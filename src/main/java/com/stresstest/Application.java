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
    /**
     * 강좌 데이터 초기화 및 조회/저장을 담당하는 저장소
     */
    private final CourseRepository courseRepository;
    /**
     * 서버 풀 초기화와 요청 분산을 담당하는 로드밸런서
     */
    private final LoadBalancer loadBalancer;
    
    /**
     * 스프링 부트 애플리케이션 진입점
     *
     * @param args 실행 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    /**
     * 애플리케이션 기동 시 초기 서버 풀 구성과 예시 강좌 데이터를 생성
     */
    @Bean
    public CommandLineRunner initData() {
        return args -> {
            // 로드밸런서 초기화
            loadBalancer.initializeServers();
            
            // 초기 데이터 생성
            if (courseRepository.count() == 0) {
                Course course1 = new Course("Java Programming", 100);
                courseRepository.save(course1);
                
                Course course2 = new Course("Spring Boot", 50);
                courseRepository.save(course2);
                
                Course course3 = new Course("Database Design", 30);
                courseRepository.save(course3);
                
                log.info("Initial courses created");
            }
        };
    }
}

