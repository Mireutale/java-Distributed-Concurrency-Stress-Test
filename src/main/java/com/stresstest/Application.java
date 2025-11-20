package com.stresstest;

import com.stresstest.loadbalancer.LoadBalancer;
import com.stresstest.model.Course;
import com.stresstest.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class Application {
    private final CourseRepository courseRepository;
    private final LoadBalancer loadBalancer;
    
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Bean
    public CommandLineRunner initData() {
        return args -> {
            // 로드밸런서 초기화
            loadBalancer.initializeServers();
            
            // 초기 데이터 생성
            if (courseRepository.count() == 0) {
                Course course1 = new Course();
                course1.setName("Java Programming");
                course1.setCapacity(100);
                courseRepository.save(course1);
                
                Course course2 = new Course();
                course2.setName("Spring Boot");
                course2.setCapacity(50);
                courseRepository.save(course2);
                
                Course course3 = new Course();
                course3.setName("Database Design");
                course3.setCapacity(30);
                courseRepository.save(course3);
                
                log.info("Initial courses created");
            }
        };
    }
}

