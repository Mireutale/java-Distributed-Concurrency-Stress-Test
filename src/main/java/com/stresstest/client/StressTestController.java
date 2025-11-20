package com.stresstest.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/stress")
@RequiredArgsConstructor
public class StressTestController {
    private final StressTestClient stressTestClient;
    
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startStressTest(
            @RequestParam(defaultValue = "1000") int totalRequests,
            @RequestParam(defaultValue = "50") int concurrentRequests,
            @RequestParam(defaultValue = "1") long courseId) {
        
        log.info("Starting stress test: totalRequests={}, concurrentRequests={}, courseId={}", 
                totalRequests, concurrentRequests, courseId);
        
        // 비동기로 스트레스 테스트 실행
        CompletableFuture.runAsync(() -> {
            stressTestClient.startStressTest(totalRequests, concurrentRequests, courseId);
            stressTestClient.printStatistics();
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "started");
        response.put("totalRequests", totalRequests);
        response.put("concurrentRequests", concurrentRequests);
        response.put("courseId", courseId);
        response.put("message", "Stress test started in background");
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", stressTestClient.getRequestCount());
        stats.put("successCount", stressTestClient.getSuccessCount());
        stats.put("failureCount", stressTestClient.getFailureCount());
        stats.put("successRate", stressTestClient.getRequestCount() > 0 
                ? (stressTestClient.getSuccessCount() * 100.0 / stressTestClient.getRequestCount()) 
                : 0);
        return ResponseEntity.ok(stats);
    }
}

