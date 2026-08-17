package com.oneenterprise.paymentservice.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class FaultInjector {

    public enum Mode {
        OK,
        SLOW,
        FAIL,
        DECLINE
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    private final AtomicLong delayMs = new AtomicLong(0);
    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    private final AtomicLong callsReceived = new AtomicLong(0);
    private final AtomicLong callsFailed = new AtomicLong(0);

    public void configure(Mode newMode, long newDelayMs, int failures) {
        mode.set(newMode);
        delayMs.set(newDelayMs);
        remainingFailures.set(failures);
    }

    public void reset() {
        configure(Mode.OK, 0, 0);
        callsReceived.set(0);
        callsFailed.set(0);
    }

    public long recordCall() {
        return callsReceived.incrementAndGet();
    }

    public void recordFailure() {
        callsFailed.incrementAndGet();
    }

    public boolean consumeTransientFailure() {
        return remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    public Mode getMode() {
        return mode.get();
    }

    public long getDelayMs() {
        return delayMs.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("mode", mode.get().name());
        stats.put("delayMs", delayMs.get());
        stats.put("remainingFailures", remainingFailures.get());
        stats.put("callsReceived", callsReceived.get());
        stats.put("callsFailed", callsFailed.get());
        return stats;
    }
}
