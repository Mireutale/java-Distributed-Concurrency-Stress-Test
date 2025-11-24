package com.stresstest.service;

import com.stresstest.model.Course;
import com.stresstest.model.Enrollment;
import com.stresstest.model.EnrollmentRequest;
import com.stresstest.repository.CourseRepository;
import com.stresstest.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 트랜잭션 경계를 소유하고 비관적 락을 통해 등록 처리를 수행하는 컴포넌트
 * - 별도 빈으로 분리하여 프록시를 통해 @Transactional이 적용되도록 함
 */
public class EnrollmentProcessor {
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * 단일 신청 요청을 트랜잭션 내에서 처리
     * - 중복 신청 확인
     * - 강좌 행을 비관적 락으로 조회
     * - 정원 확인 및 등록 반영
     * - 등록 레코드 저장
     */
    @Transactional
    /** 
     * ACID원칙을 보장
     * 중복 신청, 조회, 등록, 저장 모두 하나라도 실패하는 경우 전부 롤백해야 하는 문제가 발생할 수 있기 때문에
     * 하나의 Transactional 내에서 모두 처리하도록 함
     * */
    public EnrollmentService.EnrollmentResult processEnrollment(EnrollmentRequest request) {
        try {
            // 이미 등록되어 있는지 확인 - 중복 신청 방지
            if (enrollmentRepository.existsByUserIdAndCourseId(request.getUserId(), request.getCourseId())) {
                log.warn("User {} already enrolled in course {}", request.getUserId(), request.getCourseId());
                return new EnrollmentService.EnrollmentResult(false, "Already enrolled");
            }

            // 비관적 락으로 코스 조회 - 동시성 제어
            Course course = courseRepository.findByIdWithLock(request.getCourseId())
                    .orElseThrow(() -> new RuntimeException("Course not found: " + request.getCourseId()));

            if (course.isFull()) {
                log.warn("Course {} is full", request.getCourseId());
                return new EnrollmentService.EnrollmentResult(false, "Course is full");
            }

            // 등록 처리 - 정원 확인 및 등록 반영
            boolean enrolled = course.enroll();
            if (!enrolled) {
                return new EnrollmentService.EnrollmentResult(false, "Failed to enroll");
            }

            courseRepository.save(course);

            // 등록 정보 저장 - 등록 레코드 저장
            Enrollment enrollment = new Enrollment(
                    request.getUserId(),
                    course,
                    LocalDateTime.now(),
                    Enrollment.EnrollmentStatus.SUCCESS
            );
            enrollmentRepository.save(enrollment);

            log.info("Enrollment successful: userId={}, courseId={}",
                    request.getUserId(), request.getCourseId());
            return new EnrollmentService.EnrollmentResult(true, "Enrollment successful");

        } catch (Exception e) {
            log.error("Error processing enrollment: userId={}, courseId={}",
                    request.getUserId(), request.getCourseId(), e);
            return new EnrollmentService.EnrollmentResult(false, "Error: " + e.getMessage());
        }
    }
}

