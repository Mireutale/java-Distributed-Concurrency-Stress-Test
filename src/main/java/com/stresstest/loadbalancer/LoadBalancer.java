package com.stresstest.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class LoadBalancer {
    private final List<String> serverUrls = new ArrayList<>();
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final WebClient webClient;
    
    @Value("${loadbalancer.servers:http://localhost:8081,http://localhost:8082,http://localhost:8083}")
    private String serversConfig;
    
    public LoadBalancer() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
    
    public void initializeServers() {
        String[] servers = serversConfig.split(",");
        for (String server : servers) {
            serverUrls.add(server.trim());
        }
        log.info("Load balancer initialized with {} servers: {}", serverUrls.size(), serverUrls);
    }
    
    public String getNextServer() {
        if (serverUrls.isEmpty()) {
            initializeServers();
        }
        int index = currentIndex.getAndIncrement() % serverUrls.size();
        return serverUrls.get(index);
    }
    
    public Mono<ResponseEntity<Object>> forwardRequest(String path, Object body) {
        String serverUrl = getNextServer();
        String fullUrl = serverUrl + path;
        
        log.info("Forwarding request to {}: {}", serverUrl, path);
        
        return webClient.post()
                .uri(fullUrl)
                .bodyValue(body)
                .retrieve()
                .toEntity(Object.class)
                .doOnSuccess(response -> log.info("Response from {}: {}", serverUrl, response.getStatusCode()))
                .doOnError(error -> log.error("Error forwarding to {}: {}", serverUrl, error.getMessage()))
                .onErrorResume(error -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Server unavailable: " + error.getMessage()))));
    }
    
    public Mono<ResponseEntity<Object>> forwardGetRequest(String path) {
        String serverUrl = getNextServer();
        String fullUrl = serverUrl + path;
        
        log.info("Forwarding GET request to {}: {}", serverUrl, path);
        
        return webClient.get()
                .uri(fullUrl)
                .retrieve()
                .toEntity(Object.class)
                .doOnSuccess(response -> log.info("Response from {}: {}", serverUrl, response.getStatusCode()))
                .doOnError(error -> log.error("Error forwarding to {}: {}", serverUrl, error.getMessage()))
                .onErrorResume(error -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Server unavailable: " + error.getMessage()))));
    }
    
    public List<String> getServerUrls() {
        if (serverUrls.isEmpty()) {
            initializeServers();
        }
        return new ArrayList<>(serverUrls);
    }
}

