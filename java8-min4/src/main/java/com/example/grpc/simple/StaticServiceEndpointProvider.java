package com.example.grpc.simple;

public class StaticServiceEndpointProvider implements ServiceEndpointProvider {
    private final String serviceName;
    private final ServiceEndpoint endpoint;

    public StaticServiceEndpointProvider(String serviceName, String host, int port) {
        this.serviceName = serviceName;
        this.endpoint = new ServiceEndpoint(host, port);
    }

    @Override
    public ServiceEndpoint getEndpoint(String serviceName) {
        if (!this.serviceName.equals(serviceName)) {
            throw new IllegalArgumentException(
                    "No endpoint configured for service: " + serviceName);
        }
        return endpoint;
    }
}
