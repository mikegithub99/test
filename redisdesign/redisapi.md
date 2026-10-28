# Redis Job Status — API Inventory

## Purpose and conventions

This document lists the Redis commands and redis-plus-plus C++ APIs expected for the design in `AGENTS.md`. It is an implementation checklist, not a claim that every signature is identical across all redis-plus-plus versions.

**Legend:** ✓ = expected to be available on that client type for the stated use; — = not needed by this design; ✓* = supported by the Redis command/client API in common redis-plus-plus versions, but verify the exact installed version and cluster routing behavior before relying on it.

| Design operation | Redis command / C++ API | Redis (`sw::redis::Redis`) | RedisCluster (`sw::redis::RedisCluster`) | Notes |
|---|---|:---:|:---:|---|
| Read job fields | `HGET` / `hget` | ✓ | ✓ | Read `status`, `serverType`, `serverId`, timestamps, and display fields. |
| Read a job record | `HGETALL` / `hgetall` | ✓ | ✓ | Job HASH is the source of truth. |
| Create/update job fields | `HSET` / `hset` | ✓ | ✓ | Store the job's current state and metadata. |
| Add job to type index | `SADD` / `sadd` | ✓ | ✓ | Normally called inside the create-job Lua script, not as a separate client command. |
| Add job to server index | `SADD` / `sadd` | ✓ | ✓ | Same atomic script as type index and counters. |
| Remove job from indexes | `SREM` / `srem` | ✓ | ✓ | Completion/recovery scripts. |
| List jobs for a type/server | `SMEMBERS` / `smembers` | ✓ | ✓ | For potentially large sets, prefer `SSCAN`/bounded pagination instead of returning an unbounded set. |
| Register server instance | `SADD` / `sadd` | ✓ | ✓ | Adds the instance ID to the type's registry. |
| Remove server instance | `SREM` / `srem` | ✓ | ✓ | Apply only according to the documented lifecycle policy. |
| Read counters | `HGETALL` / `hgetall` | ✓ | ✓ | Summary HASH fields are numeric strings at Redis protocol level; convert and validate in application code. |
| Adjust counters | `HINCRBY` / `hincrby` | ✓ | ✓ | Perform inside the lifecycle Lua script; validate before writes so counters cannot go negative. |
| Refresh heartbeat with TTL | `SET key value EX seconds` / `set` with expiry options | ✓ | ✓ | Use an explicit TTL; confirm the exact options overload for the installed client version. |
| Check heartbeat/job existence | `EXISTS` / `exists` | ✓ | ✓ | A missing heartbeat signals possible unhealthiness, not proof that jobs have failed. |
| Delete completed job record | `DEL` / `del` | ✓ | ✓ | Delete in the same completion script as index and counter updates. |
| Set expiry / inspect TTL | `EXPIRE`, `TTL` / `expire`, `ttl` | ✓ | ✓ | Heartbeat refresh can instead use `SET` with an expiry. |
| Execute atomic lifecycle script | `EVAL` / `eval` | ✓* | ✓* | All script keys must share the same hash slot in Redis Cluster. Pass every accessed key explicitly in `KEYS`. |
| Load script | `SCRIPT LOAD` / `script_load` | ✓* | ✓* | Cluster loading/caching behavior must be verified for the exact library version and server topology. |
| Execute cached script | `EVALSHA` / `evalsha` | ✓* | ✓* | Handle `NOSCRIPT`; ensure the script is loaded on the node that will execute it. |
| Batch independent reads | Pipeline / pipeline API | ✓* | ✓* | Verify the exact redis-plus-plus pipeline support and cluster routing behavior; do not assume one pipeline can span arbitrary slots. |
| Enumerate all keys | `KEYS` | Avoid | Avoid | Never use `KEYS` for production dashboard discovery. Maintain an explicit registry; use `SCAN` only for administrative reconciliation. |
| Reconcile counts | `SCAN` plus HASH/index reads | ✓* | ✓* | Administrative process only; cluster-wide scanning needs per-node/cluster-aware handling. |

## Logical APIs to expose from the C++ application

Keep Redis commands that must change together inside version-controlled Lua scripts. The application-facing layer should expose logical operations rather than asking every caller to assemble several commands:

| Suggested logical operation | Purpose | Atomic Lua script? |
|---|---|:---:|
| `createPendingJob(job, serverType)` | Create the job HASH, add both membership entries, increment pending and total counters in both summaries | Yes |
| `transitionJob(jobId, serverType, newState)` | Validate stored state/ownership, then apply a valid state transition and counter deltas | Yes |
| `completeJob(jobId, serverType)` | Decrement the prior state and total counters, remove both index entries, delete the job HASH | Yes |
| `registerServer(serverType, serverId)` | Add the server instance to the type registry | Usually one command; use a script if coupled changes are added |
| `refreshHeartbeat(serverType, serverId, ttl)` | Refresh the expiring heartbeat key | Usually one `SET` with TTL |
| `removeServer(serverType, serverId)` | Apply the chosen registration/lifecycle policy | Depends on policy; do not blindly subtract all job counters |
| `getTypeSummary(serverType)` | Read a type summary HASH | No |
| `getServerSummaries(serverType)` | Read registered server IDs and their summary HASHes | No; batch/pipeline where supported |
| `getServerJobs(serverType, serverId, page)` | List current job IDs and fetch their details | No; use bounded pagination for large result sets |
| `reconcileType(serverType)` | Compare job records/indexes with materialized counters and report discrepancies | Administrative workflow |

The `serverType` argument in the job APIs is a **routing hint** needed to construct keys tagged with `{serverType}`. The script must verify the stored job's `serverType` and `serverId`; caller input is not authoritative for the old state or ownership.

## C++ wrapper considerations

The existing wrapper selects `sw::redis::Redis` or `sw::redis::RedisCluster` at runtime. These APIs have substantial overlap, but templates, overloads, pipeline types, and cluster-specific behavior may differ by redis-plus-plus version. Compile each operation against both client alternatives; test scripts and retries against a real cluster.

With the current `std::variant` design, a visitor passed to `RedisClient::execute(...)` must be well-formed for both alternatives unless it uses type-specific compile-time branching. Keep script source and argument/key ordering independent of the concrete client type where possible.

## Required verification before implementation is considered complete

- [ ] Build all used APIs against the project's pinned redis-plus-plus version for both client types.
- [ ] Test `EVAL` with the full key list against standalone Redis/Valkey and cluster Redis/Valkey.
- [ ] Test same-slot keys and confirm no `CROSSSLOT` error.
- [ ] Test `NOSCRIPT` recovery after connecting to a primary that does not have the script cached.
- [ ] Confirm pipeline behavior and error handling for the selected cluster client version.
- [ ] Verify command/request timeouts and connection options for both client types.
- [ ] Confirm all lifecycle scripts preserve the counter/index invariants described in `AGENTS.md`.
