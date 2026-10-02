# Java 8 gRPC Client — Safe Active/Retired Lifecycle

This folder is the production-oriented Java 8 variant of the gRPC discovery/client design.

## Core lifecycle

There is exactly one authoritative active client per logical service. The Guava cache is the single authoritative active-client store. There is no second active-client map.

CustomerApi singleton -> GrpcClientManager -> activeClients[CUSTOMER_SERVICE] -> GrpcClient -> ManagedChannel A

Retired clients remain alive only for existing operations and shut down after inFlight reaches zero.

## Active, retiring, closed

ACTIVE -> RETIRING -> CLOSED

ACTIVE accepts new operations. RETIRING accepts no new operations, but operations that already acquired a lease can finish. CLOSED means the underlying channel has been shut down.

This distinction matters because one thread can fail an RPC while other threads are still using the same channel.

## Per-operation leases

Before invoking a service adapter, the manager calls client.tryAcquire(). After the operation it always calls client.release(). GrpcClient tracks an atomic retired flag and an in-flight count.

A retiring client cannot accept new work. Its channel is shut down only after the final in-flight operation releases its lease. This supports one singleton application API being safely used concurrently by many application threads.

## Failover

Initial state: CUSTOMER_SERVICE -> customer-1 -> Channel A.

If an RPC returns a configured retryable status, the manager adds the failed instance ID to the current operation exclusion set, acquires the per-service replacement lock, verifies Channel A is still authoritative, invalidates the active cache entry, retires Channel A, discovers another instance, creates Channel B and its adapter, installs Channel B as authoritative, releases the failed operation's Channel A lease, and retries using the current active client.

Existing operations on Channel A can finish while Channel B handles new work.

## Concurrent failures

If two threads fail on the same active client, the first can install Channel B. The second sees that Channel B is already current and does not replace it again. The per-service lock serializes replacement and the identity check prevents an older failed client from replacing a newer active client.

## Bounded retries

The manager accepts maxAttempts. Example: new GrpcClientManager(serviceDiscovery, 100, 3).

The current operation maintains an exclusion set, for example: attempt 1 -> instance-1; attempt 2 excludes instance-1; attempt 3 excludes instance-1 and instance-2. This prevents an individual call from cycling indefinitely through the same instances.

## Cache semantics

The cache represents logical service name -> current active GrpcClient. Its removal listener calls client.retire(), not channel.shutdown(). Explicit invalidation and maximum-size eviction therefore mean that the client is no longer eligible for new work; retirement determines when the underlying channel can actually close.

## One channel per logical service

The design intentionally remains CustomerApi -> CustomerGrpcClient -> Channel A and OrderApi -> OrderGrpcClient -> Channel B. Even if both services resolve to the same endpoint, channels are not automatically shared.

Explicit connection groups are intentionally deferred. If channel sharing is introduced later, it should be an explicit ownership model rather than an inference from matching host/port values.

## Discovery remains independent

The manager depends only on the ServiceDiscovery abstraction with lookup(serviceName, excludedInstanceIds). Static discovery and registry discovery therefore use the same lifecycle code. A future Kubernetes implementation can use the same abstraction.

## Production-oriented changes

Compared with the original Java 8 implementation, this version adds:

- one authoritative active-client cache;
- explicit retiring/draining lifecycle;
- per-operation in-flight leases;
- identity-checked replacement;
- per-service replacement locks;
- bounded retry attempts;
- cumulative excluded instance IDs per operation;
- defensive copies for static discovery configuration;
- input validation;
- registry response validation;
- channel cleanup if service-adapter construction fails.

The sample still uses usePlaintext() to match the existing repository examples. Production deployment should make TLS/mTLS channel creation configurable rather than hard-code plaintext. The lifecycle model is independent of plaintext versus TLS.

## Spring singleton remains unchanged

The application-facing proxy remains a singleton CustomerApi. The proxy does not own a permanent channel. The manager owns discovery, active-client selection, replacement, and lifecycle.

## Files

java8-1/
  javareadme.md
  src/main/java/com/example/discovery/
    ServiceTarget.java
    ServiceDiscovery.java
    StaticServiceDiscovery.java
    RegistryServiceDiscovery.java
    GrpcClient.java
    GrpcClientFactory.java
    GrpcClientManager.java

## Design summary

The Java type does not determine the channel. The logical service name determines discovery. The discovered target determines the endpoint. The service-specific factory determines which adapter/stub is created on the channel.

There is one authoritative active client per logical service. Failover replaces that client without prematurely destroying a channel that still has in-flight work. The application-facing API remains a singleton and stays unaware of channel replacement.
