package com.stresstest.queue;

import com.stresstest.model.EnrollmentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
/**
 * 우선순위 기반의 수강 신청 대기열.
 * - priority 값이 낮을수록 높은 우선순위로 처리된다.
 * - 동시 처리 상한을 두어 처리량과 지연을 균형화한다.
 */
public class PriorityEnrollmentQueue {
    // 우선순위 큐 (priority가 낮을수록 높은 우선순위)
    private final PriorityBlockingQueue<EnrollmentRequest> queue = new PriorityBlockingQueue<>(
            10000,
            Comparator.comparingInt(EnrollmentRequest::getPriority)
    );
    
    /** 동시에 처리할 수 있는 최대 요청 수. */
    private final AtomicInteger maxConcurrentRequests = new AtomicInteger(100); // 동시 접근 제한 수
    /** 현재 처리 중인 요청 수. */
    private final AtomicInteger currentProcessing = new AtomicInteger(0);
    /** 지연 EMA(ms) */
    private final AtomicReference<Double> emaLatencyMs = new AtomicReference<>(0.0);
    /** 성공 카운트 */
    private final AtomicInteger successesSinceIncrease = new AtomicInteger(0);
    /** 실패 카운트 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** 처리된 요청 수 */
    private final AtomicLong processedCount = new AtomicLong(0);
    /** 최소 동시 처리 수 */
    @Value("${queue.adaptive.minConcurrent:20}")
    private int minConcurrent;
    /** 최대 동시 처리 수 */
    @Value("${queue.adaptive.maxConcurrent:200}")
    private int maxConcurrent;
    /** 목표 지연 시간(ms) */
    @Value("${queue.adaptive.targetLatencyMs:250}")
    private long targetLatencyMs;
    /** 감소 비율 */
    @Value("${queue.adaptive.decreaseRatio:0.7}")
    private double decreaseRatio;
    /** 증가 조건 성공 횟수 */
    @Value("${queue.adaptive.successesForIncrease:50}")
    private int successesForIncrease;
    /** 백로그 증가 임계값 */
    @Value("${queue.adaptive.backlogBoostThreshold:500}")
    private int backlogBoostThreshold;

    /** 초기화 */
    @PostConstruct
    void init() {
        int init = Math.max(1, minConcurrent);
        maxConcurrentRequests.set(init);
        log.info("PriorityEnrollmentQueue adaptive init: min={}, max={}, targetLatencyMs={}ms",
                minConcurrent, maxConcurrent, targetLatencyMs);
    }
    
    /**
     * 신청 요청을 큐에 추가한다.
     */
    public void addRequest(EnrollmentRequest request) {
        queue.offer(request);
        log.info("Request added to queue: userId={}, courseId={}, priority={}, queueSize={}", 
                request.getUserId(), request.getCourseId(), request.getPriority(), queue.size());
    }
    
    /**
     * 동시 처리 한도 내에서 요청 하나를 꺼내 처리 슬롯을 점유한다.
     *
     * @return 처리할 요청 또는 null(한도 초과/빈 큐)
     */
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
    
    /**
     * 처리 완료 후 슬롯을 반환한다.
     */
    public void releaseSlot() {
        int current = currentProcessing.decrementAndGet();
        log.debug("Slot released, currentProcessing={}", current);
    }
    
    /**
     * 처리 결과를 피드백하여 동시 처리 한도를 적응적으로 조절.
     * - 빠르면 점증(+1)
     * - 느리거나 실패가 이어지면 비율 축소
     */
    public void onProcessed(long durationMs, boolean success) {
        // 처리된 요청 수 증가
        processedCount.incrementAndGet();
        // 지연 EMA(ms) 업데이트
        emaLatencyMs.updateAndGet(prev -> {
            double alpha = 0.2; // 지연 EMA(ms) 가중치
            double base; // 지연 EMA(ms) 기준값
            if (prev == null) {
                base = durationMs; // 첫 번째 값은 현재 지연 시간으로 초기화
            } else {
                base = prev; // 이후 값은 이전 지연 EMA(ms) 값으로 초기화
            }
            return base + alpha * (durationMs - base); // 새롭게 지연 EMA(ms) 계산
        });
        // 성공 처리
        if (success) {
            consecutiveFailures.set(0); // 실패 카운트 초기화
            boolean fastEnough = durationMs <= targetLatencyMs; // 목표 지연 시간 이내인지 확인
            int succ = successesSinceIncrease.incrementAndGet(); // 성공 카운트 증가
            boolean largeBacklog = queue.size() >= backlogBoostThreshold; // 백로그 증가 임계값 이상인지 확인
            int divisor;
            if (largeBacklog) {
                divisor = 2; // 백로그 증가 임계값 이상인 경우 2로 나눔
            } else {
                divisor = 1; // 백로그 증가 임계값 이하인 경우 1로 나눔
            }
            int thresholdForIncrease = Math.max(5, successesForIncrease / divisor); // 증가 조건 임계값 계산
            if ((fastEnough && succ >= thresholdForIncrease) || (largeBacklog && succ >= 5)) {
                successesSinceIncrease.set(0);
                int next = Math.min(maxConcurrent, maxConcurrentRequests.incrementAndGet()); // 최대 동시 처리 수 증가
                maxConcurrentRequests.set(next);
                log.debug("Adaptive increase: durationMs={}ms, backlog={}, newLimit={}", durationMs, queue.size(), next);
            }
        } else {
            consecutiveFailures.incrementAndGet();
            successesSinceIncrease.set(0);
            shrink();
        }
        // 매우 느린 경우에도 축소
        if (durationMs > targetLatencyMs * 2) {
            successesSinceIncrease.set(0);
            shrink();
        }
    }
    
    /** 축소 */
    private void shrink() {
        int cur = maxConcurrentRequests.get(); // 현재 동시 처리 수
        double effectiveDecreaseRatio;
        if (decreaseRatio <= 0 || decreaseRatio >= 1) {
            effectiveDecreaseRatio = 0.7; // 감소 비율이 0 이하 또는 1 이상인 경우 0.7로 설정
        } else {
            effectiveDecreaseRatio = decreaseRatio; // 감소 비율이 0 이상 1 이하인 경우 감소 비율로 설정
        }
        int next = Math.max(minConcurrent, (int)Math.floor(cur * effectiveDecreaseRatio)); // 최소 동시 처리 수와 현재 동시 처리 수의 최대값을 반환
        if (next < cur) {
            maxConcurrentRequests.set(next); // 동시 처리 수 축소
            log.debug("Adaptive decrease: newLimit={}", next);
        }
    }
    
    /** 현재 큐 크기를 반환한다. */
    public int getQueueSize() {
        return queue.size();
    }
    
    /** 현재 처리 중인 요청 수를 반환한다. */
    public int getCurrentProcessing() {
        return currentProcessing.get();
    }
    
    /** 동시 처리 최대값을 설정한다. */
    public void setMaxConcurrentRequests(int max) {
        maxConcurrentRequests.set(max);
        log.info("Max concurrent requests updated to: {}", max);
    }
    
    /** 동시 처리 최대값을 조회한다. */
    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests.get();
    }
    
    /** 지표 확인용, 지연시간의 지수평균을 반환한다. */
    public double getEmaLatencyMs() {
        Double v = emaLatencyMs.get();
        if (v == null) {
            return 0.0;
        }
        return v;
    }
    public long getProcessedCount() {
        return processedCount.get();
    }
}

