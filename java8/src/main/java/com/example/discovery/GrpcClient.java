package com.example.discovery;

import io.grpc.ManagedChannel;

public final class GrpcClient {
    private final ServiceTarget target;
    private final ManagedChannel channel;
    private final Object delegate;

    public GrpcClient(
            ServiceTarget target,
            ManagedChannel channel,
            Object delegate) {
        this.target = target;
        this.channel = channel;
        this.delegate = delegate;
    }

    public ServiceTarget target() {
        return target;
    }

    @SuppressWarnings("unchecked")
    public <T> T delegate() {
        return (T) delegate;
    }

    public void retire() {
        channel.shutdown();
    }
}
