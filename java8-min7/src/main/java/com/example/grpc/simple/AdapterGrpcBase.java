package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.function.Function;

public abstract class AdapterGrpcBase<T> {
    private final ManagedChannel channel;
    private final T service;

    protected AdapterGrpcBase(String host, int port, Function<ManagedChannel, T> stubFactory) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.service = stubFactory.apply(channel);
    }

    protected T getService() {
        return service;
    }

    public void shutdown() {
        channel.shutdown();
    }
}
