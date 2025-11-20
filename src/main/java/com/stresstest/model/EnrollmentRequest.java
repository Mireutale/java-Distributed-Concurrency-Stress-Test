package com.stresstest.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EnrollmentRequest {
    private Long userId;
    private Long courseId;
    private Integer priority; // 우선순위 (낮을수록 높은 우선순위)
}

