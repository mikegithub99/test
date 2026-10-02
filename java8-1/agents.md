# AGENTS.md

## Purpose
This folder contains the Java 8-compatible implementation of the gRPC service discovery and client lifecycle design.

Core rules:
- one authoritative active client per logical service;
- one active ManagedChannel per logical service;
- retired clients drain existing operations before channel shutdown;
- service-specific adapters own generated gRPC stub types;
- discovery is independent from service-specific Java types.

## Architecture
Application-facing APIs are normally Spring singletons. The flow is CustomerApi -> GrpcClientManager -> activeClients[CUSTOMER_SERVICE] -> GrpcClient -> ManagedChannel.

The proxy must not permanently own a channel. GrpcClientManager owns discovery, active-client selection, replacement, retry, and lifecycle.

## Authoritative active state
GrpcClientManager.activeClients is the single authoritative active-client store. Do not reintroduce a second ConcurrentMap or other structure containing current active clients.

The cache key is the logical service name, for example CUSTOMER_SERVICE -> current GrpcClient. The instance ID belongs to the target and is used for failover/exclusion.

## Retirement and draining
The lifecycle is ACTIVE -> RETIRING -> CLOSED.

- ACTIVE: new operations may acquire a lease.
- RETIRING: no new operations may acquire a lease; existing operations may finish.
- CLOSED: the channel has been shut down.

Never replace retirement with unconditional immediate channel shutdown. A retryable failure on one thread does not prove other threads have stopped using the channel.

Every manager operation must follow tryAcquire() -> operation -> release(), with release in a finally block.

## Failover
When a retryable gRPC status occurs:
1. Record the failed instance ID in the current operation's exclusion set.
2. Serialize replacement using the per-service lock.
3. Replace the client only if the failed client is still authoritative.
4. Retire the old client.
5. Discover a replacement while excluding known failed instances.
6. Create the replacement channel and service adapter.
7. Install the replacement as authoritative.
8. Retry the operation subject to maxAttempts.

An older failed client must never replace a newer active client.

## Retry policy
Current retryable statuses are UNAVAILABLE, DEADLINE_EXCEEDED, and RESOURCE_EXHAUSTED.

Keep retries bounded with maxAttempts. Do not reintroduce an unbounded retry loop.

The exclusion set belongs to the individual application operation and accumulates failed instance IDs across attempts.

## Channel ownership
Keep the current rule: CustomerApi -> CustomerGrpcClient -> Channel A, and OrderApi -> OrderGrpcClient -> Channel B.

Even if Customer and Order resolve to the same host and port, do not automatically share a channel.

Do not introduce connection groups unless the architecture explicitly changes to support shared-channel ownership, coordinated retirement, and reference/lifecycle management.

## Generic boundary
Only GrpcClientFactory<T> should be generic. GrpcClientManager remains non-generic infrastructure.

The manager must not know generated protobuf/stub types. The service-specific adapter knows the generated stub.

## Discovery
Keep discovery behind ServiceDiscovery.lookup(serviceName, excludedInstanceIds).

The manager should not depend on whether discovery is static configuration, the external registry, or future Kubernetes/service discovery.

A new discovery mechanism should implement the existing abstraction rather than leak provider-specific behavior into the manager.

## Java 8 compatibility
Code in this folder must remain Java 8 compatible.

Avoid Java 9+ APIs and newer language features such as records, List.of, Set.of, Map.of, Map.copyOf, Stream.toList, local variable type inference, and newer pattern matching syntax.

Ordinary Java 8 collections, lambdas, method references, and concurrency primitives are fine.

## Production considerations
The repository examples currently use plaintext gRPC channels. Production should make channel creation configurable/injectable for TLS, mTLS, authority configuration, keepalive, interceptors, credentials, and channel options.

Do not embed these transport concerns into ServiceDiscovery.

## Concurrency cases to preserve
When modifying lifecycle code, verify:
1. Multiple threads use the same active client.
2. One thread triggers failover while other operations are in flight.
3. Two threads simultaneously fail on the same active client.
4. Cache eviction retires a client with active operations.
5. Adapter creation fails after a channel has been created.
6. No replacement instance is available.
7. Retry attempts are exhausted.
8. A newer client has already replaced the client that reported failure.
9. Application shutdown occurs while operations are in flight.

Core invariant: a client that is no longer authoritative must not receive new operations, but an operation that already acquired a lease must be allowed to finish before the channel is closed.

## Future Kubernetes migration
The application-facing proxy and manager should remain usable when discovery changes to Kubernetes. A future Kubernetes discovery implementation should fit behind ServiceDiscovery.

Do not couple application APIs to the external registry or static configuration.

## Validation checklist
Before considering a lifecycle change complete, verify:
- Java 8 compilation;
- no duplicate active-client state;
- no immediate shutdown of an in-flight client;
- every acquired client is released;
- replacement is identity-checked;
- retries are bounded;
- failed instance IDs are excluded across the current operation;
- channel creation failures clean up the channel;
- service-specific generated stubs remain outside generic infrastructure.