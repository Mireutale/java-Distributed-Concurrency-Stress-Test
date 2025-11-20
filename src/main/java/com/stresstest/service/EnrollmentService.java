package com.stresstest.service;

import com.stresstest.model.Course;
import com.stresstest.model.Enrollment;
import com.stresstest.model.EnrollmentRequest;
import com.stresstest.queue.PriorityEnrollmentQueue;
import com.stresstest.repository.CourseRepository;
import com.stresstest.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentService {
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final PriorityEnrollmentQueue queue;
    private final ExecutorService executorService = Executors.newFixedThreadPool(20);
    
    public void requestEnrollment(EnrollmentRequest request) {
        log.info("Enrollment request received: userId={}, courseId={}, priority={}", 
                request.getUserId(), request.getCourseId(), request.getPriority());
        queue.addRequest(request);
        
        // 비동기로 처리 시작
        CompletableFuture.runAsync(() -> processQueue(), executorService);
    }
    
    @Transactional
    public EnrollmentResult processEnrollment(EnrollmentRequest request) {
        try {
            // 이미 등록되어 있는지 확인
            if (enrollmentRepository.existsByUserIdAndCourseId(request.getUserId(), request.getCourseId())) {
                log.warn("User {} already enrolled in course {}", request.getUserId(), request.getCourseId());
                return new EnrollmentResult(false, "Already enrolled");
            }
            
            // 비관적 락으로 코스 조회
            Course course = courseRepository.findByIdWithLock(request.getCourseId())
                    .orElseThrow(() -> new RuntimeException("Course not found: " + request.getCourseId()));
            
            if (course.isFull()) {
                log.warn("Course {} is full", request.getCourseId());
                return new EnrollmentResult(false, "Course is full");
            }
            
            // 등록 처리
            boolean enrolled = course.enroll();
            if (!enrolled) {
                return new EnrollmentResult(false, "Failed to enroll");
            }
            
            courseRepository.save(course);
            
            // 등록 정보 저장
            Enrollment enrollment = new Enrollment();
            enrollment.setUserId(request.getUserId());
            enrollment.setCourse(course);
            enrollment.setEnrolledAt(LocalDateTime.now());
            enrollment.setStatus(Enrollment.EnrollmentStatus.SUCCESS);
            enrollmentRepository.save(enrollment);
            
            log.info("Enrollment successful: userId={}, courseId={}", 
                    request.getUserId(), request.getCourseId());
            return new EnrollmentResult(true, "Enrollment successful");
            
        } catch (Exception e) {
            log.error("Error processing enrollment: userId={}, courseId={}", 
                    request.getUserId(), request.getCourseId(), e);
            return new EnrollmentResult(false, "Error: " + e.getMessage());
        }
    }
    
    private void processQueue() {
        EnrollmentRequest request = queue.pollRequest();
        if (request == null) {
            return;
        }
        
        try {
            EnrollmentResult result = processEnrollment(request);
            log.info("Enrollment processed: userId={}, success={}, message={}", 
                    request.getUserId(), result.isSuccess(), result.getMessage());
        } finally {
            queue.releaseSlot();
            // 다음 요청 처리
            if (queue.getQueueSize() > 0) {
                CompletableFuture.runAsync(() -> processQueue(), executorService);
            }
        }
    }
    
    public static class EnrollmentResult {
        private final boolean success;
        private final String message;
        
        public EnrollmentResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
    }
}

