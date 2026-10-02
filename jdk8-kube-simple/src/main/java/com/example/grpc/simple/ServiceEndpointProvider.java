package com.example.grpc.simple;

@FunctionalInterface
public interface ServiceEndpointProvider {
    ServiceEndpoint endpoint(String serviceName);
}
