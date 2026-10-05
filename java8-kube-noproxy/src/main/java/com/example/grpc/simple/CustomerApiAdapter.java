package com.example.grpc.simple;

public final class CustomerApiAdapter implements CustomerApi {
    private final GrpcClientManager manager;
    private final GrpcClientFactory<CustomerGrpcClient> factory;

    public CustomerApiAdapter(GrpcClientManager manager) {
        this.manager = manager;
        this.factory = new GrpcClientFactory<CustomerGrpcClient>() {
            @Override
            public CustomerGrpcClient create(io.grpc.ManagedChannel channel) {
                return new CustomerGrpcClient(channel);
            }
        };
    }

    @Override
    public String getCustomer(final String customerId) {
        return manager.execute(
                "CUSTOMER_SERVICE",
                factory,
                new java.util.function.Function<CustomerGrpcClient, String>() {
                    @Override
                    public String apply(CustomerGrpcClient client) {
                        return client.getCustomer(customerId);
                    }
                });
    }
}
