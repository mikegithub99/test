package com.example.grpc.simple;

public class CustomerAdapterGrpcImpl
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerAdapterGrpc {

    public CustomerAdapterGrpcImpl(String host, int port) {
        super(host, port, CustomerServiceGrpc::newBlockingStub);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder().setId(id).build();
        return getService().getCustomer(request);
    }
}
