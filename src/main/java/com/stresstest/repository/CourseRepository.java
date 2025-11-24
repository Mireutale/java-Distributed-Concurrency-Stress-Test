package com.stresstest.repository;

import com.stresstest.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
/**
 * 강좌 엔티티 접근 저장소
 * - 동시성 제어를 위한 비관적 락 조회 메서드를 제공
 */
public interface CourseRepository extends JpaRepository<Course, Long> {
    /**
     * 등록 처리 시 경쟁 상태를 피하기 위해 해당 강좌 행을 PESSIMISTIC_WRITE 락으로 조회
     * PESSIMISTIC_WRITE - 비관적 락, 데이터를 읽어오기 시작하는 시점부터, 해당 데이터에 대한 베타적인 락을 걸어
     * 현재 트랜잭션이 완료될 때까지, 다른 트랜잭션의 접근을 명시적으로 차단
     * 동시 업데이트 충돌 / 데이터 일관성이 중요한 환경에서 주로 사용
     * @param id 강좌 ID
     * @return 강좌 엔티티(Optional)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    Optional<Course> findByIdWithLock(Long id);
}

