package com.example.grpc.simple;

public class CustomerAdapterGrpc
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerApi {

    public CustomerAdapterGrpc(
            ServiceEndpointProvider endpointProvider,
            int maxAttempts,
            long retryDelayMillis) {

        super(
                endpointProvider,
                "customer-service",
                CustomerServiceGrpc::newBlockingStub,
                maxAttempts,
                retryDelayMillis);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        return execute(() -> getService().getCustomer(request));
    }
}
