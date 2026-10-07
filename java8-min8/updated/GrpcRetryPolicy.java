package com.example.grpc.simple;

import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class GrpcRetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(GrpcRetryPolicy.class);
    private final int maxAttempts;
    private final long delayMillis;
    private final double backoffMultiplier;
    private final long maxDelayMillis;
    private final Set<Status.Code> retryableStatuses;

    public GrpcRetryPolicy(int maxAttempts, long delayMillis, double backoffMultiplier,
                           long maxDelayMillis, Set<Status.Code> retryableStatuses) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        if (delayMillis < 0) throw new IllegalArgumentException("delayMillis cannot be negative");
        if (backoffMultiplier < 1.0) throw new IllegalArgumentException("backoffMultiplier must be at least 1.0");
        if (maxDelayMillis < 0) throw new IllegalArgumentException("maxDelayMillis cannot be negative");
        this.maxAttempts = maxAttempts;
        this.delayMillis = delayMillis;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMillis = maxDelayMillis;
        this.retryableStatuses = Collections.unmodifiableSet(
                retryableStatuses.isEmpty() ? EnumSet.noneOf(Status.Code.class) : EnumSet.copyOf(retryableStatuses));
    }

    @PostConstruct
    private void logConfiguration() {
        log.debug("gRPC retry policy: maxAttempts={}, delayMillis={}, backoffMultiplier={}, maxDelayMillis={}, retryableStatuses={}",
                maxAttempts, delayMillis, backoffMultiplier, maxDelayMillis, retryableStatuses);
    }

    public int getMaxAttempts() { return maxAttempts; }
    public boolean isRetryable(Status.Code code) { return retryableStatuses.contains(code); }
    public Set<Status.Code> getRetryableStatuses() { return retryableStatuses; }

    public long getRetryDelayMillis(int attempt) {
        if (delayMillis <= 0 || maxDelayMillis <= 0) return 0L;
        double delay = delayMillis * Math.pow(backoffMultiplier, attempt - 1);
        return Math.min((long) delay, maxDelayMillis);
    }
}
