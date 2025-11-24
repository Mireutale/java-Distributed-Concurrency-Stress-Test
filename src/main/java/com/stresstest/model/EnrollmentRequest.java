package com.stresstest.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 수강 신청 요청 DTO
 * - 큐에 적재되어 처리되는 입력 모델
 */
public class EnrollmentRequest {
    /** 신청 사용자 식별자 */
    private final Long userId;
    /** 신청 대상 강좌 식별자 */
    private final Long courseId;
    /** 우선순위 (값이 낮을수록 높은 우선순위) */
    private final Integer priority; // 우선순위 (낮을수록 높은 우선순위)
    
    @JsonCreator
    public EnrollmentRequest(
            @JsonProperty(value = "userId", required = true) Long userId,
            @JsonProperty(value = "courseId", required = true) Long courseId,
            @JsonProperty(value = "priority", required = true) Integer priority
    ) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.courseId = Objects.requireNonNull(courseId, "courseId must not be null");
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
    }
    
    public Long getUserId() {
        return userId;
    }
    
    public Long getCourseId() {
        return courseId;
    }
    
    public Integer getPriority() {
        return priority;
    }
}

