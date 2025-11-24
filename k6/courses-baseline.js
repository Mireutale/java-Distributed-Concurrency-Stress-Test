import http from "k6/http";
import { sleep } from "k6";

export const options = {
  vus: Number(__ENV.VUS || 100), // 가상 사용자 수
  duration: __ENV.DURATION || "30s", // 테스트 지속 시간
  thresholds: {
    // 성공 기준 임계치
    http_req_failed: ["rate<0.01"], // 실패율 < 1%
    http_req_duration: ["p(95)<300"], // p95 < 300ms
  },
};

export default function () {
  const base = __ENV.BASE_URL || "http://localhost:8080";
  http.get(`${base}/lb/courses`); // 코스 조회 요청
  sleep(0.1); // 0.1초 대기
}
