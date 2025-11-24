package com.stresstest.controller;

import com.stresstest.model.Course;
import com.stresstest.model.Enrollment;
import com.stresstest.model.EnrollmentRequest;
import com.stresstest.queue.PriorityEnrollmentQueue;
import com.stresstest.repository.CourseRepository;
import com.stresstest.repository.EnrollmentRepository;
import com.stresstest.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
/**
 * 개별 서버 인스턴스의 비즈니스 API 컨트롤러
 * - 수강 신청 큐에 요청 적재
 * - 강좌/등록 내역 조회
 * - 큐 상태 조회 및 동시 처리 설정
 */
public class ServerController {
    /** 수강 신청 처리 로직을 제공하는 서비스 */
    private final EnrollmentService enrollmentService;
    /** 강좌 데이터 접근 저장소 */
    private final CourseRepository courseRepository;
    /** 수강 신청 데이터 접근 저장소 */
    private final EnrollmentRepository enrollmentRepository;
    /** 우선순위 기반 신청 대기열 */
    private final PriorityEnrollmentQueue queue;
    
    /** 현재 서버 인스턴스가 바인딩된 포트. 응답 정보에 포함됨 */
    @Value("${server.port:8080}")
    private int serverPort;
    
    /**
     * 수강 신청 요청을 큐에 올림
     */
    @PostMapping("/enroll")
    public ResponseEntity<Map<String, Object>> enroll(@RequestBody EnrollmentRequest request) {
        log.info("Enrollment request received on server port {}: {}", serverPort, request);
        enrollmentService.requestEnrollment(request);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "queued");
        response.put("message", "Request added to queue");
        response.put("serverPort", serverPort);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 모든 강좌 목록을 반환
     */
    @GetMapping("/courses")
    public ResponseEntity<List<Course>> getAllCourses() {
        return ResponseEntity.ok(courseRepository.findAll());
    }
    
    /**
     * ID로 강좌를 조회
     */
    @GetMapping("/courses/{id}")
    public ResponseEntity<Course> getCourse(@PathVariable Long id) {
        return courseRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 강좌를 생성
     */
    @PostMapping("/courses")
    public ResponseEntity<Course> createCourse(@RequestBody Course course) {
        return ResponseEntity.ok(courseRepository.save(course));
    }
    
    /**
     * 특정 사용자 ID의 수강 신청 내역을 조회
     */
    @GetMapping("/enrollments/user/{userId}")
    public ResponseEntity<List<Enrollment>> getUserEnrollments(@PathVariable Long userId) {
        return ResponseEntity.ok(enrollmentRepository.findByUserId(userId));
    }
    
    /**
     * 현재 큐 상태를 조회
     */
    @GetMapping("/queue/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", queue.getQueueSize());
        status.put("currentProcessing", queue.getCurrentProcessing());
        status.put("maxConcurrentRequests", queue.getMaxConcurrentRequests());
        status.put("emaLatencyMs", queue.getEmaLatencyMs());
        status.put("processedCount", queue.getProcessedCount());
        status.put("serverPort", serverPort);
        return ResponseEntity.ok(status);
    }
    
    /**
     * 동시 처리 최대값을 갱신
     */
    @PostMapping("/queue/max-concurrent")
    public ResponseEntity<Map<String, Object>> setMaxConcurrent(@RequestBody Map<String, Integer> config) {
        int max = config.getOrDefault("max", 10);
        queue.setMaxConcurrentRequests(max);
        
        Map<String, Object> response = new HashMap<>();
        response.put("maxConcurrentRequests", max);
        response.put("message", "Max concurrent requests updated");
        return ResponseEntity.ok(response);
    }
    
    /**
     * 단순 헬스 체크 엔드포인트.
     * 서버가 작동 중인지 아닌지 판단하는 엔드포인트
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("serverPort", serverPort);
        return ResponseEntity.ok(health);
    }
}

