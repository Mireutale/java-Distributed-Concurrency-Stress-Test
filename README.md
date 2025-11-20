# 분산 동시성 스트레스 테스트 시스템

자바를 활용한 로드밸런서와 여러 서버 인스턴스를 통한 분산 동시성 스트레스 테스트 시스템입니다. 우선순위 큐를 통한 접근 제어를 구현하여 수강신청이나 좌석예매와 같은 고부하 시나리오를 시뮬레이션합니다.

## 주요 기능

- **로드밸런서**: 라운드로빈 방식으로 여러 서버에 요청 분산
- **다중 서버 인스턴스**: 독립적인 서버 인스턴스로 부하 분산
- **우선순위 큐**: 요청 우선순위에 따른 처리 순서 관리
- **동시 접근 제어**: 최대 동시 처리 수 제한으로 시스템 안정성 확보
- **데이터베이스 연동**: H2 인메모리 데이터베이스 사용
- **스트레스 테스트**: 대량의 동시 요청을 통한 시스템 성능 테스트

## 시스템 아키텍처

```
[스트레스 테스트 클라이언트]
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
   [H2 데이터베이스]
```

## 기술 스택

- **Java 21**
- **Spring Boot 3.2.0**
- **Spring Data JPA**
- **H2 Database**
- **WebFlux** (비동기 HTTP 클라이언트)
- **Gradle**

## 프로젝트 구조

```
src/main/java/com/stresstest/
├── model/              # 도메인 모델 (Course, Enrollment, EnrollmentRequest)
├── repository/         # 데이터베이스 리포지토리
├── service/            # 비즈니스 로직 (EnrollmentService)
├── queue/              # 우선순위 큐 관리 (PriorityEnrollmentQueue)
├── controller/         # 서버 REST API 컨트롤러
├── loadbalancer/       # 로드밸런서 구현
├── client/             # 스트레스 테스트 클라이언트
└── Application.java    # 메인 애플리케이션
```

## 빌드 및 실행

### 1. 프로젝트 빌드

```bash
./gradlew build
```

### 2. 서버 인스턴스 실행

터미널을 여러 개 열어서 각각 다른 포트로 서버를 실행합니다:

**서버 1 (포트 8081):**
```bash
./gradlew bootRun --args='--spring.profiles.active=server1 --SERVER_PORT=8081'
```

**서버 2 (포트 8082):**
```bash
./gradlew bootRun --args='--spring.profiles.active=server2 --SERVER_PORT=8082'
```

**서버 3 (포트 8083):**
```bash
./gradlew bootRun --args='--spring.profiles.active=server3 --SERVER_PORT=8083'
```

**로드밸런서 (포트 8080):**
```bash
./gradlew bootRun --args='--spring.profiles.active=lb --SERVER_PORT=8080'
```

### 3. Windows에서 실행

```powershell
# 서버 1
.\gradlew.bat bootRun --args='--spring.profiles.active=server1 --SERVER_PORT=8081'

# 서버 2
.\gradlew.bat bootRun --args='--spring.profiles.active=server2 --SERVER_PORT=8082'

# 서버 3
.\gradlew.bat bootRun --args='--spring.profiles.active=server3 --SERVER_PORT=8083'

# 로드밸런서
.\gradlew.bat bootRun --args='--spring.profiles.active=lb --SERVER_PORT=8080'
```

## API 사용법

### 1. 코스 조회

```bash
# 모든 코스 조회
curl http://localhost:8080/lb/courses

# 특정 코스 조회
curl http://localhost:8080/lb/courses/1
```

### 2. 수강신청

```bash
curl -X POST http://localhost:8080/lb/enroll \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "courseId": 1,
    "priority": 10
  }'
```

### 3. 큐 상태 확인

```bash
curl http://localhost:8080/lb/queue/status
```

### 4. 동시 접근 제한 수 설정

```bash
curl -X POST http://localhost:8080/lb/api/queue/max-concurrent \
  -H "Content-Type: application/json" \
  -d '{"max": 20}'
```

### 5. 스트레스 테스트 시작

```bash
curl -X POST "http://localhost:8080/stress/start?totalRequests=1000&concurrentRequests=50&courseId=1"
```

**파라미터:**
- `totalRequests`: 총 요청 수 (기본값: 1000)
- `concurrentRequests`: 동시 요청 수 (기본값: 50)
- `courseId`: 수강신청할 코스 ID (기본값: 1)

### 6. 스트레스 테스트 통계 조회

```bash
curl http://localhost:8080/stress/statistics
```

## 우선순위 큐 동작 방식

1. **요청 수신**: 클라이언트로부터 수강신청 요청이 들어옵니다.
2. **큐 추가**: 요청은 우선순위(priority) 값에 따라 우선순위 큐에 추가됩니다.
   - 낮은 priority 값 = 높은 우선순위
3. **접근 제어**: 현재 처리 중인 요청 수가 최대 동시 접근 수를 초과하면 대기합니다.
4. **순차 처리**: 우선순위에 따라 요청을 순차적으로 처리합니다.
5. **데이터베이스 락**: 비관적 락(PESSIMISTIC_WRITE)을 사용하여 동시성 문제를 방지합니다.

## 설정 파일

### application.yml
기본 설정 파일로 데이터베이스, 로드밸런서, 로깅 설정을 포함합니다.

### application-server1/2/3.yml
각 서버 인스턴스의 포트와 데이터베이스 설정을 정의합니다.

### application-lb.yml
로드밸런서의 포트와 서버 목록을 정의합니다.

## 환경 변수

- `SERVER_PORT`: 서버 포트 번호
- `LB_SERVERS`: 로드밸런서가 연결할 서버 목록 (쉼표로 구분)
- `TARGET_URL`: 스트레스 테스트 클라이언트의 타겟 URL

## 데이터베이스 접근

H2 콘솔에 접근하여 데이터를 확인할 수 있습니다:

- URL: `http://localhost:8081/h2-console` (서버1)
- JDBC URL: `jdbc:h2:mem:server1db`
- Username: `sa`
- Password: (비어있음)

## 주요 컴포넌트 설명

### PriorityEnrollmentQueue
- 우선순위 기반 요청 큐 관리
- 동시 접근 제한 수 제어
- 스레드 안전한 큐 구현

### EnrollmentService
- 수강신청 비즈니스 로직 처리
- 비동기 큐 처리
- 데이터베이스 트랜잭션 관리

### LoadBalancer
- 라운드로빈 방식의 요청 분산
- 서버 상태 모니터링
- 장애 처리

### StressTestClient
- 대량의 동시 요청 생성
- 성능 통계 수집
- 비동기 요청 처리

## 주의사항

1. 각 서버 인스턴스는 독립적인 H2 인메모리 데이터베이스를 사용합니다.
2. 실제 운영 환경에서는 공유 데이터베이스(MySQL, PostgreSQL 등)를 사용해야 합니다.
3. 로드밸런서는 단순한 라운드로빈 방식을 사용하며, 실제 운영 환경에서는 더 정교한 로드밸런싱 알고리즘을 고려해야 합니다.

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다.
