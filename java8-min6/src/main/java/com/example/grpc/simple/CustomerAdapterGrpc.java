package com.example.grpc.simple;

public class CustomerAdapterGrpc
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerApi {

    public CustomerAdapterGrpc(String host, int port) {
        super(
                host,
                port,
                CustomerServiceGrpc::newBlockingStub);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        return getService().getCustomer(request);
    }
}
