package com.example.grpc.simple;

import io.grpc.StatusRuntimeException;

public class CustomerApiAdapter implements CustomerApi {
    private final GrpcClientManager manager;
    private final CustomerServiceGrpc.CustomerServiceBlockingStub stub;

    public CustomerApiAdapter(GrpcClientManager manager) {
        this.manager = manager;
        this.stub = manager.getClient("customer-service",
                CustomerServiceGrpc::newBlockingStub);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return stub.getCustomer(request);
            } catch (StatusRuntimeException e) {
                if (!manager.isRetryable(e) || attempts >= manager.getMaxAttempts()) {
                    throw e;
                }

                if (manager.getRetryDelayMillis() > 0) {
                    try {
                        Thread.sleep(manager.getRetryDelayMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during gRPC retry delay", ie);
                    }
                }
            }
        }
    }
}