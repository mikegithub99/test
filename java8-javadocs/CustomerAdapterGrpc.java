package com.example.grpc.simple;

/**
 * gRPC-specific adapter contract used by the application/framework.
 *
 * <p>The application depends on this adapter contract rather than on the
 * generated gRPC stub classes. Retry behavior is applied to this contract by
 * {@link GrpcRetryProxy} in the Spring configuration.</p>
 */
public interface CustomerAdapterGrpc {

    /**
     * Retrieves a customer from the backend service.
     *
     * @param id customer identifier
     * @return customer returned by the backend
     */
    Customer getCustomer(String id);
}
