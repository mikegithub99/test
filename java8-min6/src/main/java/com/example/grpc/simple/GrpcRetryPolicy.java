package com.example.grpc.simple;

import io.grpc.Status;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class GrpcRetryPolicy {
    private final int maxAttempts;
    private final long delayMillis;
    private final Set<Status.Code> retryableStatuses;

    public GrpcRetryPolicy(
            int maxAttempts,
            long delayMillis,
            Set<Status.Code> retryableStatuses) {

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis cannot be negative");
        }

        this.maxAttempts = maxAttempts;
        this.delayMillis = delayMillis;
        this.retryableStatuses = Collections.unmodifiableSet(
                retryableStatuses.isEmpty()
                        ? EnumSet.noneOf(Status.Code.class)
                        : EnumSet.copyOf(retryableStatuses));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public Set<Status.Code> getRetryableStatuses() {
        return retryableStatuses;
    }

    public boolean isRetryable(Status.Code code) {
        return retryableStatuses.contains(code);
    }
}
