package com.stresstest.loadbalancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
/**
 * 간단한 라운드 로빈 전략의 애플리케이션 레벨 로드밸런서
 * - 서버 목록 초기화/보관
 * - POST/GET 요청을 다음 서버로 포워딩
 */
public class LoadBalancer {
    /** 대상 서버의 베이스 URL 목록 */
    private final List<String> serverUrls = new ArrayList<>();
    /** 다음 대상 서버를 선택하기 위한 인덱스 */
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    /** 비동기 HTTP 요청 전송 클라이언트 */ 
    private final WebClient webClient;
    
    /** application.yml 에서 주입되는 서버 목록 설정값 */
    @Value("${loadbalancer.servers:http://localhost:8081,http://localhost:8082,http://localhost:8083}")
    private String serversConfig;
    
    /** 생성자에서 WebClient 기본 설정으로 초기화 
     * - 메모리 사용량 제한 10MB
     * - 비동기 HTTP 요청 전송 클라이언트 생성
    */
    public LoadBalancer() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
    
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "trailer"
    );
    
    private static boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
    }
    
    private static HttpHeaders sanitizeHeaders(HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!isHopByHopHeader(name)) {
                target.put(name, values);
            }
        });
        target.remove(HttpHeaders.CONTENT_LENGTH);
        target.remove(HttpHeaders.TRANSFER_ENCODING);
        return target;
    }
    
    /**
     * 설정값을 읽어 서버 URL 목록을 초기화
     */
    public void initializeServers() {
        String[] servers = serversConfig.split(",");
        for (String server : servers) {
            serverUrls.add(server.trim());
        }
        log.info("Load balancer initialized with {} servers: {}", serverUrls.size(), serverUrls);
    }
    
    /**
     * 라운드 로빈으로 다음 대상 서버 URL을 반환
     */
    public String getNextServer() {
        if (serverUrls.isEmpty()) {
            initializeServers();
        }
        int index = currentIndex.getAndIncrement() % serverUrls.size();
        return serverUrls.get(index);
    }
    
    /**
     * POST 요청을 다음 서버의 지정 경로로 포워딩
     *
     * @param path 서버 측 경로
     * @param body 요청 바디
     * @return 서버 응답 Mono
     */
    public Mono<ResponseEntity<byte[]>> forwardRequest(String path, Object body) {
        String serverUrl = getNextServer();
        String fullUrl = serverUrl + path;
        
        log.info("Forwarding request to {}: {}", serverUrl, path);
        return webClient.post()
                .uri(fullUrl)
                .bodyValue(body)
                .exchangeToMono(clientResponse -> clientResponse
                        .bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(responseBody -> {
                            HttpHeaders headers = sanitizeHeaders(clientResponse.headers().asHttpHeaders());
                            HttpStatus status = HttpStatus.valueOf(clientResponse.statusCode().value());
                            return new ResponseEntity<>(responseBody, headers, status);
                        }))
                .doOnSuccess(response -> log.info("Response from {}: {}", serverUrl, response.getStatusCode()))
                .doOnError(error -> log.error("Error forwarding to {}: {}", serverUrl, error.getMessage()))
                .onErrorResume(error -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
                    String msg = "{\"error\":\"Server unavailable: " + error.getMessage().replace("\"","\\\"") + "\"}";
                    return Mono.just(new ResponseEntity<>(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, HttpStatus.SERVICE_UNAVAILABLE));
                });
    }
    
    /**
     * GET 요청을 다음 서버의 지정 경로로 포워딩
     * @param path 서버 측 경로
     * @return 서버 응답 Mono
     */
    public Mono<ResponseEntity<byte[]>> forwardGetRequest(String path) {
        String serverUrl = getNextServer();
        String fullUrl = serverUrl + path;
        
        log.info("Forwarding GET request to {}: {}", serverUrl, path);
        return webClient.get()
                .uri(fullUrl)
                .exchangeToMono(clientResponse -> clientResponse
                        .bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(responseBody -> {
                            HttpHeaders headers = sanitizeHeaders(clientResponse.headers().asHttpHeaders());
                            HttpStatus status = HttpStatus.valueOf(clientResponse.statusCode().value());
                            return new ResponseEntity<>(responseBody, headers, status);
                        }))
                .doOnSuccess(response -> log.info("Response from {}: {}", serverUrl, response.getStatusCode()))
                .doOnError(error -> log.error("Error forwarding to {}: {}", serverUrl, error.getMessage()))
                .onErrorResume(error -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
                    String msg = "{\"error\":\"Server unavailable: " + error.getMessage().replace("\"","\\\"") + "\"}";
                    return Mono.just(new ResponseEntity<>(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, HttpStatus.SERVICE_UNAVAILABLE));
                });
    }
    
    /**
     * 현재 관리 중인 서버 URL 목록을 복사하여 반환
     */
    public List<String> getServerUrls() {
        if (serverUrls.isEmpty()) {
            initializeServers();
        }
        return new ArrayList<>(serverUrls);
    }
}

