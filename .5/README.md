# Redis Job Status API (.5)

## RedisClient configuration injection

Pass an optional provider to `RedisClient::Instance()` on its first call. This keeps `RedisClient` small: it accepts a callable returning `RedisClient::Config`, while the default provider calls the host application's `get_reg_val("redisUrl")` and `get_reg_val("isRedisCluster")`.

Production usage:

```cpp
// Call after get_reg_val() is initialized and before constructing RedisJobStatus.
// Initialize the application registry first.
RedisJobStatus status; // default provider calls get_reg_val() automatically

auto client = RedisClient::Instance(); // optional direct access
```

Tests and examples can pass a provider returning deterministic mock values. The first `Instance()` call initializes the singleton; providers passed on later calls are ignored. The default provider accepts `true`/`false`, `1`/`0`, or `yes`/`no` for `isRedisCluster`.

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

| Type | Key pattern | Purpose | Sample data held |
|---|---|---|---|
| SET | `servers:{registry}:types` | Global server-type discovery. | `workers`, `schedulers` |
| SET | `servers:{registry}:type:serverType` | Registered instance IDs for one type. | For `workers`: `worker-01`, `worker-02` |
| HASH | `summary:type:{serverType}` | Type-level status counters. | `pending=2`, `active=3`, `suspended=1`, `total=6` |
| HASH | `summary:server:{serverType}:serverId` | Per-instance counters. | For `worker-01`: `pending=1`, `active=2`, `total=3` |
| HASH | `job:{serverType}:jobId` | Job state, ownership and metadata. | `jobId=job-100`, `status=ACTIVE`, `serverType=workers`, `serverId=worker-01`, plus custom fields |
| SET | `jobs:type:{serverType}` | Current job IDs for a type. | `job-100`, `job-101`, `job-102` |
| SET | `jobs:server:{serverType}:serverId` | Current job IDs for an instance. | For `worker-01`: `job-100`, `job-102` |
| String + TTL | `heartbeat:server:{serverType}:serverId` | Heartbeat health signal. | `worker-01` with a 30-second TTL |

The `{serverType}` hash tag keeps lifecycle script keys in one Redis Cluster slot. Both registry keys use the shared literal `{registry}` hash tag, so `registerServer()` can update the global type set and the type-specific instance set in one Lua script without a cross-slot error. This deliberately puts registry writes in one cluster slot; if registration volume becomes very high, that slot may become a hotspot. Redis scripts prevent other commands from interleaving during execution, but they are not general rollback transactions, so the registration script validates both key types before writing.

## Lua scripts: keys, arguments and tables

redis-plus-plus `eval` passes the Lua script, a key range and an argument range. `KEYS[1]`/ `KEYS[2]` are supplied Redis keys; `ARGV[1]`/ `ARGV[2]` are non-key arguments. Lua tables are one-indexed arrays. For example, `local fields = {'status', 'PENDING'}` and `redis.call('HSET', KEYS[1], unpack(fields))` expands the table into the arguments to `HSET`. Other calls include `SADD` for set membership, `HGET` for a field read, and `HINCRBY` for counters. Keep scripts short: they execute atomically on the serving node but block other commands while running.

## Build and validation

Requires C++17, redis-plus-plus and hiredis. The host application must provide `std::string get_reg_val(const std::string&)` and initialize its configuration registry before the first `RedisClient::Instance()` call. Build flags depend on your installation. These sources have not been compiled or integration-tested as part of this repository update; test against the exact client version and both standalone and cluster deployments before production.
