package com.stresstest.repository;

import com.stresstest.model.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
/**
 * 수강 등록 엔티티 접근 저장소
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    /**
     * 사용자 ID로 등록 내역을 조회
     */
    List<Enrollment> findByUserId(Long userId);
    /**
     * 사용자가 특정 강좌에 이미 등록했는지 여부를 반환
     */
    boolean existsByUserIdAndCourseId(Long userId, Long courseId);
}

