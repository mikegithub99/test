package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.function.Function;

/**
 * Base class for gRPC adapters that own one long-lived channel and generated
 * gRPC stub.
 *
 * <p>The channel and stub are intentionally created once and reused for the
 * lifetime of the Spring singleton adapter. A gRPC stub is a lightweight,
 * thread-safe client object that represents how calls are made through the
 * channel; it is not a connection to one fixed Kubernetes Pod.</p>
 *
 * <h2>Why the stub is not replaced on retry</h2>
 *
 * <p>Retries in this design are intended to recover from transient transport
 * or service availability failures, not to perform endpoint discovery.
 * Kubernetes owns endpoint selection behind the Service. The client connects
 * to the stable Service host and port, while the channel manages the
 * underlying connections and name resolution. If a backend Pod disappears,
 * Kubernetes can direct subsequent connection attempts to another healthy
 * Pod without the application creating a new stub.</p>
 *
 * <p>Replacing the generated stub on every retry would therefore add client
 * side endpoint-management complexity without providing useful information:
 * the current adapter does not know which Pod should receive the next call.
 * The same generated stub can be safely reused for concurrent calls and
 * across retries.</p>
 *
 * <h2>Future extension for explicit Pod-aware routing</h2>
 *
 * <p>If a future requirement requires the client to select individual Pods,
 * cache Pod addresses, exclude failed Pods, or deliberately swap a stub to a
 * different Pod, this design can be extended by reintroducing a component
 * such as {@code GrpcClientManager}. That manager could maintain the current
 * set of discovered Pod endpoints and own a pool/cache of channels and stubs,
 * while retry logic selects another eligible endpoint after a failure.</p>
 *
 * <p>That is deliberately not part of the current design because it duplicates
 * endpoint-selection responsibilities already provided by the Kubernetes
 * Service. It would also require explicit lifecycle, cache invalidation,
 * endpoint health, concurrency, and replacement rules.</p>
 *
 * <h2>Kubernetes Service port</h2>
 *
 * <p>All instances behind one Kubernetes Service can use the same Service
 * port because the Service provides one stable virtual endpoint. Kubernetes
 * maps the Service port to the configured target port on the backend Pods.
 * The client therefore needs only the Service host and Service port; it does
 * not need to know individual Pod IP addresses or Pod ports. Different
 * Services may use different ports, but all Pods selected by a given Service
 * expose the Service's configured target port.</p>
 *
 * <p>The host and port are configuration values so the same adapter can be
 * used for the interim non-Kubernetes deployment and the Kubernetes
 * deployment without changing the adapter implementation.</p>
 *
 * @param <T> generated gRPC stub type
 */
public abstract class AdapterGrpcBase<T> {
    /** Long-lived channel owned by this adapter. */
    private final ManagedChannel channel;

    /** Generated stub bound to the long-lived channel. */
    private final T service;

    /**
     * Creates a channel and generated stub once for this adapter instance.
     *
     * @param host stable gRPC host, such as a Kubernetes Service DNS name
     * @param port gRPC port exposed by the target service
     * @param stubFactory factory for the generated blocking or async stub
     */
    protected AdapterGrpcBase(String host, int port, Function<ManagedChannel, T> stubFactory) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.service = stubFactory.apply(channel);
    }

    /** @return the generated gRPC stub shared by adapter calls */
    protected T getService() {
        return service;
    }

    /**
     * Initiates shutdown of the channel.
     *
     * <p>Spring singleton lifecycle management should invoke this when the
     * application context is stopped.</p>
     */
    public void shutdown() {
        channel.shutdown();
    }
}
