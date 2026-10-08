package com.example.grpc.simple.kubernetes;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import java.net.URI;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;

/**
 * gRPC NameResolverProvider for the kube:// scheme.
 */
public final class KubernetesNameResolverProvider extends NameResolverProvider {
    private static final String SCHEME = "kube";
    private final KubernetesNameResolver.Factory resolverFactory;

    public KubernetesNameResolverProvider(KubernetesNameResolver.Factory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return resolverFactory.create(targetUri, args);
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public Set<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.<Class<? extends SocketAddress>>singleton(
                java.net.InetSocketAddress.class);
    }
}
