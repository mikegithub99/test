package com.example.grpc.simple;

import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Defines the retry policy used by the gRPC client adapters.
 *
 * <p>The policy limits total attempts and calculates a bounded exponential
 * backoff. Only configured gRPC status codes are retried.</p>
 */
public final class GrpcRetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(GrpcRetryPolicy.class);

    /** Maximum total RPC attempts, including the initial attempt. */
    private final int maxAttempts;
    /** Initial retry delay in milliseconds. */
    private final long delayMillis;
    /** Exponential backoff multiplier. */
    private final double backoffMultiplier;
    /** Maximum delay for one retry. */
    private final long maxDelayMillis;
    /** gRPC status codes eligible for retry. */
    private final Set<Status.Code> retryableStatuses;

    /**
     * Creates an immutable retry policy.
     *
     * @param maxAttempts maximum total attempts
     * @param delayMillis initial retry delay
     * @param backoffMultiplier backoff multiplier
     * @param maxDelayMillis maximum delay for one retry
     * @param retryableStatuses retryable gRPC statuses
     */
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
                retryableStatuses.isEmpty()
                        ? EnumSet.noneOf(Status.Code.class)
                        : EnumSet.copyOf(retryableStatuses));
    }

    /** Logs the effective retry configuration after bean creation. */
    @PostConstruct
    private void logConfiguration() {
        log.debug(
                "gRPC retry policy: maxAttempts={}, delayMillis={}, backoffMultiplier={}, " +
                "maxDelayMillis={}, retryableStatuses={}",
                maxAttempts, delayMillis, backoffMultiplier, maxDelayMillis, retryableStatuses);
    }

    /** @return maximum total RPC attempts */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** @return whether the status is configured for retry */
    public boolean isRetryable(Status.Code code) {
        return retryableStatuses.contains(code);
    }

    /** @return immutable set of retryable status codes */
    public Set<Status.Code> getRetryableStatuses() {
        return retryableStatuses;
    }

    /**
     * Calculates the delay before the next attempt.
     *
     * @param attempt failed attempt number, starting at 1
     * @return delay in milliseconds
     */
    public long getRetryDelayMillis(int attempt) {
        if (delayMillis <= 0 || maxDelayMillis <= 0) return 0L;
        double delay = delayMillis * Math.pow(backoffMultiplier, attempt - 1);
        return Math.min((long) delay, maxDelayMillis);
    }
}
