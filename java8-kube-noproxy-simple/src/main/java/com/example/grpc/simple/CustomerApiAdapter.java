package com.example.grpc.simple;

public final class CustomerApiAdapter implements CustomerApi {
    private final GrpcClientManager manager;
    private final GrpcClientFactory<Object> factory;

    public CustomerApiAdapter(GrpcClientManager manager) {
        this.manager = manager;
        this.factory = new GrpcClientFactory<Object>() {
            @Override
            public Object create(io.grpc.ManagedChannel channel) {
                // Replace Object with your generated stub type.
                // Example: return CustomerServiceGrpc.newBlockingStub(channel);
                throw new UnsupportedOperationException(
                        "Bind this adapter to your generated CustomerServiceGrpc stub");
            }
        };
    }

    @Override
    public String getCustomer(final String customerId) {
        return manager.execute(
                "CUSTOMER_SERVICE",
                factory,
                new java.util.function.Function<Object, String>() {
                    @Override
                    public String apply(Object stub) {
                        // Build the generated request and invoke the generated stub here.
                        // Example:
                        // GetCustomerRequest request = GetCustomerRequest.newBuilder()
                        //         .setCustomerId(customerId).build();
                        // return ((CustomerServiceGrpc.CustomerServiceBlockingStub) stub)
                        //         .getCustomer(request).getName();
                        throw new UnsupportedOperationException(
                                "Implement getCustomer using the generated gRPC stub");
                    }
                });
    }
}