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
 * <p>The policy limits the total number of attempts and calculates an
 * exponential backoff between attempts. Only the configured gRPC status
 * codes are eligible for retry.</p>
 */
public final class GrpcRetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(GrpcRetryPolicy.class);

    /** Maximum number of total RPC attempts, including the initial attempt. */
    private final int maxAttempts;

    /** Initial retry delay in milliseconds. */
    private final long delayMillis;

    /** Multiplier applied to the previous retry delay. */
    private final double backoffMultiplier;

    /** Maximum delay for any single retry. */
    private final long maxDelayMillis;

    /** Immutable set of gRPC status codes that may be retried. */
    private final Set<Status.Code> retryableStatuses;

    /**
     * Creates an immutable retry policy.
     *
     * @param maxAttempts maximum total number of RPC attempts
     * @param delayMillis initial retry delay in milliseconds
     * @param backoffMultiplier exponential backoff multiplier
     * @param maxDelayMillis maximum delay for one retry
     * @param retryableStatuses gRPC statuses eligible for retry
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

    /** Logs the effective retry configuration after Spring creates the bean. */
    @PostConstruct
    private void logConfiguration() {
        log.debug(
                "gRPC retry policy: maxAttempts={}, delayMillis={}, backoffMultiplier={}, " +
                "maxDelayMillis={}, retryableStatuses={}",
                maxAttempts, delayMillis, backoffMultiplier, maxDelayMillis, retryableStatuses);
    }

    /** @return maximum total number of RPC attempts */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** @return whether the supplied status code is configured for retry */
    public boolean isRetryable(Status.Code code) {
        return retryableStatuses.contains(code);
    }

    /** @return immutable set of configured retryable status codes */
    public Set<Status.Code> getRetryableStatuses() {
        return retryableStatuses;
    }

    /**
     * Calculates the delay after a failed attempt.
     *
     * <p>For example, with a one-second initial delay and a multiplier of
     * 2.0, retry delays are 1s, 2s, 4s, 8s, and so on, up to the configured
     * maximum delay.</p>
     *
     * @param attempt failed attempt number, starting at 1
     * @return delay before the next attempt in milliseconds
     */
    public long getRetryDelayMillis(int attempt) {
        if (delayMillis <= 0 || maxDelayMillis <= 0) return 0L;

        double delay = delayMillis * Math.pow(backoffMultiplier, attempt - 1);
        return Math.min((long) delay, maxDelayMillis);
    }
}
