# 분산 동시성 스트레스 테스트 시스템

> 수강신청은 왜 터지는가. 로드밸런서·서버 3대·우선순위 큐를 직접 만들고, k6로 부하를 걸어 한계를 확인한 뒤 **적응형 동시 처리 제어**로 개선한 실험입니다.

- 2025.11 · 우아한테크코스 8기 프리코스 오픈미션 · 개인 프로젝트
- Java 21 · Spring Boot 3.2 · Spring Data JPA · WebFlux · PostgreSQL · Docker · k6

## 결과 요약

초당 2,000건의 수강신청 요청(k6, 60초, VU 1,000~5,000)을 같은 조건으로 걸었을 때의 결과입니다.

| 지표 | 고정 동시 처리 수 | 적응형 동시 처리 제어 |
| :--- | :---: | :---: |
| 실패율 (`http_req_failed`) | 20.79% | **0.00%** |
| p95 응답 시간 | 11.71s | **462.06ms** |
| 완료된 요청 수 | 79,447 | **119,112** |
| 초당 처리량 | 1,221/s | **1,981/s** |

## 시스템 아키텍처

```
[k6 부하 생성기]
         ↓
[로드밸런서 (포트 8080)]   ← 라운드로빈 분배 (WebFlux WebClient)
         ↓
    ┌────┴────┬────────┐
    ↓         ↓        ↓
[서버1]   [서버2]   [서버3]   ← 서버마다 우선순위 큐 + 적응형 동시 처리 제어
포트8081  포트8082  포트8083
    ↓         ↓        ↓
    └────┬────┴────────┘
         ↓
   [PostgreSQL]   ← 3대가 공유, 강좌 행에 비관적 락
```

- **로드밸런서**: `AtomicInteger` 인덱스를 `getAndIncrement() % 서버 수`로 돌려 요청을 서버 3대에 순서대로 나눕니다.
- **서버 3대**: 같은 코드를 프로필(`server1/2/3`)만 바꿔 띄운 독립 인스턴스입니다.
- **공유 PostgreSQL**: 서버 3대가 같은 DB를 보기 때문에, 같은 강좌의 남은 자리를 동시에 건드리는 실제 경합이 생깁니다.

## 프로젝트 구조

```
src/main/java/com/stresstest/
├── loadbalancer/       # 로드밸런서 (LoadBalancer, LoadBalancerController)
├── controller/         # 서버 REST API (ServerController)
├── service/            # 신청 처리 (EnrollmentService, EnrollmentProcessor)
├── queue/              # 우선순위 큐 + 적응형 제어 (PriorityEnrollmentQueue)
├── repository/         # JPA 리포지토리 (비관적 락 쿼리 포함)
├── model/              # 도메인 모델 (Course, Enrollment, EnrollmentRequest)
└── Application.java

src/main/resources/     # 공통 설정 + 프로필별 설정 (lb, server1~3)
k6/                     # 부하 테스트 스크립트 (enroll-spike.js, courses-baseline.js)
```

## 요청 처리 흐름

```
POST /lb/enroll
  → 로드밸런서가 서버 하나를 골라 POST /api/enroll 전달
  → 서버는 요청을 우선순위 큐에 넣고 바로 200 "queued" 응답 (비동기 처리)
  → processQueue()
      ├─ pollRequest(): 처리 중인 수 ≥ 동시 처리 한도이면 꺼내지 않고 대기
      └─ 한도 안이면 꺼내서 처리 (처리 중 +1)
          → EnrollmentProcessor.processEnrollment()  ← 트랜잭션 + 비관적 락
          → finally: onProcessed(걸린 시간, 성공 여부)  ← 동시 처리 한도 조절
                     releaseSlot()                      ← 처리 중 -1
                     큐가 남아 있으면 다음 processQueue() 실행
```

### 우선순위 큐

- `PriorityBlockingQueue`(초기 용량 10,000)에 `priority` 기준으로 정렬해 넣습니다.
- `priority` 값이 **낮을수록 먼저** 처리됩니다. (k6에서는 0~99를 무작위로 부여)

### DB 동시성 제어 (비관적 락)

`processEnrollment()`는 하나의 `@Transactional` 안에서 아래 순서로 처리합니다. 중간에 하나라도 실패하면 전부 롤백됩니다.

1. 같은 사용자가 같은 강좌에 이미 신청했는지 확인 → 중복이면 거절
2. 강좌 행을 `@Lock(PESSIMISTIC_WRITE)`로 조회 → 트랜잭션이 끝날 때까지 다른 서버의 접근 차단
3. 정원이 찼는지 확인 → 찼으면 거절
4. 신청 인원 +1, 신청 기록 저장

서버 3대가 동시에 같은 강좌에 접근해도 정원을 넘겨 등록되지 않습니다. 테스트 후 DB를 확인하면 정원 100명 강좌에 정확히 100명만 등록되어 있습니다.

## 적응형 동시 처리 제어

### 왜 필요했나

처음에는 서버마다 동시 처리 수를 **고정값**으로 두었습니다.

- 너무 작으면 서버에 여유가 있어도 큐에서 오래 기다립니다.
- 너무 크면 DB 락 경합과 스레드가 몰려 응답이 느려지고 실패가 늘어납니다.

실제로 큐 용량과 동시 처리 수를 10배로 늘려도 VU 1만 명 조회 테스트의 실패율은 84.47%에서 77.84%로 조금 줄었을 뿐입니다(아래 [부하 테스트 상세](#부하-테스트-상세) 참고). 값을 하나로 정해 두는 대신, **서버 상태를 보고 한도를 스스로 맞추게** 바꿨습니다.

### 동작 원리: AIMD

TCP 혼잡 제어와 같은 **AIMD**(Additive Increase, Multiplicative Decrease) 방식입니다.

- **잘 되고 있으면 조금씩 늘린다** (+1)
- **문제가 생기면 크게 줄인다** (×0.7)

요청 하나가 끝날 때마다 `onProcessed(durationMs, success)`가 호출되고, 걸린 시간과 성공 여부로 한도를 조절합니다.

| 상황 | 조건 | 동시 처리 한도 |
| :--- | :--- | :---: |
| 빠르게 성공 | 250ms 이내 성공이 50번 쌓임 | **+1** |
| 큐가 많이 밀림 | 큐에 500건 이상 대기 중이고 성공이 5번 쌓임 | **+1** |
| 실패 | 처리 실패 1번 | **×0.7** |
| 너무 느림 | 성공했더라도 500ms(목표의 2배) 초과 | **×0.7** |

- 한도는 **최소 20 ~ 최대 200** 사이에서만 움직이며, 최소값 20에서 시작합니다.
- 큐가 500건 이상 밀려 있으면 늘리는 조건을 느슨하게 해서, 줄이 길 때 처리량을 더 빨리 끌어올립니다.
- 줄일 때는 비율로 크게 줄여 과부하에서 빠르게 벗어나고, 늘릴 때는 1씩 조심스럽게 올립니다.

이렇게 하면 한도가 **서버가 감당할 수 있는 지점 근처에서 자동으로 오르내립니다.**

### 핵심 코드 (`PriorityEnrollmentQueue`)

```java
public void onProcessed(long durationMs, boolean success) {
    if (success) {
        boolean fastEnough = durationMs <= targetLatencyMs;              // 250ms 이내?
        int succ = successesSinceIncrease.incrementAndGet();
        boolean largeBacklog = queue.size() >= backlogBoostThreshold;   // 500건 이상 대기?
        int thresholdForIncrease = Math.max(5, successesForIncrease / (largeBacklog ? 2 : 1));
        if ((fastEnough && succ >= thresholdForIncrease) || (largeBacklog && succ >= 5)) {
            successesSinceIncrease.set(0);
            maxConcurrentRequests.set(Math.min(maxConcurrent, maxConcurrentRequests.get() + 1)); // +1
        }
    } else {
        successesSinceIncrease.set(0);
        shrink();                                                        // ×0.7
    }
    if (durationMs > targetLatencyMs * 2) {                              // 500ms 초과
        successesSinceIncrease.set(0);
        shrink();
    }
}

private void shrink() {
    int cur = maxConcurrentRequests.get();
    maxConcurrentRequests.set(Math.max(minConcurrent, (int) Math.floor(cur * decreaseRatio)));
}
```

(읽기 쉽게 로그와 EMA 계산 부분을 줄인 코드입니다. 전체 코드는 [`PriorityEnrollmentQueue.java`](src/main/java/com/stresstest/queue/PriorityEnrollmentQueue.java)에 있습니다.)

### 설정값

`@Value`로 주입하며, 따로 지정하지 않으면 아래 기본값을 씁니다.

| 설정 키 | 기본값 | 의미 |
| :--- | :---: | :--- |
| `queue.adaptive.minConcurrent` | 20 | 동시 처리 한도 최소값 (시작값) |
| `queue.adaptive.maxConcurrent` | 200 | 동시 처리 한도 최대값 |
| `queue.adaptive.targetLatencyMs` | 250 | 목표 처리 시간(ms), 2배를 넘으면 축소 |
| `queue.adaptive.decreaseRatio` | 0.7 | 축소 비율 |
| `queue.adaptive.successesForIncrease` | 50 | +1 하기 위해 필요한 성공 횟수 |
| `queue.adaptive.backlogBoostThreshold` | 500 | 큐가 밀렸다고 판단하는 대기 건수 |

현재 한도와 상태는 `GET /lb/queue/status`(서버에서는 `GET /api/queue/status`)로 확인할 수 있습니다. 큐 크기, 처리 중인 수, 현재 한도, 처리 시간 지수이동평균(`emaLatencyMs`), 누적 처리 수를 돌려줍니다.

### 한계와 개선 방향

다시 코드를 보며 정리한, 앞으로 고칠 부분입니다.

- **정상적인 거절도 실패로 셉니다.** "정원 초과", "이미 신청함"도 `success=false`라서 한도가 줄어듭니다. 정원이 찬 강좌에 요청이 몰리면 서버에 문제가 없어도 한도가 계속 내려갑니다. 서버 과부하(예외, 타임아웃)와 비즈니스상 거절을 구분해야 합니다.
- **평균값을 판단에 쓰지 않습니다.** 처리 시간 지수이동평균(`emaLatencyMs`)을 계산하지만 지표 조회에만 쓰고, 판단은 요청 한 건의 처리 시간으로 합니다. 느린 요청 하나에도 한도가 줄어들 수 있어, 평균값 기준으로 판단하면 더 안정적입니다.
- **한도 확인과 점유가 원자적이지 않습니다.** `pollRequest()`의 "처리 중 수 확인 → 꺼내기 → +1"이 하나로 묶여 있지 않아, 동시에 여러 스레드가 들어오면 한도를 잠깐 넘을 수 있습니다. `Semaphore`나 `compareAndSet`으로 막을 수 있습니다.
- **k6 응답 시간은 "큐에 넣기"까지의 시간입니다.** `/enroll`은 큐에 넣고 바로 200을 돌려주므로, k6 수치는 실제 DB 처리 완료 시간이 아닙니다. 처리 완료까지의 시간은 서버 로그나 `emaLatencyMs`로 따로 봐야 합니다.

## 실행 가이드 (macOS)

### 사전 준비

- Java 21, Gradle, k6 설치
- Docker Desktop 설치 (PostgreSQL 실행용)

### 1) PostgreSQL 실행

```bash
docker run --name pg-stresstest \
  -e POSTGRES_USER=app -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=stresstest \
  -p 5432:5432 -d postgres:16
```

### 2) 로드밸런서·서버 실행

IntelliJ에서 실행 구성을 4개 만들고, 활성 프로필을 각각 `lb`, `server1`, `server2`, `server3`로 지정해 동시에 실행합니다.

| 프로필 | 포트 | 역할 |
| :--- | :---: | :--- |
| `lb` | 8080 | 로드밸런서 |
| `server1` | 8081 | 서버 1 |
| `server2` | 8082 | 서버 2 |
| `server3` | 8083 | 서버 3 |

```bash
curl http://localhost:8080/lb/health
```

### 3) 부하 테스트

```bash
# 수강신청 부하: 초당 RATE건, DURATION 동안, VU VUS~MAX_VUS
# 실패가 0이면 RATE와 VUS를 10배씩 올리며 반복
BASE_URL="http://localhost:8080" RATE="200" DURATION="60s" VUS="100" MAX_VUS="500" COURSE_ID="1" k6 run k6/enroll-spike.js

# 강좌 조회 부하
BASE_URL="http://localhost:8080" VUS="100" DURATION="30s" k6 run k6/courses-baseline.js
```

`enroll-spike.js`는 `constant-arrival-rate`로 초당 요청 수를 고정하고, 성공 기준을 **실패율 1% 미만, p95 500ms 미만**으로 둡니다.

## 부하 테스트 상세

### 수강신청 (`enroll-spike.js`)

| 단계 | 조건 | 실패율 | p95 |
| :--- | :--- | :---: | :---: |
| 1 | 초당 200건, VU 100~500 | 0.00% | 21.44ms |
| 2 | 초당 2,000건, VU 1,000~5,000 (고정 한도) | 20.79% | 11.71s |
| 3 | 초당 2,000건, VU 1,000~5,000 (**적응형 제어**) | **0.00%** | **462.06ms** |

<details>
<summary>1. 초당 200건, VU 100~500</summary>

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
<summary>2. 초당 2,000건, VU 1,000~5,000 (고정 한도)</summary>

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
<summary>3. 초당 2,000건, VU 1,000~5,000 (적응형 제어)</summary>

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
<summary>DB 등록 결과 확인 (정원 100명 강좌에 정확히 100명)</summary>

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

### 강좌 조회 (`courses-baseline.js`, 성공 기준 p95 300ms 미만)

| 단계 | 조건 | 실패율 | p95 |
| :--- | :--- | :---: | :---: |
| 1 | VU 100 | 0.00% | 27.19ms |
| 2 | VU 1,000 (10배) | 0.00% | 714.1ms |
| 3 | VU 10,000 (100배) | 84.47% | 29.07s |
| 4 | VU 10,000, 큐 용량·동시 처리 수 10배 | 77.84% | 25.31s |

<details>
<summary>1. VU 100</summary>

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
<summary>2. VU 1,000 (10배)</summary>

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
<summary>3. VU 10,000 (100배)</summary>

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
<summary>4. VU 10,000, 큐 용량·동시 처리 수 10배</summary>

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

## 트러블슈팅

- **H2 → 공유 PostgreSQL**: 서버마다 H2(인메모리 DB)를 쓰면 서버끼리 경합이 생기지 않습니다. Docker로 PostgreSQL을 띄워 서버 3대가 같은 DB를 보게 바꿨습니다.
- **로드밸런서 응답 헤더 중복**: 로드밸런서가 서버 응답을 헤더째 그대로 다시 감싸 보내, k6에서 헤더가 두 번 붙은 응답을 모두 에러로 처리했습니다. `LoadBalancer.java`에서 `exchangeToMono`로 응답 본문만 꺼내 전달하도록 고쳤습니다.
- **고정 한도의 한계 → 적응형 제어**: 처리 시간과 성공 여부로 동시 처리 한도를 늘리고 줄이도록 바꿔, 같은 시간에 더 많이 처리하고 실패율을 0%로 낮췄습니다.

## 결론

- 우선순위 큐를 수정해 서버에 과부하가 가지 않도록 한 결과, 최적화 전에는 실패가 발생하고 같은 시간 동안 훨씬 적게 처리했지만, 최적화 후에는 같은 시간에 더 많이 처리하면서 실패율도 크게 낮아졌습니다.
- 서버의 역량이 우선순위 큐보다 더 중요하고, 그래서 수강신청·좌석 예매처럼 순간 접속자가 크게 뛰는 서비스는 서버를 여러 대 두어 부하를 나누거나, 큐로 동시에 들어올 수 있는 인원을 제한한다는 것을 알게 되었습니다.
- 앞으로는 사용량이 늘어날 것을 예상해 서버를 미리 늘리는 확장(가상 머신, 오토스케일링 등)을 염두에 두고 설계하는 것이 좋아 보입니다.
- 하나의 DB에 모든 요청이 몰리기보다, 강좌별로 DB를 나누는 등 DB 부하를 분산하면 더 많은 사용자가 쾌적하게 쓸 수 있을 것이라고 생각합니다.
- 수정할 때마다 커밋을 남겨야 왜 이런 결과가 나왔는지 나중에 확실히 알 수 있다는 것을 느꼈습니다. 앞으로는 결과를 혼자 확인하는 데서 끝내지 않고, 수정 하나당 커밋 하나로 과정을 기록하려 합니다.

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다.
