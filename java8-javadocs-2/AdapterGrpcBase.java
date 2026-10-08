package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.function.Function;

/**
 * Base class for gRPC adapters using one configured endpoint.
 *
 * <p>The channel and generated stub are created once and reused for the
 * lifetime of the adapter. Retries reuse the same adapter infrastructure.</p>
 *
 * <p>The current implementation intentionally keeps endpoint management
 * simple: the adapter receives a host and port from configuration. It does
 * not discover or select individual backend endpoints.</p>
 *
 * <p>If future requirements need explicit endpoint-level control, this layer
 * can be extended to use a client manager that maintains reusable channels and
 * stubs for discovered endpoints and works with an endpoint-selection policy.</p>
 *
 * @param <T> generated gRPC stub type
 */
public abstract class AdapterGrpcBase<T> {
    /** Long-lived channel owned by this adapter. */
    private final ManagedChannel channel;
    /** Generated stub bound to the channel. */
    private final T service;

    /**
     * Creates the channel and generated stub.
     *
     * @param host configured gRPC host
     * @param port configured gRPC port
     * @param stubFactory generated stub factory
     */
    protected AdapterGrpcBase(String host, int port, Function<ManagedChannel, T> stubFactory) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.service = stubFactory.apply(channel);
    }

    /** @return generated gRPC stub shared by adapter calls */
    protected T getService() {
        return service;
    }

    /** Shuts down the channel. */
    public void shutdown() {
        channel.shutdown();
    }
}
