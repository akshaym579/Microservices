package com.oneenterprise.userservice.controller;

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
public class ConfigController {

    private final ConfigurableEnvironment environment;

    @Value("${app.message}")
    private String message;

    @Value("${app.environment}")
    private String appEnvironment;

    @Value("${app.platform}")
    private String platform;

    public ConfigController(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeProfiles", environment.getActiveProfiles());
        body.put("app.environment", appEnvironment);
        body.put("app.message", message);
        body.put("app.platform", platform);
        body.put("loadedFromConfigServer", isServedByConfigServer());
        body.put("propertySources", propertySourceNames());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/message")
    public String message() {
        return message;
    }

    private boolean isServedByConfigServer() {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith("configserver:")) {
                return true;
            }
        }
        return false;
    }

    private List<String> propertySourceNames() {
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            names.add(source.getName());
        }
        return names;
    }
}
