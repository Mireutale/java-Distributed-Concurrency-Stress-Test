package com.stresstest.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "courses")
/**
 * 강좌 도메인 엔티티
 * - 수용 인원(capacity)과 현재 등록 인원(currentEnrollment)을 관리
 */
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false)
    private Integer capacity;
    
    @Column(nullable = false)
    private Integer currentEnrollment = 0;
    
    /**
     * JPA를 위한 기본 생성자
     */
    protected Course() {
    }
    
    /**
     * JSON 바인딩 및 도메인 생성을 위한 생성자
     */
    @JsonCreator
    public Course(
            @JsonProperty("name") String name,
            @JsonProperty("capacity") Integer capacity
    ) {
        this.name = name;
        this.capacity = capacity;
        this.currentEnrollment = 0;
    }
    
    public Long getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public Integer getCapacity() {
        return capacity;
    }
    
    public Integer getCurrentEnrollment() {
        return currentEnrollment;
    }
    
    /**
     * 강좌가 정원이 찼는지 여부를 반환
     */
    public boolean isFull() {
        return currentEnrollment >= capacity;
    }
    
    /**
     * 정원에 여유가 있으면 등록 인원을 1 증가시키고 true를 반환
     */
    public boolean enroll() {
        if (isFull()) {
            return false;
        }
        currentEnrollment++;
        return true;
    }
    
    /**
     * 등록 취소 시 현재 인원을 1 감소시킨다(0 미만으로 내려가지 않음)
     */
    public void cancelEnrollment() {
        if (currentEnrollment > 0) {
            currentEnrollment--;
        }
    }
}

