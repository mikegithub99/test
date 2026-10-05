package com.example.grpc.simple;

import java.util.Objects;

public final class ServiceEndpoint {
    private final String serviceName;
    private final String target;

    public ServiceEndpoint(String serviceName, String target) {
        if (isBlank(serviceName)) throw new IllegalArgumentException("serviceName must not be blank");
        if (isBlank(target)) throw new IllegalArgumentException("target must not be blank");
        this.serviceName = serviceName;
        this.target = target;
    }

    public static ServiceEndpoint hostPort(String serviceName, String host, int port) {
        if (isBlank(host)) throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        return new ServiceEndpoint(serviceName, host + ":" + port);
    }

    public String getServiceName() { return serviceName; }
    public String getTarget() { return target; }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ServiceEndpoint)) return false;
        ServiceEndpoint that = (ServiceEndpoint) other;
        return serviceName.equals(that.serviceName) && target.equals(that.target);
    }

    @Override public int hashCode() { return Objects.hash(serviceName, target); }

    @Override public String toString() {
        return "ServiceEndpoint{serviceName='" + serviceName + "', target='" + target + "'}";
    }
}
