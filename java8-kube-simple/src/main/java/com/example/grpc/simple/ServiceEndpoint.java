package com.example.grpc.simple;

public record ServiceEndpoint(String serviceName, String target) {
    public ServiceEndpoint { if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName must not be blank"); if (target == null || target.isBlank()) throw new IllegalArgumentException("target must not be blank"); }
    public static ServiceEndpoint hostPort(String serviceName, String host, int port) { if (host == null || host.isBlank()) throw new IllegalArgumentException("host must not be blank"); if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535"); return new ServiceEndpoint(serviceName, host + ":" + port); }
}