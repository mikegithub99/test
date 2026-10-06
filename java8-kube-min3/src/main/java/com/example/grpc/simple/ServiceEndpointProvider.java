package com.example.grpc.simple;

public interface ServiceEndpointProvider {
    ServiceEndpoint getEndpoint(String serviceName);
}