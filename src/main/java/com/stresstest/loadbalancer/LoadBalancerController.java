package com.stresstest.loadbalancer;

import com.stresstest.model.EnrollmentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/lb")
@RequiredArgsConstructor
/**
 * 로드밸런서 진입점 컨트롤러
 * - 클라이언트 요청을 받아 백엔드 서버로 포워딩
 */
public class LoadBalancerController {
    /** 라운드 로빈 기반 요청 포워딩 로직 */
    private final LoadBalancer loadBalancer;
    
    /**
     * 수강 신청 요청을 서버로 포워딩
     */
    @PostMapping("/enroll")
    public Mono<ResponseEntity<byte[]>> enroll(@RequestBody EnrollmentRequest request) {
        log.info("Load balancer received enrollment request: {}", request);
        return loadBalancer.forwardRequest("/api/enroll", request);
    }
    
    /** 강좌 목록 조회 요청을 포워딩 */
    @GetMapping("/courses")
    public Mono<ResponseEntity<byte[]>> getAllCourses() {
        return loadBalancer.forwardGetRequest("/api/courses");
    }
    
    /** 특정 강좌 조회 요청을 포워딩 */
    @GetMapping("/courses/{id}")
    public Mono<ResponseEntity<byte[]>> getCourse(@PathVariable Long id) {
        return loadBalancer.forwardGetRequest("/api/courses/" + id);
    }
    
    /** 큐 상태 조회 요청을 포워딩 */
    @GetMapping("/queue/status")
    public Mono<ResponseEntity<byte[]>> getQueueStatus() {
        return loadBalancer.forwardGetRequest("/api/queue/status");
    }
    
    /**
     * 현재 로드밸런서가 관리하는 서버 목록을 반환한다.
     */
    @GetMapping("/servers")
    public ResponseEntity<Map<String, Object>> getServers() {
        Map<String, Object> response = new HashMap<>();
        response.put("servers", loadBalancer.getServerUrls());
        return ResponseEntity.ok(response);
    }
}

