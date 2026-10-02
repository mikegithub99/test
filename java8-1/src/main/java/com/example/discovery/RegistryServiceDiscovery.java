package com.example.discovery;

import com.example.registry.grpc.GrpcServiceRegistryGrpc;
import com.example.registry.grpc.LookupRequest;
import com.example.registry.grpc.LookupResponse;
import com.example.registry.grpc.ServiceInstance;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.Set;

public final class RegistryServiceDiscovery
        implements ServiceDiscovery, AutoCloseable {

    private final ManagedChannel channel;
    private final GrpcServiceRegistryGrpc.GrpcServiceRegistryBlockingStub stub;

    public RegistryServiceDiscovery(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }

        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = GrpcServiceRegistryGrpc.newBlockingStub(channel);
    }

    @Override
    public ServiceTarget lookup(
            String serviceName,
            Set<String> excludedInstanceIds) {

        LookupRequest.Builder builder = LookupRequest.newBuilder()
                .setServiceName(serviceName);

        if (excludedInstanceIds != null) {
            builder.addAllExcludedInstanceIds(excludedInstanceIds);
        }

        LookupResponse response = stub.lookup(builder.build());
        if (!response.hasInstance()) {
            throw new IllegalStateException(
                    "Registry returned no instance for " + serviceName);
        }

        ServiceInstance instance = response.getInstance();

        if (instance.getServiceName().trim().isEmpty()
                || instance.getHost().trim().isEmpty()
                || instance.getPort() <= 0) {
            throw new IllegalStateException("Invalid registry target");
        }

        return new ServiceTarget(
                instance.getServiceName(),
                instance.getInstanceId(),
                instance.getHost() + ":" + instance.getPort());
    }

    @Override
    public void close() {
        channel.shutdown();
    }
}
