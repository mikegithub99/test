package com.example.grpc.simple;

/**
 * Concrete customer gRPC adapter.
 *
 * <p>This class translates the stable adapter contract into calls on the
 * generated gRPC blocking stub. The inherited channel and stub are long-lived
 * and are reused across calls.</p>
 */
public class CustomerAdapterGrpcImpl
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerAdapterGrpc {

    /**
     * Creates a customer adapter connected to the configured gRPC endpoint.
     *
     * @param host service hostname, such as a Kubernetes Service DNS name
     * @param port service gRPC port
     */
    public CustomerAdapterGrpcImpl(String host, int port) {
        super(host, port, CustomerServiceGrpc::newBlockingStub);
    }

    /**
     * Builds the generated request and invokes the backend service.
     *
     * @param id customer identifier
     * @return customer returned by the backend
     */
    @Override
    public Customer getCustomer(String id) {
        GetCustomerRequest request = GetCustomerRequest.newBuilder()
                .setId(id)
                .build();

        return getService().getCustomer(request);
    }
}
