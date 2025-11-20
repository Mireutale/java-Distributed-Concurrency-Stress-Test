package com.stresstest.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "courses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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
    
    public boolean isFull() {
        return currentEnrollment >= capacity;
    }
    
    public boolean enroll() {
        if (isFull()) {
            return false;
        }
        currentEnrollment++;
        return true;
    }
    
    public void cancelEnrollment() {
        if (currentEnrollment > 0) {
            currentEnrollment--;
        }
    }
}

