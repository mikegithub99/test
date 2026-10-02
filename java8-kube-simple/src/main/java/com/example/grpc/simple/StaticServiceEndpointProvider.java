package com.example.grpc.simple;
import java.util.Map;
import java.util.Objects;
public final class StaticServiceEndpointProvider implements ServiceEndpointProvider {
    private final Map<String, ServiceEndpoint> endpoints;
    public StaticServiceEndpointProvider(Map<String, ServiceEndpoint> endpoints) { this.endpoints = Map.copyOf(Objects.requireNonNull(endpoints)); }
    public ServiceEndpoint endpoint(String serviceName) { ServiceEndpoint e = endpoints.get(serviceName); if (e == null) throw new IllegalStateException("No gRPC endpoint configured for service: " + serviceName); return e; }
}