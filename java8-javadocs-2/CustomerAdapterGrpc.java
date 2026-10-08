package com.example.grpc.simple;

/**
 * Application-facing contract for the customer gRPC adapter.
 *
 * <p>The application depends on this contract rather than generated gRPC
 * classes. Retry behavior is applied by {@link GrpcRetryProxy}.</p>
 */
public interface CustomerAdapterGrpc {

    /**
     * Retrieves a customer.
     *
     * @param id customer identifier
     * @return customer returned by the backend
     */
    Customer getCustomer(String id);
}
