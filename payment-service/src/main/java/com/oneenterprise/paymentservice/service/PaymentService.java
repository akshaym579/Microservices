package com.oneenterprise.paymentservice.service;

import com.oneenterprise.paymentservice.exception.PaymentDeclinedException;
import com.oneenterprise.paymentservice.exception.PaymentProcessingException;
import com.oneenterprise.paymentservice.model.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final Map<String, Payment> paymentsById = new ConcurrentHashMap<>();
    private final Map<String, Payment> paymentsByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicLong idempotentReplays = new AtomicLong(0);

    private final FaultInjector faults;

    public PaymentService(FaultInjector faults) {
        this.faults = faults;
    }

    public Payment charge(Long orderId, BigDecimal amount, String idempotencyKey) {
        long call = faults.recordCall();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return capture(orderId, amount, call);
        }

        AtomicBoolean captured = new AtomicBoolean(false);
        Payment payment = paymentsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            captured.set(true);
            return capture(orderId, amount, call);
        });

        if (!captured.get()) {
            idempotentReplays.incrementAndGet();
            log.info("Call {} replayed idempotency key {}, returning existing payment {}",
                    call, idempotencyKey, payment.getPaymentId());
        }
        return payment;
    }

    private Payment capture(Long orderId, BigDecimal amount, long call) {
        applyConfiguredFaults(orderId, call);

        Payment payment = new Payment(UUID.randomUUID().toString(), orderId, amount, "COMPLETED", Instant.now());
        paymentsById.put(payment.getPaymentId(), payment);
        log.info("Call {} captured payment {} for order {}", call, payment.getPaymentId(), orderId);
        return payment;
    }

    private void applyConfiguredFaults(Long orderId, long call) {
        if (faults.consumeTransientFailure()) {
            faults.recordFailure();
            log.warn("Call {} failing on purpose (transient failure budget)", call);
            throw new PaymentProcessingException("Payment provider temporarily unavailable");
        }

        switch (faults.getMode()) {
            case SLOW -> sleep(faults.getDelayMs(), call);
            case FAIL -> {
                faults.recordFailure();
                log.warn("Call {} failing on purpose (mode=FAIL)", call);
                throw new PaymentProcessingException("Payment provider is not responding correctly");
            }
            case DECLINE -> {
                faults.recordFailure();
                log.warn("Call {} declined on purpose (mode=DECLINE)", call);
                throw new PaymentDeclinedException(orderId);
            }
            case OK -> {
            }
        }
    }

    private void sleep(long millis, long call) {
        log.warn("Call {} sleeping {}ms on purpose (mode=SLOW)", call, millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PaymentProcessingException("Interrupted while simulating a slow provider");
        }
    }

    public Payment findById(String paymentId) {
        return paymentsById.get(paymentId);
    }

    public long getIdempotentReplays() {
        return idempotentReplays.get();
    }

    public long getPaymentsCaptured() {
        return paymentsById.size();
    }

    public void clear() {
        paymentsById.clear();
        paymentsByIdempotencyKey.clear();
        idempotentReplays.set(0);
    }
}
