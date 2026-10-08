package com.example.grpc.simple.kubernetes;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.kubernetes.client.openapi.ApiClient;

import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reactive gRPC NameResolver backed by Kubernetes EndpointSlice discovery.
 */
public final class KubernetesNameResolver extends NameResolver {
    private final URI targetUri;
    private final Factory factory;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private volatile Listener2 listener;
    private volatile KubernetesEndpointSliceDiscovery discovery;

    KubernetesNameResolver(URI targetUri, Factory factory) {
        this.targetUri = targetUri;
        this.factory = factory;
    }

    @Override
    public String getServiceAuthority() {
        String path = targetUri.getPath();
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }

    @Override
    public void start(Listener2 listener) {
        if (this.listener != null) {
            throw new IllegalStateException("NameResolver already started");
        }
        this.listener = Objects.requireNonNull(listener, "listener");
        String serviceName = getServiceAuthority();

        if (serviceName == null || serviceName.isEmpty()) {
            listener.onError(Status.INVALID_ARGUMENT
                    .withDescription("kube target must contain a Service name")
                    .asRuntimeException());
            return;
        }

        this.discovery = new KubernetesEndpointSliceDiscovery(
                factory.apiClient, factory.namespace, serviceName, this::publish);
        discovery.start();
    }

    private void publish(List<SocketAddress> addresses) {
        if (shutdown.get()) {
            return;
        }

        List<EquivalentAddressGroup> groups = new ArrayList<>();
        for (SocketAddress address : addresses) {
            groups.add(new EquivalentAddressGroup(address));
        }

        Listener2 current = listener;
        if (current != null) {
            current.onResult(ResolutionResult.newBuilder()
                    .setAddresses(Collections.unmodifiableList(groups))
                    .setAttributes(Attributes.EMPTY)
                    .build());
        }
    }

    @Override
    public void refresh() {
        KubernetesEndpointSliceDiscovery current = discovery;
        if (current != null && !shutdown.get()) {
            current.refresh();
        }
    }

    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        KubernetesEndpointSliceDiscovery current = discovery;
        if (current != null) {
            current.close();
        }
    }

    public static final class Factory {
        private final ApiClient apiClient;
        private final String namespace;

        public Factory(ApiClient apiClient, String namespace) {
            this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
            this.namespace = Objects.requireNonNull(namespace, "namespace");
        }

        KubernetesNameResolver create(URI targetUri, NameResolver.Args args) {
            if (!"kube".equalsIgnoreCase(targetUri.getScheme())) {
                return null;
            }
            return new KubernetesNameResolver(targetUri, this);
        }
    }
}
