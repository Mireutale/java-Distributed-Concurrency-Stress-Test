import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    spike: {
      // 스파이크 시나리오
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 200), // 요청/초
      timeUnit: "1s", // 시간 단위
      duration: __ENV.DURATION || "60s", // 테스트 지속 시간
      preAllocatedVUs: Number(__ENV.VUS || 100), // 사전 할당 가상 사용자 수
      maxVUs: Number(__ENV.MAX_VUS || 500), // 최대 가상 사용자 수
    },
  },
  thresholds: {
    // 성공 기준 임계치
    http_req_failed: ["rate<0.01"], // 실패율 < 1%
    http_req_duration: ["p(95)<500"], // p95 < 500ms
  },
};

function randInt(n) {
  // 랜덤 정수 생성
  return Math.floor(Math.random() * n) + 1;
}

export default function () {
  // 수강 신청 요청 처리
  const base = __ENV.BASE_URL || "http://localhost:8080"; // 기본 URL
  const body = JSON.stringify({
    userId: randInt(100000), // 수강 신청 사용자 식별자
    courseId: Number(__ENV.COURSE_ID || 1), // 수강 신청 대상 강좌 식별자
    priority: Math.floor(Math.random() * 100), // 수강 신청 우선순위 (0-99)
  });
  const res = http.post(`${base}/lb/enroll`, body, {
    // 수강 신청 요청
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "enroll queued 200": (r) => r.status === 200 }); // 수강 신청 요청 결과 확인

  if (Math.random() < 0.05) {
    http.get(`${base}/lb/queue/status`); // 수강 신청 대기열 상태 조회
  }
  sleep(0.1); // 0.1초 대기
}
