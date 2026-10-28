# Redis Job Status API (.5)

## RedisClient configuration injection

`RedisClient::SetConfigProvider()` lets production code adapt the existing `get_reg_val()` registry and lets examples/tests inject mock values. Install the provider **before** the first `RedisClient::GetClient()` call. Configuration is read once when the lazy singleton is first initialized; attempting to replace the provider after initialization begins throws.

Production integration example:

```cpp
RedisClient::SetConfigProvider([] {
  return RedisClient::Config{
      get_reg_val("redisUrl"),
      static_cast<bool>(get_reg_val("isRedisCluster"))};
});
// Only after the provider is installed:
RedisJobStatus status;
```

Without a provider, the client reads `REDIS_URL` and optional `REDIS_CLUSTER` environment variables. `REDIS_CLUSTER=true` or `1` enables cluster mode.

## Files

- `RedisClient.h/.cc`: client selection and injectable configuration.
- `RedisJobStatus.h/.cc`: job lifecycle and dashboard APIs.
- `examples/RedisWorkerExample.cc`: worker registration and heartbeat.
- `examples/RedisStatusCli.cc`: interactive type → instance → jobs drill-down.
- `examples/RedisWorkerLifecycleExample.cc`: end-to-end demonstration of every public RedisJobStatus API.

## Lifecycle example

```sh
./RedisWorkerLifecycleExample --serverType=query-server-type1
./RedisWorkerLifecycleExample --serverType=query-server-type1 --redisUrl=redis://127.0.0.1:6379 --cluster=false
```

The example mocks the values that would ordinarily come from `get_reg_val("redisUrl")` and `get_reg_val("isRedisCluster")`, then calls `registerServer`, `refreshHeartbeat`, `createPendingJob`, `getServerTypes`, `getTypeSummary`, `getServerSummaries`, `getServerJobs`, all valid `transitionJob` transitions, and `completeJob`. It uses a deterministic demo job ID, so rerunning against the same Redis data may report `JobAlreadyExists`; use a fresh server type or remove the prior demo record.

## Redis data structures

| Type | Key | Purpose |
|---|---|---|
| SET | `servers:types` | Global server-type discovery. |
| SET | `servers:type:{serverType}` | Registered instance IDs. |
| HASH | `summary:type:{serverType}` | Type-level status counters. |
| HASH | `summary:server:{serverType}:serverId` | Per-instance counters. |
| HASH | `job:{serverType}:jobId` | Job state, ownership and metadata. |
| SET | `jobs:type:{serverType}` | Current job IDs for a type. |
| SET | `jobs:server:{serverType}:serverId` | Current job IDs for an instance. |
| String + TTL | `heartbeat:server:{serverType}:serverId` | Heartbeat health signal. |

The `{serverType}` hash tag keeps lifecycle script keys in one Redis Cluster slot. The global `servers:types` registry is updated separately to avoid cross-slot scripts; reconcile registries if a process crashes between operations.

## Lua scripts: keys, arguments and tables

redis-plus-plus `eval` passes the Lua script, a key range and an argument range. `KEYS[1]`/ `KEYS[2]` are supplied Redis keys; `ARGV[1]`/ `ARGV[2]` are non-key arguments. Lua tables are one-indexed arrays. For example, `local fields = {'status', 'PENDING'}` and `redis.call('HSET', KEYS[1], unpack(fields))` expands the table into the arguments to `HSET`. Other calls include `SADD` for set membership, `HGET` for a field read, and `HINCRBY` for counters. Keep scripts short: they execute atomically on the serving node but block other commands while running.

## Build and validation

Requires C++17, redis-plus-plus and hiredis. Link the configuration provider only if adapting to your own registry. Build flags depend on your installation. These sources have not been compiled or integration-tested as part of this repository update; test against the exact client version and both standalone and cluster deployments before production.
