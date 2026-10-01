# C++17 gRPC Service Provider

The C++ side of the service-discovery design is under this directory.

A service starts its normal gRPC server and creates a ServiceProvider. The provider registers the logical service name, unique instance ID, host, port and TTL; sends heartbeats approximately every TTL/3; and unregisters during normal shutdown.

Unexpected process termination is handled by the registry TTL.

Build with CMake, Protobuf and gRPC C++ development packages:

cmake -S cpp -B cpp/build
cmake --build cpp/build -j

Multiple instances can register independently, for example:

customer-1 -> 127.0.0.1:50051
customer-2 -> 127.0.0.1:50052
customer-3 -> 127.0.0.1:50053

The Java client can discover these instances and exclude failed instance IDs during failover.

The example uses insecure credentials for local development. Production deployments should use TLS and appropriate authentication/authorization.
