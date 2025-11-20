package com.stresstest.client;

import com.stresstest.model.EnrollmentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class StressTestClient {
    private final WebClient webClient;
    private final Random random = new Random();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    
    @Value("${stress.client.target-url:http://localhost:8080}")
    private String targetUrl;
    
    public StressTestClient() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
    
    public void startStressTest(int totalRequests, int concurrentRequests, long courseId) {
        log.info("Starting stress test: totalRequests={}, concurrentRequests={}, courseId={}, targetUrl={}", 
                totalRequests, concurrentRequests, courseId, targetUrl);
        
        requestCount.set(0);
        successCount.set(0);
        failureCount.set(0);
        
        Flux.range(1, totalRequests)
                .flatMap(i -> {
                    EnrollmentRequest request = new EnrollmentRequest();
                    request.setUserId((long) (random.nextInt(1000) + 1));
                    request.setCourseId(courseId);
                    request.setPriority(random.nextInt(100)); // 0-99 우선순위
                    
                    return sendRequest(request)
                            .delayElement(Duration.ofMillis(10)); // 약간의 지연
                }, concurrentRequests) // 동시 요청 수
                .doOnComplete(() -> {
                    log.info("Stress test completed. Total: {}, Success: {}, Failed: {}", 
                            requestCount.get(), successCount.get(), failureCount.get());
                })
                .blockLast();
    }
    
    private Mono<Void> sendRequest(EnrollmentRequest request) {
        return webClient.post()
                .uri(targetUrl + "/lb/enroll")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Object.class)
                .doOnSuccess(response -> {
                    requestCount.incrementAndGet();
                    successCount.incrementAndGet();
                    if (requestCount.get() % 100 == 0) {
                        log.info("Progress: {} requests sent, {} successful, {} failed", 
                                requestCount.get(), successCount.get(), failureCount.get());
                    }
                })
                .doOnError(error -> {
                    requestCount.incrementAndGet();
                    failureCount.incrementAndGet();
                    log.error("Request failed: {}", error.getMessage());
                })
                .then();
    }
    
    public void printStatistics() {
        log.info("=== Stress Test Statistics ===");
        log.info("Total Requests: {}", requestCount.get());
        log.info("Successful: {}", successCount.get());
        log.info("Failed: {}", failureCount.get());
        log.info("Success Rate: {}%", 
                requestCount.get() > 0 ? (successCount.get() * 100.0 / requestCount.get()) : 0);
    }
    
    public int getRequestCount() {
        return requestCount.get();
    }
    
    public int getSuccessCount() {
        return successCount.get();
    }
    
    public int getFailureCount() {
        return failureCount.get();
    }
}

