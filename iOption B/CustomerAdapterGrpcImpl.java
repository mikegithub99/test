package com.example.grpc.simple;

/**
 * Concrete customer gRPC adapter for Option B.
 *
 * The adapter uses a stable target such as kube:///customer-service.
 * Kubernetes EndpointSlice discovery and gRPC round_robin are below the
 * adapter and are invisible to the application contract.
 */
public class CustomerAdapterGrpcImpl
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerAdapterGrpc {

    public CustomerAdapterGrpcImpl(String target) {
        super(target, CustomerServiceGrpc::newBlockingStub);
    }

    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        return getService().getCustomer(request);
    }
}
