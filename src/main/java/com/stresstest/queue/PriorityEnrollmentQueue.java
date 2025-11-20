package com.stresstest.queue;

import com.stresstest.model.EnrollmentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class PriorityEnrollmentQueue {
    // 우선순위 큐 (priority가 낮을수록 높은 우선순위)
    private final PriorityBlockingQueue<EnrollmentRequest> queue = new PriorityBlockingQueue<>(
            1000,
            Comparator.comparingInt(EnrollmentRequest::getPriority)
    );
    
    private final AtomicInteger maxConcurrentRequests = new AtomicInteger(10); // 동시 접근 제한 수
    private final AtomicInteger currentProcessing = new AtomicInteger(0);
    
    public void addRequest(EnrollmentRequest request) {
        queue.offer(request);
        log.info("Request added to queue: userId={}, courseId={}, priority={}, queueSize={}", 
                request.getUserId(), request.getCourseId(), request.getPriority(), queue.size());
    }
    
    public EnrollmentRequest pollRequest() {
        if (currentProcessing.get() >= maxConcurrentRequests.get()) {
            log.debug("Max concurrent requests reached: {}/{}", 
                    currentProcessing.get(), maxConcurrentRequests.get());
            return null;
        }
        
        EnrollmentRequest request = queue.poll();
        if (request != null) {
            currentProcessing.incrementAndGet();
            log.debug("Request polled from queue: userId={}, currentProcessing={}", 
                    request.getUserId(), currentProcessing.get());
        }
        return request;
    }
    
    public void releaseSlot() {
        int current = currentProcessing.decrementAndGet();
        log.debug("Slot released, currentProcessing={}", current);
    }
    
    public int getQueueSize() {
        return queue.size();
    }
    
    public int getCurrentProcessing() {
        return currentProcessing.get();
    }
    
    public void setMaxConcurrentRequests(int max) {
        maxConcurrentRequests.set(max);
        log.info("Max concurrent requests updated to: {}", max);
    }
    
    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests.get();
    }
}

