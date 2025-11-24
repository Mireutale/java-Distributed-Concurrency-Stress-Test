package com.stresstest.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "enrollments")
/**
 * 수강 등록 엔티티
 * - 어느 사용자가 어떤 강좌에 언제 등록되었는지와 상태를 기록한다.
 */
public class Enrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /** 등록을 수행한 사용자 식별자 */
    @Column(nullable = false)
    private Long userId;
    
    /** 등록 대상 강좌 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;
    
    /** 등록 완료 시각 */
    @Column(nullable = false)
    private LocalDateTime enrolledAt;
    
    /** 등록 처리 상태 */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EnrollmentStatus status;
    
    /**
     * JPA를 위한 기본 생성자
     */
    protected Enrollment() {
    }
    
    /**
     * 도메인 생성을 위한 생성자
     */
    public Enrollment(Long userId, Course course, LocalDateTime enrolledAt, EnrollmentStatus status) {
        this.userId = userId;
        this.course = course;
        this.enrolledAt = enrolledAt;
        this.status = status;
    }
    
    public Long getId() {
        return id;
    }
    
    public Long getUserId() {
        return userId;
    }
    
    public Course getCourse() {
        return course;
    }
    
    public LocalDateTime getEnrolledAt() {
        return enrolledAt;
    }
    
    public EnrollmentStatus getStatus() {
        return status;
    }
    
    /**
     * 성공으로 상태를 전이
     */
    public void markSuccess(LocalDateTime at) {
        this.enrolledAt = at;
        this.status = EnrollmentStatus.SUCCESS;
    }
    
    /**
     * 실패로 상태를 전이
     */
    public void markFailed() {
        this.status = EnrollmentStatus.FAILED;
    }
    
    /**
     * 등록 상태
     * PENDING: 대기중, SUCCESS: 성공, FAILED: 실패
     */
    public enum EnrollmentStatus {
        PENDING, SUCCESS, FAILED
    }
}

