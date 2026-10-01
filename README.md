# gRPC Service Discovery

Java 21 / Spring Boot reference implementation for external registry discovery and application-local static discovery.

Architecture:
CustomerApi -> client manager -> ServiceDiscovery -> ManagedChannel -> generated gRPC stub

The manager resolves a service initially, reuses the cached channel for normal calls, and performs a fresh discovery lookup only after a configured retryable gRPC failure.

Static configuration example:

grpc:
  discovery:
    mode: STATIC
    services:
      CUSTOMER_SERVICE:
        - instance-id: customer-1
          host: server-a
          port: 50051
        - instance-id: customer-2
          host: server-b
          port: 50051
        - instance-id: customer-3
          host: server-a
          port: 50052

Registry mode uses the same ServiceDiscovery interface and expects the registry to return service_name, instance_id, host and port. No Java mapping from protobuf stub types to hosts is required.

The included registry.proto defines Register, Unregister, Lookup (with excluded instance ids), and Heartbeat.

For production TLS, replace plaintext channel construction with the application's shared channel/security configuration.