package com.stresstest.loadbalancer;

import com.stresstest.model.EnrollmentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/lb")
@RequiredArgsConstructor
public class LoadBalancerController {
    private final LoadBalancer loadBalancer;
    
    @PostMapping("/enroll")
    public Mono<ResponseEntity<Object>> enroll(@RequestBody EnrollmentRequest request) {
        log.info("Load balancer received enrollment request: {}", request);
        return loadBalancer.forwardRequest("/api/enroll", request);
    }
    
    @GetMapping("/courses")
    public Mono<ResponseEntity<Object>> getAllCourses() {
        return loadBalancer.forwardGetRequest("/api/courses");
    }
    
    @GetMapping("/courses/{id}")
    public Mono<ResponseEntity<Object>> getCourse(@PathVariable Long id) {
        return loadBalancer.forwardGetRequest("/api/courses/" + id);
    }
    
    @GetMapping("/queue/status")
    public Mono<ResponseEntity<Object>> getQueueStatus() {
        return loadBalancer.forwardGetRequest("/api/queue/status");
    }
    
    @GetMapping("/servers")
    public ResponseEntity<Map<String, Object>> getServers() {
        Map<String, Object> response = new HashMap<>();
        response.put("servers", loadBalancer.getServerUrls());
        return ResponseEntity.ok(response);
    }
}

