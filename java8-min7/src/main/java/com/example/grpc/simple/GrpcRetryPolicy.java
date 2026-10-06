package com.example.grpc.simple;

import io.grpc.Status;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class GrpcRetryPolicy {
    private final int maxAttempts;
    private final long delayMillis;
    private final Set<Status.Code> retryableStatuses;

    public GrpcRetryPolicy(int maxAttempts, long delayMillis, Set<Status.Code> retryableStatuses) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        if (retryableStatuses == null || retryableStatuses.isEmpty()) {
            throw new IllegalArgumentException("retryableStatuses must not be empty");
        }
        this.maxAttempts = maxAttempts;
        this.delayMillis = delayMillis;
        this.retryableStatuses = Collections.unmodifiableSet(
                EnumSet.copyOf(retryableStatuses));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public boolean isRetryable(Status.Code code) {
        return retryableStatuses.contains(code);
    }
}
