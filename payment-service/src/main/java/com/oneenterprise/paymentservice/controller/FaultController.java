package com.oneenterprise.paymentservice.controller;

import com.oneenterprise.paymentservice.service.FaultInjector;
import com.oneenterprise.paymentservice.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class FaultController {

    private final FaultInjector faults;
    private final PaymentService paymentService;

    public FaultController(FaultInjector faults, PaymentService paymentService) {
        this.faults = faults;
        this.paymentService = paymentService;
    }

    @PostMapping("/behaviour")
    public ResponseEntity<Map<String, Object>> configure(
            @RequestParam(defaultValue = "OK") FaultInjector.Mode mode,
            @RequestParam(defaultValue = "0") long delayMs,
            @RequestParam(defaultValue = "0") int failures) {

        faults.configure(mode, delayMs, failures);
        return ResponseEntity.ok(stats());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        faults.reset();
        paymentService.clear();
        return ResponseEntity.ok(stats());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(stats());
    }

    private Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>(faults.snapshot());
        body.put("paymentsCaptured", paymentService.getPaymentsCaptured());
        body.put("idempotentReplays", paymentService.getIdempotentReplays());
        return body;
    }
}
