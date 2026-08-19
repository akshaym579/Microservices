package com.oneenterprise.orderservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/config")
public class ConfigAdminController {

    private final ConfigurableEnvironment environment;

    @Value("${app.environment}")
    private String appEnvironment;

    @Value("${user-service.read-timeout-ms}")
    private int userReadTimeoutMs;

    @Value("${payment-service.read-timeout-ms}")
    private int paymentReadTimeoutMs;

    @Value("${resilience4j.retry.instances.paymentService.max-attempts}")
    private int paymentMaxAttempts;

    public ConfigAdminController(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeProfiles", environment.getActiveProfiles());
        body.put("app.environment", appEnvironment);
        body.put("user-service.read-timeout-ms", userReadTimeoutMs);
        body.put("payment-service.read-timeout-ms", paymentReadTimeoutMs);
        body.put("paymentService.max-attempts", paymentMaxAttempts);
        body.put("loadedFromConfigServer", isServedByConfigServer());
        body.put("winningSource", sourceOf("user-service.read-timeout-ms"));
        return ResponseEntity.ok(body);
    }

    private boolean isServedByConfigServer() {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith("configserver:")) {
                return true;
            }
        }
        return false;
    }

    private String sourceOf(String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().equals("configurationProperties")) {
                continue;
            }
            if (source.containsProperty(key)) {
                return source.getName();
            }
        }
        return "not found";
    }
}
