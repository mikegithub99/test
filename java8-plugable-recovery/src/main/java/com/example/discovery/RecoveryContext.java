package com.example.discovery;

import io.grpc.StatusRuntimeException;

public final class RecoveryContext {
    private final String serviceName;
    private final GrpcClient failedClient;
    private final StatusRuntimeException failure;
    private final int attempt;
    private final int maxAttempts;

    public RecoveryContext(String serviceName, GrpcClient failedClient,
                           StatusRuntimeException failure, int attempt, int maxAttempts) {
        this.serviceName = serviceName;
        this.failedClient = failedClient;
        this.failure = failure;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
    }

    public String serviceName() { return serviceName; }
    public GrpcClient failedClient() { return failedClient; }
    public StatusRuntimeException failure() { return failure; }
    public int attempt() { return attempt; }
    public int maxAttempts() { return maxAttempts; }
}
