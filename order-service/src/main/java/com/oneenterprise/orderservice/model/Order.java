package com.oneenterprise.orderservice.model;

import java.math.BigDecimal;

public class Order {

    private final Long id;
    private final Long userId;
    private final String product;
    private final BigDecimal amount;

    private String status;
    private String paymentId;

    public Order(Long id, Long userId, String product, BigDecimal amount, String status) {
        this.id = id;
        this.userId = userId;
        this.product = product;
        this.amount = amount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProduct() {
        return product;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }
}
