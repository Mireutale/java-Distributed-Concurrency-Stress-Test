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
public class ServerController {
    private final EnrollmentService enrollmentService;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final PriorityEnrollmentQueue queue;
    
    @Value("${server.port:8080}")
    private int serverPort;
    
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
    
    @GetMapping("/courses")
    public ResponseEntity<List<Course>> getAllCourses() {
        return ResponseEntity.ok(courseRepository.findAll());
    }
    
    @GetMapping("/courses/{id}")
    public ResponseEntity<Course> getCourse(@PathVariable Long id) {
        return courseRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping("/courses")
    public ResponseEntity<Course> createCourse(@RequestBody Course course) {
        return ResponseEntity.ok(courseRepository.save(course));
    }
    
    @GetMapping("/enrollments/user/{userId}")
    public ResponseEntity<List<Enrollment>> getUserEnrollments(@PathVariable Long userId) {
        return ResponseEntity.ok(enrollmentRepository.findByUserId(userId));
    }
    
    @GetMapping("/queue/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", queue.getQueueSize());
        status.put("currentProcessing", queue.getCurrentProcessing());
        status.put("maxConcurrentRequests", queue.getMaxConcurrentRequests());
        status.put("serverPort", serverPort);
        return ResponseEntity.ok(status);
    }
    
    @PostMapping("/queue/max-concurrent")
    public ResponseEntity<Map<String, Object>> setMaxConcurrent(@RequestBody Map<String, Integer> config) {
        int max = config.getOrDefault("max", 10);
        queue.setMaxConcurrentRequests(max);
        
        Map<String, Object> response = new HashMap<>();
        response.put("maxConcurrentRequests", max);
        response.put("message", "Max concurrent requests updated");
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("serverPort", serverPort);
        return ResponseEntity.ok(health);
    }
}

