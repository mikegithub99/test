# gRPC vs REST for Internal Backend-to-Backend Communication

## Executive recommendation

For this system, keep **gRPC for internal backend-to-backend communication**.

REST is simpler when the goal is a conventional HTTP API exposed through a Kubernetes Service and network-level load balancing. However, that simplicity does not remove the underlying load-balancing requirement in a distributed backend system.

The important architectural question is not simply **gRPC vs REST**. It is:

> Where should service discovery, backend selection, load balancing, and retry happen?

For this architecture, the preferred answer is:

- **Kubernetes** owns service membership and endpoint lifecycle.
- **gRPC client-side load balancing** chooses among available backend instances when per-RPC balancing is required.
- **The retry layer** handles retryable failures and policy.
- **The application-facing adapter interface** remains stable and hides transport/discovery details.

This preserves the advantages of gRPC while giving the system the per-RPC distribution behavior desired for a horizontally scaled backend.

---

## 1. Why REST initially looks simpler

A typical REST deployment can look like:

```
Backend Service A
       |
       | HTTP
       v
Kubernetes Service
       |
   +---+---+---+
   |   |   |   |
 Pod Pod Pod Pod
```

The application calls one stable Service address. Kubernetes/network infrastructure selects a backend.

This is operationally simple:

- no generated gRPC stubs
- no custom gRPC NameResolver
- no EndpointSlice RBAC in the client
- no gRPC subchannel management in application infrastructure
- familiar HTTP tooling

For many systems, that is a good architecture.

But it does not mean REST inherently provides per-request load balancing. The actual behavior depends on HTTP version, connection reuse, connection pooling, and where the load balancer operates.

---

## 2. The important gRPC difference: HTTP/2 multiplexing

gRPC normally uses HTTP/2.

A gRPC channel is intended to be long-lived and can multiplex many independent RPCs over a small number of HTTP/2 connections:

```
             one long-lived HTTP/2 connection
                         |
              +----------+----------+
              |          |          |
             RPC 1      RPC 2      RPC 3
              |          |          |
              +----------+----------+
                         |
                       Pod A
```

If a network load balancer selects Pod A for the underlying TCP connection, many RPCs can continue to use Pod A.

Therefore:

> Network-level load balancing of a gRPC TCP connection is not automatically equivalent to per-RPC load balancing.

This is the main reason client-side gRPC load balancing is useful.

---

## 3. Per-RPC load balancing is a good fit for this system

The backend architecture is a distributed collection of services that make many independent calls to horizontally scaled services.

For that workload, per-RPC load balancing has useful properties:

- distributes work across currently available instances
- avoids relying on one long-lived HTTP/2 connection to represent all traffic from a client
- allows newly available instances to participate in traffic
- reacts more naturally to endpoint health and membership changes
- works well with horizontally scaled Kubernetes Deployments
- keeps load-balancing behavior close to the RPC abstraction

Conceptually:

```
Backend Service A
       |
 CustomerAdapter
       |
   Retry Proxy
       |
   gRPC Channel
       |
 client-side LB
       |
 +-----+-----+-----+
 |           |     |
Pod A       Pod B Pod C
```

The channel remains long-lived. The individual RPCs are selected against the available backend subchannels.

---

## 4. REST does not eliminate the same architectural concern

If REST is used:

```
Service A
   |
   | HTTP
   v
Network / Kubernetes LB
   |
 +--+--+--+
 |  |  |  |
 A  B  C
```

If gRPC uses only network-level LB:

```
Service A
   |
   | HTTP/2 + gRPC
   v
Network / Kubernetes LB
   |
 +--+--+--+
 |  |  |  |
 A  B  C
```

The network can load-balance both protocols.

The difference is that HTTP/2 multiplexing makes a long-lived gRPC connection particularly important. A single connection can carry a large amount of RPC traffic.

So the correct comparison is:

> **REST vs gRPC is not the same thing as network LB vs client-side/per-RPC LB.**

Those are separate architectural choices.

---

## 5. Why gRPC remains attractive for this backend system

### Strong service contracts

Protobuf gives a precise, language-neutral contract shared by the Java clients and C++17 servers.

### Generated client/server infrastructure

The C++ and Java gRPC ecosystems generate strongly typed service APIs from the same protocol definition.

### Efficient internal transport

Binary protobuf messages and HTTP/2 are well suited to high-volume service-to-service traffic.

### Streaming

gRPC provides first-class support for streaming RPCs. This is valuable if some backend interactions eventually become streaming rather than simple request/response calls.

### Explicit RPC semantics

Deadlines, status codes, metadata, streaming, and transport behavior are directly represented by the gRPC model.

### Good fit for C++

Because the backend is already C++17, gRPC provides a natural service implementation model.

---

## 6. Where REST is genuinely preferable

REST remains a strong choice when the service is primarily:

- public-facing
- consumed by browsers
- consumed by heterogeneous third-party clients
- intended to be manually explored with standard HTTP tooling
- naturally resource-oriented
- better represented by conventional HTTP semantics
- expected to integrate broadly with existing HTTP infrastructure

For those cases, REST's simplicity and ecosystem can outweigh gRPC's advantages.

That is different from the internal backend-to-backend use case here.

---

## 7. Recommended Kubernetes/gRPC architecture

The preferred architecture is:

```
                         Kubernetes
                             |
                     EndpointSlice data
                             |
                             v
                 gRPC Kubernetes Resolver
                             |
                             v
                    gRPC client-side LB
                       (round_robin)
                             |
              +--------------+--------------+
              |              |              |
              v              v              v
           Pod A          Pod B          Pod C
              ^              ^              ^
              |              |              |
              +------ gRPC RPCs ------------+
                             ^
                             |
                      Retry Proxy
                             ^
                             |
                    Application Adapter
```

The responsibilities are deliberately separated.

### Kubernetes

Knows which backend instances exist.

### Kubernetes-aware gRPC resolver

Converts Kubernetes endpoint membership into gRPC addresses.

### gRPC load balancer

Chooses an available backend/subchannel for each RPC.

### Retry proxy

Applies retry policy such as:

- `UNAVAILABLE`
- `DEADLINE_EXCEEDED`
- `RESOURCE_EXHAUSTED`

with bounded exponential backoff.

### Adapter

Keeps the application independent of generated protobuf classes and transport/discovery infrastructure.

---

## 8. Why not make every adapter responsible for this?

Avoid implementing separate discovery and load-balancing logic in:

- CustomerAdapter
- OrderAdapter
- PaymentAdapter
- InventoryAdapter
- ShippingAdapter
- etc.

Instead, make the infrastructure reusable:

```
CustomerAdapter
OrderAdapter
PaymentAdapter
InventoryAdapter
       |
       +---- common gRPC infrastructure
                    |
              discovery
                    |
                client LB
                    |
                  retry
```

This is especially important as the number of backend services grows.

---

## 9. Option B vs a custom client manager

There are two useful levels of sophistication.

### Option B: Kubernetes discovery + gRPC LB

Use Kubernetes EndpointSlices to discover individual backend endpoints and let gRPC's load-balancing infrastructure select among them.

This should be the preferred default when:

- per-RPC load balancing is wanted
- endpoint membership comes from Kubernetes
- ordinary round-robin behavior is sufficient
- the client should not contain business-specific endpoint-selection policy

### Custom manager / selector

Use a custom `GrpcClientManager` and endpoint selector when requirements go beyond ordinary gRPC LB, such as:

- mandatory selection of a different Pod after a failure
- weights
- least-work routing
- explicit endpoint exclusion
- custom circuit breakers
- service-specific routing rules
- custom connection pools

This is more flexible but also more application infrastructure to maintain.

The recommendation is therefore:

> Start with gRPC's native client-side LB and add a custom manager only when a concrete requirement cannot be expressed through the normal gRPC model.

---

## 10. Retry and load balancing are separate

This distinction is important.

**Load balancing answers:**

> Which available backend should receive this RPC?

**Retry answers:**

> What should happen if the RPC fails with a retryable status?

They should not be conflated.

For example:

```
RPC
 |
 +-- client-side LB selects Pod A
 |
 +-- Pod A -> UNAVAILABLE
 |
 +-- retry policy allows retry
 |
 +-- next RPC attempt
 |
 +-- LB selects an available subchannel
```

Ordinary gRPC round-robin does not necessarily guarantee that the retry goes to a different Pod. If mandatory failed-endpoint exclusion is a hard requirement, that is a reason to introduce additional endpoint-selection/failover logic.

---

## 11. Long-lived channels are still correct

Per-RPC load balancing does **not** mean creating a new gRPC channel for every RPC.

Do not do this:

```
RPC
 |
 +-- create channel
 +-- create stub
 +-- make call
 +-- destroy channel
```

Instead:

```
long-lived channel
       |
       +-- RPC 1
       +-- RPC 2
       +-- RPC 3
       +-- RPC 4
       +-- ...
```

The gRPC load balancer manages the backend subchannels/connections underneath the channel.

This preserves connection reuse while still allowing RPC-level backend selection.

---

## 12. What Kubernetes should and should not do

Kubernetes should be the source of truth for backend membership:

```
Deployment
   |
   +-- Pod A
   +-- Pod B
   +-- Pod C
```

EndpointSlices expose the current endpoint set.

The client-side gRPC layer can then make an application-level choice among those endpoints.

This is a useful separation:

> **Kubernetes answers "who is available?"**

> **gRPC answers "which available instance should handle this RPC?"**

That is a cleaner model for this system than making every application service independently discover and select Pods.

---

## 13. Comparison

| Concern | REST + network LB | gRPC + network LB | gRPC + client-side LB |
|---|---|---|---|
| Basic deployment simplicity | Excellent | Good | Moderate |
| Strong generated contract | Optional | Excellent | Excellent |
| C++17 integration | Good | Excellent | Excellent |
| Binary protocol | Usually no | Yes | Yes |
| HTTP/2 multiplexing | Usually no | Yes | Yes |
| Per-RPC backend selection | Not inherently guaranteed | Not inherently guaranteed | Yes, by design |
| Kubernetes endpoint discovery in client | No | No | Yes |
| Custom client infrastructure | Low | Low | Moderate |
| Streaming | Possible | Excellent | Excellent |
| Best fit for internal distributed backend | Good | Good | **Best fit here** |
| Best fit for public/browser APIs | **Often best** | Less common | Less common |

The table should not be interpreted as saying REST cannot perform per-request load balancing. It can. The point is that the desired behavior depends on the HTTP client and load-balancer architecture rather than REST itself.

---

## 14. Final recommendation

For this project:

### Keep gRPC for internal service-to-service communication.

Use:

1. **Protobuf/gRPC contracts** shared by Java and C++.
2. **Long-lived gRPC channels and generated stubs.**
3. **Kubernetes EndpointSlices** as the source of backend membership.
4. **A Kubernetes-aware gRPC NameResolver** to expose endpoint addresses.
5. **gRPC client-side `round_robin`** for ordinary per-RPC distribution.
6. **A reusable retry layer** above the service adapter.
7. **Custom endpoint selection/manager logic only if a concrete requirement exceeds normal gRPC LB.**

Use REST where the API itself benefits from HTTP/resource semantics or broad HTTP-client interoperability.

### Bottom line

The strongest argument for REST is **simplicity and interoperability**, not inherently better load balancing.

The strongest argument for gRPC in this system is that **the system is already a high-volume, strongly typed, C++/Java distributed backend**, and gRPC's client-side load-balancing model maps naturally onto the desired per-RPC behavior.

The architectural target should therefore be:

```
Application
    |
Stable adapter interface
    |
Retry policy
    |
Long-lived gRPC channel
    |
Kubernetes endpoint discovery
    |
gRPC client-side per-RPC LB
    |
+---+---+---+
|   |   |   |
Pod Pod Pod
```

That is the recommended direction for the internal backend architecture.
