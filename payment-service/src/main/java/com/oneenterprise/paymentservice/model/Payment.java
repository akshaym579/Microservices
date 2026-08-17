package com.oneenterprise.paymentservice.model;

import java.math.BigDecimal;
import java.time.Instant;

public class Payment {

    private final String paymentId;
    private final Long orderId;
    private final BigDecimal amount;
    private final String status;
    private final Instant capturedAt;

    public Payment(String paymentId, Long orderId, BigDecimal amount, String status, Instant capturedAt) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
