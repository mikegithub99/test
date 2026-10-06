package com.example.grpc.simple;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StaticServiceEndpointProvider implements ServiceEndpointProvider {
    private final Map<String, ServiceEndpoint> endpoints =
            new ConcurrentHashMap<String, ServiceEndpoint>();

    public StaticServiceEndpointProvider(String serviceName, String host, int port) {
        endpoints.put(serviceName, new ServiceEndpoint(host, port));
    }

    @Override
    public ServiceEndpoint getEndpoint(String serviceName) {
        ServiceEndpoint endpoint = endpoints.get(serviceName);
        if (endpoint == null) {
            throw new IllegalArgumentException("No endpoint configured for service: " + serviceName);
        }
        return endpoint;
    }
}