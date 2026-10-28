# Redis Job Status API (.5)

This folder contains a C++17 implementation and examples for Redis job-status monitoring. It uses the existing `RedisClient` singleton to select standalone Redis or Redis Cluster from application configuration.

## Files

- `RedisClient.h/.cc`: configured Redis client wrapper.
- `RedisJobStatus.h/.cc`: logical job lifecycle APIs and dashboard reads.
- `examples/RedisWorkerExample.cc`: command-line worker registration and heartbeat.
- `examples/RedisStatusCli.cc`: interactive drill-down from server types to instances and jobs.

## Build assumptions

Requires C++17, redis-plus-plus and hiredis. `RedisClient.cc` calls the application-specific `get_reg_val("redisUrl")` and `get_reg_val("isRedisCluster")`; link your configuration provider and initialize it before first calling `RedisClient::GetClient()`. Adapt the marked configuration bootstrap in each example. Link flags depend on your redis-plus-plus installation.

## Redis data structures

| Redis type | Key | Purpose |
|---|---|---|
| SET | `servers:types` | Global registry for discovering server types. |
| SET | `servers:type:{serverType}` | Registered server IDs for a type. |
| HASH | `summary:type:{serverType}` | Materialized `pending`, `active`, `suspended`, `total` counters. |
| HASH | `summary:server:{serverType}:serverId` | Per-instance counters. |
| HASH | `job:{serverType}:jobId` | Source-of-truth job state, ownership and metadata. |
| SET | `jobs:type:{serverType}` | Current job IDs for a type. |
| SET | `jobs:server:{serverType}:serverId` | Current job IDs for one instance. |
| String + TTL | `heartbeat:server:{serverType}:serverId` | Heartbeat expiry indicates possible unhealthiness. |

The `{serverType}` substring is the Redis Cluster hash tag. All keys used together by a lifecycle Lua script share this tag and therefore the same slot. The global `servers:types` registry is deliberately a separate single-key command; it cannot be atomically updated together with arbitrary type-local keys in one cluster Lua script. A process crash between global type registration and local instance registration can temporarily leave partial discovery data; consider reconciliation.

### SET, HASH and String

A **SET** holds unique members, not fields. Use `SADD`, `SREM`, `SMEMBERS`, and `SISMEMBER` for type, instance, and job membership.

A **HASH** holds field/value pairs. Use `HSET`/ `HGET` for job fields, `HGETALL` to read a record, and `HINCRBY` for summary counters. Summary HASHes are materialized counters, while the job HASH is the authoritative record for an individual job.

A **String** with an expiry is suitable for heartbeat keys. Refresh it with `SET key value EX seconds` (the redis-plus-plus overload accepts an expiry option). Avoid `KEYS *` for production discovery; use `servers:types`.

## Lua scripts and C++ `eval`

redis-plus-plus `eval` passes a script, a sequence of Redis keys, and a sequence of non-key arguments. Inside Lua:

- `KEYS[1]`, `KEYS[2]`, ... refer to keys passed by the C++ caller. Every key touched by a script must be supplied in `KEYS`.
- `ARGV[1]`, `ARGV[2]`, ... refer to arguments such as job ID, server type and target status.
- Lua tables are arrays with **one-based indexes**. `local fields = {'jobId', jobId, 'status', 'PENDING'}` creates an array.
- `redis.call('HSET', KEYS[1], unpack(fields))` invokes `HSET`; `unpack(fields)` expands the table into positional arguments.
- `redis.call('SADD', KEYS[2], jobId)` adds a member to a SET.
- `redis.call('HINCRBY', KEYS[4], 'pending', 1)` increments a counter field.
- `redis.call('HGET', KEYS[1], 'status')` reads one field; `redis.call('SISMEMBER', KEYS[2], jobId)` validates index membership.
- `return 0` returns a result code to C++; document result codes and handle them explicitly.

Typical C++ shape:

```cpp
std::vector<std::string> keys = {jobKey, typeIndexKey, /* ... */};
std::vector<std::string> args = {jobId, serverType, serverId};
auto result = redis.eval<std::int64_t>(
    script, keys.begin(), keys.end(), args.begin(), args.end());
```

Lua execution is atomic relative to other commands on the serving node, but blocks that node while running. Keep scripts short. Atomicity does not imply the write has already been persisted or replicated to every replica.

## Example 1: start and register a worker

```sh
./RedisWorkerExample --serverType=query-server-type1
```

The example obtains a non-loopback IPv4 address where available, appends a five-letter uppercase alphabetic suffix (for example, `192.0.2.10-ABQXZ`), calls `registerServer(serverType, serverId)`, then refreshes a TTL heartbeat periodically. The suffix is a collision-reduction aid, not a globally unique UUID; use a full UUID if uniqueness is critical. Adapt `initializeApplicationConfiguration()` to your project.

## Example 2: command-line drill-down

```sh
./RedisStatusCli
```

The CLI lists all types from `getServerTypes()`, then displays per-instance counters from `getServerSummaries()`, then lists current job details from `getServerJobs()`. It is a small interactive example; for large sets, implement cursor-based pagination rather than loading every job at once.

## Logical APIs

- `createPendingJob(jobId, serverType, serverId, fields)`
- `transitionJob(jobId, serverType, State::Active|State::Suspended)`
- `completeJob(jobId, serverType)`
- `registerServer(serverType, serverId)`
- `refreshHeartbeat(serverType, serverId, ttlSeconds)`
- `getServerTypes()`
- `getTypeSummary(serverType)`
- `getServerSummaries(serverType)`
- `getServerJobs(serverType, serverId)`

## Before production

Compile against the exact pinned redis-plus-plus version and test both client variants. Test scripts on a real Redis/Valkey Cluster, hash-slot placement, duplicate and invalid transitions, counter/index invariants, failover, and registry reconciliation. These sources are not claimed to be compiled or integration-tested here.
