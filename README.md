# 분산 동시성 스트레스 테스트 시스템

자바를 활용한 로드밸런서와 여러 서버 인스턴스를 통한 분산 동시성 스트레스 테스트 시스템입니다. 우선순위 큐를 통한 접근 제어를 구현하여 수강신청 고부하 시나리오를 시뮬레이션합니다.

## 주요 기능

- 로드밸런서: 라운드로빈 방식으로 여러 서버에 요청 분산
- 다중 서버 인스턴스: 독립적인 서버 인스턴스로 부하 분산
- 우선순위 큐: 요청 우선순위에 따른 처리 순서 관리, 라운드 로빈 활용
- 동시 접근 제어: 최대 동시 처리 수 제한으로 시스템 안정성 확보
- 데이터베이스 연동: PostgreSQL 데이터베이스 사용
- 스트레스 테스트(k6): 외부 도구(k6)를 사용하여 대량의 동시 요청 생성 및 지표 수집

## 시스템 아키텍처

```
[k6 부하 생성기]
         ↓
[로드밸런서 (포트 8080)]
         ↓
    ┌────┴────┬────────┐
    ↓         ↓        ↓
[서버1]   [서버2]   [서버3]
포트8081  포트8082  포트8083
    ↓         ↓        ↓
    └────┬────┴────────┘
         ↓
   [PostgreSQL 데이터베이스]
```

## 기술 스택

- **Java 21**
- **Spring Boot 3.2.0**
- **Spring Data JPA**
- **PostgreSQL**
- **WebFlux** (로드밸런서의 비동기 HTTP 클라이언트)
- **Gradle**
- **Docker**

## 프로젝트 구조

```
src/main/java/com/stresstest/
├── model/              # 도메인 모델 (Course, Enrollment, EnrollmentRequest)
├── repository/         # 데이터베이스 리포지토리
├── service/            # 비즈니스 로직 (EnrollmentService)
├── queue/              # 우선순위 큐 관리 (PriorityEnrollmentQueue)
├── controller/         # 서버 REST API 컨트롤러
├── loadbalancer/       # 로드밸런서 구현
├── resoureces/         # 서버 별 설정 yaml파일
└── Application.java    # 메인 애플리케이션
```

## 우선순위 큐 동작 방식

1. **요청 수신**: 클라이언트로부터 수강신청 요청이 들어옵니다.
2. **큐 추가**: 요청은 우선순위(priority) 값에 따라 우선순위 큐에 추가됩니다.
   - 낮은 priority 값 = 높은 우선순위
3. **접근 제어**: 현재 처리 중인 요청 수가 최대 동시 접근 수를 초과하면 대기합니다.
4. **순차 처리**: 우선순위에 따라 요청을 순차적으로 처리합니다.

## 설정 파일

### application.yml

기본 설정 파일로 데이터베이스, 로드밸런서, 로깅 설정을 포함합니다.

### application-server1/2/3.yml

각 서버 인스턴스의 포트와 데이터베이스 설정을 정의합니다.

### application-lb.yml

로드밸런서의 포트와 서버 목록을 정의합니다.

## macOS 실행 가이드

- 수행환경이 macOS 이므로, macOS에서의 실행 가이드만 추가하였습니다.

### 사전 준비

- Java 21, Gradle, k6 설치
- Docker Desktop 설치(PostgreSQL 사용)

### 공유 PostgreSQL로 실제 동시성 검증

여러 서버 인스턴스가 같은 DB를 보도록 설정해 락/경합을 실제와 가깝게 측정합니다.

```bash
# 1) Docker로 Postgres 실행
docker run --name pg-stresstest \
  -e POSTGRES_USER=app -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=stresstest \
  -p 5432:5432 -d postgres:16

# 2) 서버/로드밸런서 시작 (server1/2/3 프로필은 Postgres DSN이 이미 설정됨)

# inteliJ 활용, 구성 파일 편집을 통해 활성화된 프로파일에 lb, server1, 2, 3을 입력하고 동시에 실행
# 이후, PostgreSQL과 연결되었는지 확인 및 포트 8080(lb), 8081(server1), 8082(server2), 8083(server3)
# 가 잘 연결되었는지 확인

# 3) 확인 및 부하 테스트

# 확인
curl http://localhost:8080/lb/health

# 등록 부하 테스트
# 환경 변수를 설정하고 k6 실행
# 초당 200회 반복 요청, 60초 진행, 100-500명
# 실패량이 0인 경우 10배씩 RATE와 VUS를 증가시키며 테스트 수행
BASE_URL="http://localhost:8080" RATE="200" DURATION="60s" VUS="100" MAX_VUS="500" COURSE_ID="1" k6 run k6/enroll-spike.js

# 강좌 조회 테스트
# 환경 변수를 설정하고 k6 실행
BASE_URL="http://localhost:8080" VUS="100" DURATION="30s" k6 run k6/courses-baseline.js
```

## 부하 테스트

### Enroll

1. 기본 설정, 초당 200회, VUS 100-500

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

2. 초당 2000회, VUS 1000-5000

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

3. 실제 데이터베이스 등록 확인

```Text
docker exec -it pg-stresstest psql -U app -d stresstest -c "SELECT * FROM courses;"
 capacity | current_enrollment | id |       name
----------+--------------------+----+------------------
       50 |                  0 |  2 | Spring Boot
       30 |                  0 |  3 | Database Design
      100 |                100 |  1 | Java Programming
(3 rows)
```

### courses

1. 기본 설정, 100명의 사용자

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

2. 10배, 초당 1000명의 사용자

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

3. 100배, 초당 1만명의 사용자

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

4. 100배, 우선순위 큐의 capacity와 동시 접근 가능 수 10배로 증가

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

## 최적화 수행

- 가능한 최적화는 여러 부분이라고 생각(데이터 베이스 테이블 구성, 쿼리 문 수정, 등등?), 하지만 우선순위 큐의 최적화를 수행하고 어떻게 결과가 달라지는지, 로드 밸런서가 얼마나 유의미한 차이를 두는 지 알고 싶어서 진행
- 시스템이 과부화 상태가 아니라면, 동시 요청 수를 점진적으로 증가 / 시스템이 과부하 상태라고 판단되면, 동시 요청 수를 점진적으로 감소 하는 최적화코드 추가

- 초당 2000회, 1000-5000명의 사용자가 등록 신청

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

## 결론

- 우선순위 큐를 수정하여, 서버에 과부하가 가지 않도록 최적화를 수행한 결과 최적화 전의 결과에는 실패가 발생하고, 동일한 시간동안 훨씬 더 적은 결과를
  도출하였으나, 최적화를 수행 후 더 많은 결과를 동일한 시간에 도출하며 실패 rate도 훨씬 더 적은 모습을 확인할 수 있었습니다.
- 서버의 역량이 우선순위 큐 보다 더 중요하고, 이러한 이유로 대부분의 수강신청, 좌석신청등의 순간 접속자가 엄청나게 뛰는 경우
  서버를 여러개 두어, 부하를 분산시키거나 큐를 사용해서 접속할 수 있는 인원의 수에 제한을 두는 방식으로 수행한다는 것을 알게 되었습니다.
- 추후, 로드밸런서의 추측성 확장을 염두에 두고 가상 머신등을 활용해서 사용량이 많아지더라도 버틸 수 있도록 설계하는것이
  좋아 보입니다.
- 또한 DB를 분산시켜서 하나의 DB에서만 작동하는 것 보다는 DB별로 수강 인원의 크기를 나눠서 설계하는 것이 여러 명의 사용자가 더 좋은 시스템 환경을 느낄 수 있을 것이라고 생각합니다.
- 중간중간 commit을 계속해서 남기면서 version을 높여가며 코드를 수정해야 왜 이런 과정이 나왔는지, 코드의 결과가 이렇게 된 이유가 뭔지 확실하게 알 수 있게 된다는 것을 느낌
  - 앞으로는 모든 결과를 혼자 확인하는것보다 기록하는 것도 상당히 중요하다는 것을 깨달았음으로, 하나의 수정당 commit을 남겨 의미있는 과정을 확인 가능하도록 남겨야겠다는 생각을 함.

## 수정된 부분

- H2 데이터베이스에서, 공유 데이터베이스로 PostgreSQL을 활용
- Docker를 활용해서 PostgreSQL을 실행시키고, 서버가 하나의 DB를 바라보도록 설정
- k6 프로그램을 활용하여, 수강신청 등록에 대한 과부화를 버틸 수 있는지 스트레스 테스트 진행
- 기존 lb에서 HTTP통신 응답을 받아 헤더를 제외하지 않고 보내는 문제 발생
  - k6에서는 두개의 헤더가 붙어서 나오는 것을 보고, 모두 에러로 처리
  - 해당 문제를 해결하기 위해, `loadBalancer.java`파일의 메세지를 `exchangeToMono`를 활용해서 메세지의 헤더를 제외하도록 코드 수정
- 우선순위 큐에서, 지연 시간을 따져 점진적으로 확장 또는 축소를 수행하도록 최적화를 설정
  - 기존의 결과보다 동일한 시간 더 많은 output과 더 낮은 failed_rate를 보여줌

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다.
