package com.oneenterprise.orderservice.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/circuit-breaker")
public class CircuitBreakerAdminController {

    private static final String INSTANCE = "paymentService";

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerAdminController(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> state() {
        return ResponseEntity.ok(snapshot());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        registry.circuitBreaker(INSTANCE).reset();
        return ResponseEntity.ok(snapshot());
    }

    private Map<String, Object> snapshot() {
        CircuitBreaker breaker = registry.circuitBreaker(INSTANCE);
        CircuitBreaker.Metrics metrics = breaker.getMetrics();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", breaker.getName());
        body.put("state", breaker.getState().name());
        body.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        body.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
        body.put("failedCalls", metrics.getNumberOfFailedCalls());
        body.put("failureRatePercent", metrics.getFailureRate());
        body.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        return body;
    }
}
