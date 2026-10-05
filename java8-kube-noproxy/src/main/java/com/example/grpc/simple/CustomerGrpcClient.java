package com.example.grpc.simple;

import io.grpc.ManagedChannel;

/*
 * Replace CustomerServiceGrpc with the generated class from your .proto.
 */
public final class CustomerGrpcClient {
    private final Object stub;

    public CustomerGrpcClient(ManagedChannel channel) {
        this.stub = createStub(channel);
    }

    private Object createStub(ManagedChannel channel) {
        /*
         * Example:
         * return CustomerServiceGrpc.newBlockingStub(channel);
         */
        throw new UnsupportedOperationException("Bind this adapter to your generated CustomerServiceGrpc stub");
    }

    public String getCustomer(String customerId) {
        /*
         * Example:
         * GetCustomerRequest request = GetCustomerRequest.newBuilder()
         *         .setCustomerId(customerId)
         *         .build();
         * return stub.getCustomer(request).getName();
         */
        throw new UnsupportedOperationException("Implement getCustomer using the generated gRPC stub");
    }
}
