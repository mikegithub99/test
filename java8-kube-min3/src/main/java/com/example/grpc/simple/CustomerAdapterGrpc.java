package com.example.grpc.simple;

public class CustomerAdapterGrpc extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerApi {

    public CustomerAdapterGrpc(GrpcClientManager manager) {
        super(manager, "customer-service", CustomerServiceGrpc::newBlockingStub);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        return execute(() -> getService().getCustomer(request));
    }
}
