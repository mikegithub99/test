package com.example.grpc.simple;

import java.util.function.Function;
import java.util.function.Supplier;
import io.grpc.ManagedChannel;

public abstract class AdapterGrpcBase<T> {
    private final T service;
    private final GrpcClientManager manager;

    protected AdapterGrpcBase(GrpcClientManager manager, String serviceName, Function<ManagedChannel, T> stubFactory) {
        this.manager = manager;
        this.service = manager.getClient(serviceName, stubFactory);
    }

    protected T getService() {
        return service;
    }

    protected <R> R execute(Supplier<R> operation) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return operation.get();
            } catch (io.grpc.StatusRuntimeException e) {
                if (!manager.isRetryable(e) || attempts >= manager.getMaxAttempts()) {
                    throw e;
                }
                if (manager.getRetryDelayMillis() > 0) {
                    try {
                        Thread.sleep(manager.getRetryDelayMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during gRPC retry delay", ie);
                    }
                }
            }
        }
    }
}
