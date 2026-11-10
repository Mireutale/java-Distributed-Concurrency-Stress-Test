# 분산 동시성 스트레스 테스트

> **수강신청 사이트는 왜 터질까?**
> 직접 작은 수강신청 시스템을 만들고 요청을 한꺼번에 쏟아부어 어디서 무너지는지 확인한 뒤, 무너지지 않게 고친 실험입니다.

- 2025.11 · 우아한테크코스 8기 프리코스 오픈미션 · 개인 프로젝트
- Java 21 · Spring Boot 3.2 · Spring Data JPA · WebFlux · PostgreSQL · Docker · k6

## 한눈에 보기

1초에 2,000명이 동시에 수강신청 버튼을 누르는 상황을 1분 동안 만들었습니다.

| | 고치기 전 | 고친 후 |
| :--- | :---: | :---: |
| 실패한 요청 | 100건 중 약 **21건** | **0건** |
| 느린 쪽 응답 시간 (p95) | **11.7초** | **0.46초** |
| 1초에 처리한 요청 | 약 1,200건 | 약 **2,000건** |

> **p95**: 요청 100개를 빠른 순서로 줄 세웠을 때 95번째 요청의 응답 시간입니다. "대부분의 사용자가 이 시간 안에 응답을 받는다"는 뜻입니다.

**무엇을 고쳤나?** 서버가 한 번에 처리하는 요청 수를 고정해 두지 않고, 서버 상태를 보면서 **스스로 늘렸다 줄였다** 하게 만들었습니다. ([아래에서 자세히](#핵심-서버가-스스로-처리량을-조절하게-만들기))

## 용어 정리

| 용어 | 쉬운 설명 |
| :--- | :--- |
| 로드밸런서 | 들어온 요청을 여러 서버에 골고루 나눠 주는 안내원 |
| 큐 (Queue) | 처리를 기다리는 요청들의 대기 줄 |
| 동시 처리 한도 | 서버가 한 번에 처리하는 요청의 최대 개수 |
| 락 (Lock) | 한 사람이 데이터를 고치는 동안 다른 사람이 못 건드리게 잠그는 것 |
| k6 | 가짜 사용자를 대량으로 만들어 요청을 보내는 부하 테스트 도구 |
| VU (Virtual User) | k6가 만드는 가짜 사용자 한 명 |

## 시스템 구조

```
        [k6: 가짜 사용자 수천 명]
                   │
                   ▼
        [로드밸런서 :8080]        요청을 서버 1 → 2 → 3 → 1 … 순서로 나눠 줌
           ┌───────┼───────┐
           ▼       ▼       ▼
       [서버1]  [서버2]  [서버3]   서버마다 대기 줄(큐)과 동시 처리 한도가 있음
        :8081    :8082    :8083
           └───────┼───────┘
                   ▼
            [PostgreSQL DB]       서버 3대가 DB 하나를 같이 씀
```

**왜 서버 3대가 DB 하나를 같이 쓰나요?**
실제 수강신청처럼 여러 서버가 **같은 강의의 남은 자리를 동시에 건드리는 상황**을 만들기 위해서입니다. 서버마다 DB가 따로 있으면 이런 충돌이 생기지 않아 실험이 의미가 없습니다.

## 수강신청 요청 하나가 처리되는 과정

```
① 사용자가 수강신청 → 로드밸런서가 서버 하나를 골라 전달
② 서버는 요청을 대기 줄에 세우고, 사용자에게 바로 "접수됨" 응답
③ 서버는 한도 안에서 줄 앞의 요청부터 꺼내 처리
   ├─ 이미 신청했나? → 거절
   ├─ 강의 정보를 잠그고(락) 남은 자리 확인 → 꽉 찼으면 거절
   └─ 자리가 있으면 신청 완료 처리
④ 처리가 끝나면 걸린 시간과 성공 여부를 보고 한도를 조절
⑤ 줄에 요청이 남아 있으면 다음 요청 처리
```

### 대기 줄에도 순서가 있습니다 (우선순위 큐)

먼저 온 순서가 아니라 **우선순위 숫자가 작은 요청부터** 처리합니다. (테스트에서는 0~99를 무작위로 줬습니다.)
예를 들어 졸업 예정자를 먼저 받아 주는 것 같은 규칙을 넣을 수 있는 구조입니다.

### 정원보다 많이 들어가지 않게 (비관적 락)

서버 3대가 동시에 "남은 자리 1개"를 보고 셋 다 신청을 받아 버리면 정원을 넘깁니다.
이걸 막기 위해, 강의 정보를 읽는 순간 **잠그고**(`PESSIMISTIC_WRITE`) 신청 처리가 끝날 때까지 다른 서버는 기다리게 했습니다.

신청 확인 → 자리 확인 → 인원 +1 → 기록 저장은 **하나의 묶음(트랜잭션)** 으로 처리해서, 중간에 하나라도 실패하면 전부 없던 일로 되돌립니다.

결과적으로 테스트 후 DB를 보면 **정원 100명 강의에 정확히 100명만** 들어가 있습니다.

## 핵심: 서버가 스스로 처리량을 조절하게 만들기

### 문제: 한도를 몇으로 정해야 할까?

처음에는 서버마다 "한 번에 최대 N개 처리"를 **고정값**으로 정해 두었습니다. 그런데 딱 맞는 N을 정하기가 어렵습니다.

- **너무 작으면** → 서버는 놀고 있는데 사용자는 줄에서 오래 기다림
- **너무 크면** → DB 잠금 대기가 몰려 모두 느려지고, 결국 실패가 쏟아짐

실제로 한도와 대기 줄 크기를 10배로 늘려 봐도 실패율은 84%에서 78%로 조금 줄었을 뿐이었습니다.

### 해결: 상황을 보고 스스로 늘리고 줄이기

고속도로 진입로 신호등을 떠올리면 쉽습니다. 도로가 한산하면 차를 더 자주 들여보내고, 막히기 시작하면 진입 간격을 확 늘립니다.

요청 하나가 끝날 때마다 **얼마나 걸렸는지**, **성공했는지**를 보고 한도를 바꿉니다.

| 상황 | 언제 | 한도 |
| :--- | :--- | :---: |
| 😊 잘 되고 있음 | 0.25초 안에 성공한 요청이 50번 쌓이면 | **1 늘림** |
| 🚶 줄이 너무 김 | 500명 넘게 기다리는 중에 성공이 5번 쌓이면 | **1 늘림** |
| ❌ 실패 | 요청이 실패하면 바로 | **30% 줄임** |
| 🐢 너무 느림 | 성공했어도 0.5초를 넘기면 | **30% 줄임** |

- 한도는 **20에서 시작**하고, **20 ~ 200** 사이에서만 움직입니다.
- **늘릴 때는 1씩 조심스럽게, 줄일 때는 30%씩 크게** 줄입니다. 위험할 땐 빨리 물러나고, 괜찮을 땐 천천히 늘리는 방식입니다.
- 이 방식은 인터넷 통신(TCP)이 회선 혼잡을 피할 때 쓰는 **AIMD**(조금씩 늘리고 크게 줄이기)와 같은 원리입니다.

이렇게 하면 한도가 **서버가 감당할 수 있는 지점 근처를 스스로 찾아갑니다.**

### 코드로 보면

[`PriorityEnrollmentQueue.java`](src/main/java/com/stresstest/queue/PriorityEnrollmentQueue.java)의 핵심 부분입니다. (읽기 쉽게 로그 등은 뺐습니다.)

```java
// 요청 하나가 끝날 때마다 호출: 걸린 시간(ms)과 성공 여부를 받음
public void onProcessed(long durationMs, boolean success) {
    if (success) {
        boolean fastEnough = durationMs <= targetLatencyMs;             // 0.25초 안에 끝났나?
        int succ = successesSinceIncrease.incrementAndGet();            // 성공 횟수 +1
        boolean largeBacklog = queue.size() >= backlogBoostThreshold;  // 500명 넘게 기다리나?
        int thresholdForIncrease = Math.max(5, successesForIncrease / (largeBacklog ? 2 : 1));
        if ((fastEnough && succ >= thresholdForIncrease) || (largeBacklog && succ >= 5)) {
            successesSinceIncrease.set(0);
            maxConcurrentRequests.set(Math.min(maxConcurrent, maxConcurrentRequests.get() + 1)); // 한도 +1
        }
    } else {
        successesSinceIncrease.set(0);
        shrink();                                                       // 실패 → 한도 30% 줄임
    }
    if (durationMs > targetLatencyMs * 2) {                             // 0.5초 넘게 걸림
        successesSinceIncrease.set(0);
        shrink();                                                       // 느림 → 한도 30% 줄임
    }
}

private void shrink() {
    int cur = maxConcurrentRequests.get();
    maxConcurrentRequests.set(Math.max(minConcurrent, (int) Math.floor(cur * decreaseRatio))); // ×0.7, 최소 20
}
```

<details>
<summary>설정값 바꾸기</summary>

설정 파일에 따로 적지 않으면 아래 기본값을 씁니다.

| 설정 키 | 기본값 | 의미 |
| :--- | :---: | :--- |
| `queue.adaptive.minConcurrent` | 20 | 한도 최소값 (시작값) |
| `queue.adaptive.maxConcurrent` | 200 | 한도 최대값 |
| `queue.adaptive.targetLatencyMs` | 250 | 목표 처리 시간(ms). 2배를 넘으면 한도를 줄임 |
| `queue.adaptive.decreaseRatio` | 0.7 | 줄일 때 곱하는 비율 |
| `queue.adaptive.successesForIncrease` | 50 | 한도를 1 늘리는 데 필요한 성공 횟수 |
| `queue.adaptive.backlogBoostThreshold` | 500 | "줄이 너무 길다"고 보는 대기 인원 |

지금 상태는 `GET /lb/queue/status`로 볼 수 있습니다. 대기 인원, 처리 중인 수, 현재 한도, 평균 처리 시간(`emaLatencyMs`), 누적 처리 수를 알려 줍니다.

</details>

### 아직 아쉬운 점

코드를 다시 보며 찾은, 앞으로 고칠 부분입니다.

1. **"자리 없음"도 실패로 셉니다.** 정원이 찬 강의에 신청이 몰리면 서버는 멀쩡한데도 한도가 계속 줄어듭니다. 서버가 힘들어서 생긴 실패와 정상적인 거절을 구분해야 합니다.
2. **요청 하나만 보고 판단합니다.** 평균 처리 시간을 계산해 두고도 판단에는 쓰지 않아서, 느린 요청 하나에도 한도가 줄어듭니다. 평균으로 판단하면 더 안정적입니다.
3. **한도를 잠깐 넘길 수 있습니다.** "지금 몇 개 처리 중인지 확인 → 꺼내기 → 개수 +1"이 한 번에 묶여 있지 않아서, 여러 요청이 동시에 들어오면 한도를 살짝 넘길 수 있습니다. `Semaphore`로 막을 수 있습니다.
4. **측정한 응답 시간은 "접수"까지의 시간입니다.** 서버가 줄에 세우자마자 "접수됨"으로 응답하기 때문에, k6가 잰 시간은 실제 신청 완료까지의 시간이 아닙니다.

## 직접 실행해 보기 (macOS)

**준비물**: Java 21, Gradle, k6, Docker Desktop

**1단계. DB 띄우기**

```bash
docker run --name pg-stresstest \
  -e POSTGRES_USER=app -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=stresstest \
  -p 5432:5432 -d postgres:16
```

**2단계. 로드밸런서와 서버 3대 띄우기**

IntelliJ에서 실행 설정을 4개 만들고, 각각 활성 프로필(Active profiles)을 아래처럼 지정해 동시에 실행합니다.

| 프로필 | 포트 | 역할 |
| :--- | :---: | :--- |
| `lb` | 8080 | 로드밸런서 |
| `server1` | 8081 | 서버 1 |
| `server2` | 8082 | 서버 2 |
| `server3` | 8083 | 서버 3 |

잘 떴는지 확인:

```bash
curl http://localhost:8080/lb/health
```

**3단계. 부하 걸기**

```bash
# 수강신청: 1초에 200건씩 60초 동안 (가짜 사용자 100~500명)
# 실패가 없으면 RATE와 VUS를 10배씩 올려 가며 반복
BASE_URL="http://localhost:8080" RATE="200" DURATION="60s" VUS="100" MAX_VUS="500" COURSE_ID="1" k6 run k6/enroll-spike.js

# 강의 목록 조회: 가짜 사용자 100명이 30초 동안
BASE_URL="http://localhost:8080" VUS="100" DURATION="30s" k6 run k6/courses-baseline.js
```

수강신청 테스트는 **실패율 1% 미만, p95 0.5초 미만**이면 통과로 봅니다.

## 테스트 결과 자세히

### 수강신청

부하를 10배씩 올리며 한계를 찾고, 무너진 지점에서 적응형 제어를 넣어 다시 측정했습니다.

| 단계 | 조건 | 실패율 | p95 | 결과 |
| :--- | :--- | :---: | :---: | :---: |
| 1 | 1초에 200건 | 0% | 0.02초 | ✅ 통과 |
| 2 | 1초에 2,000건 (고정 한도) | 20.8% | 11.7초 | ❌ 무너짐 |
| 3 | 1초에 2,000건 (**적응형 제어**) | **0%** | **0.46초** | ✅ 통과 |

<details>
<summary>1단계 k6 원본 결과</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✓ 'p(95)<500' p(95)=21.44ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%


  █ TOTAL RESULTS

    checks_total.......: 11975   199.149364/s
    checks_succeeded...: 100.00% 11975 out of 11975
    checks_failed......: 0.00%   0 out of 11975

  avg=8.78ms   min=707µs    med=1.72ms   max=905.97ms p(90)=8.22ms   p(95)=21.44ms
```

</details>

<details>
<summary>2단계 k6 원본 결과 (고정 한도)</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✗ 'p(95)<500' p(95)=11.71s

    http_req_failed
    ✗ 'rate<0.01' rate=20.79%


  █ TOTAL RESULTS

    checks_total.......: 79447  1221.136571/s
    checks_succeeded...: 79.20% 62925 out of 79447
    checks_failed......: 20.79% 16522 out of 79447

    ✗ enroll queued 200
      ↳  79% — ✓ 62925 / ✗ 16522
  avg=3.22s min=764µs    med=1.45s    max=52.24s p(90)=10.21s p(95)=11.71s
```

</details>

<details>
<summary>3단계 k6 원본 결과 (적응형 제어)</summary>

```Text
BASE_URL="http://localhost:8080" RATE="2000" DURATION="60s" VUS="1000" MAX_VUS="5000" COURSE_ID="2" k6 run k6/enroll-spike.js

         /\      Grafana   /‾‾/
    /\  /  \     |\  __   /  /
   /  \/    \    | |/ /  /   ‾‾\
  /          \   |   (  |  (‾)  |
 / __________ \  |_|\_\  \_____/

     execution: local
        script: k6/enroll-spike.js
        output: -

     scenarios: (100.00%) 1 scenario, 5000 max VUs, 1m30s max duration (incl. graceful stop):
              * spike: 2000.00 iterations/s for 1m0s (maxVUs: 1000-5000, gracefulStop: 30s)



  █ THRESHOLDS

    http_req_duration
    ✓ 'p(95)<500' p(95)=462.06ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%


  █ TOTAL RESULTS

    checks_total.......: 119112  1981.772656/s
    checks_succeeded...: 100.00% 119112 out of 119112
    checks_failed......: 0.00%   0 out of 119112

    ✓ enroll queued 200

    HTTP
    http_req_duration..............: avg=57.12ms min=423µs    med=1.35ms   max=1.93s p(90)=89.08ms  p(95)=462.06ms
      { expected_response:true }...: avg=57.12ms min=423µs    med=1.35ms   max=1.93s p(90)=89.08ms  p(95)=462.06ms
    http_req_failed................: 0.00%  0 out of 125088
    http_reqs......................: 125088 2081.200702/s

    EXECUTION
    dropped_iterations.............: 889    14.791086/s
    iteration_duration.............: avg=160.2ms min=100.51ms med=101.55ms max=2.16s p(90)=195.14ms p(95)=583.8ms
    iterations.....................: 119112 1981.772656/s
    vus............................: 203    min=201         max=1707
    vus_max........................: 1835   min=1000        max=1835

    NETWORK
    data_received..................: 23 MB  378 kB/s
    data_sent......................: 21 MB  355 kB/s
```

</details>

<details>
<summary>DB 확인: 정원 100명 강의에 정확히 100명</summary>

```Text
docker exec -it pg-stresstest psql -U app -d stresstest -c "SELECT * FROM courses;"
 capacity | current_enrollment | id |       name
----------+--------------------+----+------------------
       50 |                  0 |  2 | Spring Boot
       30 |                  0 |  3 | Database Design
      100 |                100 |  1 | Java Programming
(3 rows)
```

</details>

### 강의 목록 조회

통과 기준: 실패율 1% 미만, p95 0.3초 미만

| 단계 | 가짜 사용자 수 | 실패율 | p95 | 결과 |
| :--- | :--- | :---: | :---: | :---: |
| 1 | 100명 | 0% | 0.03초 | ✅ 통과 |
| 2 | 1,000명 | 0% | 0.71초 | ⚠️ 느려짐 |
| 3 | 10,000명 | 84.5% | 29.1초 | ❌ 무너짐 |
| 4 | 10,000명 (대기 줄 크기·한도 10배) | 77.8% | 25.3초 | ❌ 거의 그대로 |

4단계처럼 숫자만 키워서는 거의 나아지지 않았습니다.

<details>
<summary>1단계 k6 원본 결과</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✓ 'p(95)<300' p(95)=27.19ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%

  █ TOTAL RESULTS

    HTTP
    http_req_duration..............: avg=13.26ms  min=1.4ms   med=9.09ms   max=331.58ms p(90)=22.97ms  p(95)=27.19ms
      { expected_response:true }...: avg=13.26ms  min=1.4ms   med=9.09ms   max=331.58ms p(90)=22.97ms  p(95)=27.19ms
    http_req_failed................: 0.00%  0 out of 26428
    http_reqs......................: 26428  878.894445/s

    EXECUTION
    iteration_duration.............: avg=113.58ms min=101.5ms med=109.39ms max=431.64ms p(90)=123.26ms p(95)=127.38ms
    iterations.....................: 26428  878.894445/s
    vus............................: 100    min=100        max=100
    vus_max........................: 100    min=100        max=100

    NETWORK
    data_received..................: 9.4 MB 313 kB/s
    data_sent......................: 2.1 MB 70 kB/s
```

</details>

<details>
<summary>2단계 k6 원본 결과</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✗ 'p(95)<300' p(95)=714.1ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%


  █ TOTAL RESULTS

    HTTP
    http_req_duration..............: avg=360.14ms min=3.46ms   med=334.21ms max=1.88s p(90)=599.29ms p(95)=714.1ms
      { expected_response:true }...: avg=360.14ms min=3.46ms   med=334.21ms max=1.88s p(90)=599.29ms p(95)=714.1ms
    http_req_failed................: 0.00%  0 out of 65221
    http_reqs......................: 65221  2157.724104/s

    EXECUTION
    iteration_duration.............: avg=461.89ms min=103.53ms med=435.03ms max=1.98s p(90)=701.73ms p(95)=816.78ms
    iterations.....................: 65221  2157.724104/s
    vus............................: 1000   min=1000       max=1000
    vus_max........................: 1000   min=1000       max=1000

    NETWORK
    data_received..................: 23 MB  768 kB/s
    data_sent......................: 5.2 MB 173 kB/s
```

</details>

<details>
<summary>3단계 k6 원본 결과</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✗ 'p(95)<300' p(95)=29.07s

    http_req_failed
    ✗ 'rate<0.01' rate=84.47%


  █ TOTAL RESULTS

    HTTP
    http_req_duration..............: avg=4.9s  min=0s       med=617.32ms max=55.36s p(90)=24.02s p(95)=29.07s
      { expected_response:true }...: avg=5.73s min=2.69ms   med=2.09s    max=45.04s p(90)=18.71s p(95)=29.97s
    http_req_failed................: 84.47% 36094 out of 42727
    http_reqs......................: 42727  712.101843/s

    EXECUTION
    iteration_duration.............: avg=7.8s  min=107.83ms med=2.91s    max=55.54s p(90)=24.44s p(95)=29.52s
    iterations.....................: 42727  712.101843/s
    vus............................: 6      min=6              max=10000
    vus_max........................: 10000  min=10000          max=10000

    NETWORK
    data_received..................: 5.7 MB 95 kB/s
    data_sent......................: 1.8 MB 31 kB/s
```

</details>

<details>
<summary>4단계 k6 원본 결과</summary>

```Text
  █ THRESHOLDS

    http_req_duration
    ✗ 'p(95)<300' p(95)=25.31s

    http_req_failed
    ✗ 'rate<0.01' rate=77.84%


  █ TOTAL RESULTS

    HTTP
    http_req_duration..............: avg=4.14s  min=0s       med=1.15s max=45.55s p(90)=17.29s p(95)=25.31s
      { expected_response:true }...: avg=11.08s min=7.56ms   med=4.1s  max=38.55s p(90)=26.66s p(95)=27.92s
    http_req_failed................: 77.84% 36957 out of 47477
    http_reqs......................: 47477  791.269209/s

    EXECUTION
    iteration_duration.............: avg=6.66s  min=110.76ms med=3.62s max=46.35s p(90)=18.03s p(95)=25.56s
    iterations.....................: 47477  791.269209/s
    vus............................: 1      min=1              max=10000
    vus_max........................: 10000  min=10000          max=10000

    NETWORK
    data_received..................: 7.1 MB 118 kB/s
    data_sent......................: 2.1 MB 35 kB/s
```

</details>

## 만들면서 부딪힌 문제

| 문제 | 원인 | 해결 |
| :--- | :--- | :--- |
| 서버끼리 충돌이 안 생김 | 서버마다 각자 메모리 DB(H2)를 써서 데이터를 공유하지 않음 | Docker로 PostgreSQL을 띄워 서버 3대가 같은 DB를 쓰게 함 |
| k6가 모든 응답을 에러로 판단 | 로드밸런서가 서버 응답을 헤더째 다시 감싸 보내 헤더가 두 번 붙음 | `exchangeToMono`로 응답 내용만 꺼내 전달 |
| 부하가 크면 실패가 쏟아짐 | 동시 처리 한도가 고정값이라 상황에 못 맞춤 | 처리 시간과 성공 여부를 보고 한도를 스스로 조절 |

## 배운 점

- **숫자를 키우는 것보다 조절하는 게 중요했습니다.** 한도를 10배로 늘려도 거의 나아지지 않았지만, 상황에 맞춰 조절하게 바꾸자 같은 시간에 더 많이 처리하면서 실패는 0이 됐습니다.
- **결국 서버가 감당할 수 있는 양이 핵심입니다.** 그래서 수강신청·티켓 예매처럼 순간 접속자가 폭증하는 서비스는 서버를 여러 대 두어 나누거나, 대기열로 들어오는 인원을 제한합니다.
- **다음에 해 보고 싶은 것**: 사용량이 늘면 서버를 자동으로 늘리는 구조(오토스케일링), 강의별로 DB를 나눠 한 DB에 몰리는 부하를 줄이는 구조
- **기록의 중요성**: 수정할 때마다 커밋을 남겨야 나중에 "왜 이런 결과가 나왔는지"를 알 수 있다는 걸 느꼈습니다. 앞으로는 수정 하나당 커밋 하나로 과정을 남기려 합니다.

## 라이선스

MIT
