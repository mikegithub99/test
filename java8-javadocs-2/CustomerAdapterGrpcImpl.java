package com.example.grpc.simple;

/**
 * Concrete customer gRPC adapter.
 *
 * <p>Builds requests and delegates them to the generated blocking stub
 * managed by {@link AdapterGrpcBase}.</p>
 *
 * <p>The current implementation uses one configured host and port. If
 * endpoint discovery and explicit selection are needed later, the adapter can
 * delegate client selection to a dedicated manager.</p>
 */
public class CustomerAdapterGrpcImpl
        extends AdapterGrpcBase<CustomerServiceGrpc.CustomerServiceBlockingStub>
        implements CustomerAdapterGrpc {

    /**
     * Creates a customer adapter for the configured endpoint.
     *
     * @param host gRPC service host
     * @param port gRPC service port
     */
    public CustomerAdapterGrpcImpl(String host, int port) {
        super(host, port, CustomerServiceGrpc::newBlockingStub);
    }

    /**
     * Builds and sends the generated gRPC request.
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
