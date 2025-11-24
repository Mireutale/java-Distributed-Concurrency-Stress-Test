package com.stresstest.service;

import com.stresstest.model.EnrollmentRequest;
import com.stresstest.queue.PriorityEnrollmentQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 수강 신청 처리 비즈니스 로직.
 * - 요청을 큐에 적재하고 비동기적으로 처리
 * - 실제 등록 처리는 별도 트랜잭션 빈(EnrollmentProcessor)에서 수행
 */
public class EnrollmentService {
    /** 우선순위 기반 신청 대기열 */
    private final PriorityEnrollmentQueue queue;
    /** 트랜잭션 경계를 소유한 처리기 */
    private final EnrollmentProcessor enrollmentProcessor;
    /** 백그라운드 처리 스레드 풀: 처리 동시성은 큐가 제어하므로 캐시드 사용 */
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    
    /**
     * 신청 요청을 큐에 적재하고 비동기 처리 실행을 트리거
     * 명령의 완료를 기다리지 않고 수행하도록 설정하였기 때문에, 과한 요청시 에러가 발생
     */
    public void requestEnrollment(EnrollmentRequest request) {
        log.info("Enrollment request received: userId={}, courseId={}, priority={}", 
                request.getUserId(), request.getCourseId(), request.getPriority());
        queue.addRequest(request);
        
        // 비동기로 처리 시작
        CompletableFuture.runAsync(this::processQueue, executorService);
    }
    
    /**
     * 큐에서 요청을 꺼내 처리하고, 슬롯 반환 후 후속 처리를 연쇄적으로 트리거
     */
    private void processQueue() {
        EnrollmentRequest request = queue.pollRequest();
        if (request == null) {
            return;
        }
        
        long startNs = System.nanoTime();
        boolean success = false;
        try {
            EnrollmentResult result = enrollmentProcessor.processEnrollment(request);
            success = result.isSuccess();
            log.info("Enrollment processed: userId={}, success={}, message={}", 
                    request.getUserId(), success, result.getMessage());
        } catch (Exception e) {
            log.error("Enrollment processing failed: userId={}, error={}", request.getUserId(), e.toString());
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            queue.onProcessed(durationMs, success);
            queue.releaseSlot();
            // 다음 요청 처리
            if (queue.getQueueSize() > 0) {
                CompletableFuture.runAsync(this::processQueue, executorService);
            }
        }
    }
    
    /**
     * 등록 처리 결과를 나타내는 불변 값 객체
     */
    public static class EnrollmentResult {
        private final boolean success;
        private final String message;
        
        /** 결과 객체 생성자 */
        public EnrollmentResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        /** 성공 여부 */
        public boolean isSuccess() {
            return success;
        }
        
        /** 결과 메시지 */
        public String getMessage() {
            return message;
        }
    }
}

