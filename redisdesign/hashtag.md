# Redis Job Status Design — Cluster Hash Tags

## Purpose

This document defines Redis key placement for the job-status design in `AGENTS.md`. The required Lua scripts atomically update a job record, its type and server membership indexes, and the corresponding type and server counters.

## Key-placement rule

**Every key touched by one lifecycle script must use the same Redis Cluster hash tag.** Use the server type as the partition key. Redis Cluster hashes only the text inside the first valid `{...}` pair; the surrounding key text does not affect the slot.

Use these canonical keys:

| Purpose | Key pattern | Redis data type |
|---|---|---|
| Job source-of-truth record | `job:{serverType}:jobId` | HASH |
| Current jobs for a type | `jobs:type:{serverType}` | SET |
| Current jobs for a server instance | `jobs:server:{serverType}:serverId` | SET |
| Registered instances for a type | `servers:type:{serverType}` | SET |
| Type counters | `summary:type:{serverType}` | HASH |
| Instance counters | `summary:server:{serverType}:serverId` | HASH |
| Instance heartbeat | `heartbeat:server:{serverType}:serverId` | String with TTL |

Replace `serverType`, `serverId`, and `jobId` with the actual identifier values. The literal examples are illustrative, not keys to use unchanged.

For a job owned by type `queryType1` and server `worker-01`, all keys use the hash tag `{queryType1}`. Consequently, the job HASH, both membership SETs, and both summary HASHes share one slot and can be accessed by one multi-key Lua script.

## Important routing consequence

The job key is partitioned by server type, not by job ID alone. A transition caller must therefore provide the server type as a **key-routing hint** so it can construct the job key. The Lua script must still read `serverType` and `serverId` from the stored job HASH and validate them before writing; never trust caller-supplied old state or ownership as authoritative.

If the caller does not know the server type, the application needs a reliable lookup/routing mechanism before it can invoke the transition script. A global job-ID-to-type lookup in a different hash slot cannot be atomically updated together with the type summary in a single Redis Cluster Lua script. Do not add such a cross-slot key to the lifecycle script.

## Identifier rules

- Define a canonical encoding for identifiers used in key names.
- Either prohibit `{` and `}` in server-type identifiers or encode them consistently so they cannot change hash-tag parsing.
- Use the same canonical server-type representation for every key in a partition.
- Include `serverType` in server-specific keys. This avoids relying on server IDs being globally unique and keeps the key in the correct slot.
- Do not build key names differently in the C++ worker and Java service.

## Script contract

For every lifecycle script:

1. Pass all keys it accesses through `KEYS`; use documented, stable key ordering.
2. Pass the routing server type and transition parameters through `ARGV`, where appropriate.
3. Validate that all expected keys are in the intended partition by construction; Redis Cluster will reject cross-slot multi-key operations.
4. Validate the job HASH, stored ownership, current state, allowed transition, and required metadata before any writes.
5. Check for inconsistent/missing membership and counter data according to the chosen recovery contract. Do not silently clamp negative counters to zero.
6. Apply counter deltas only when the state actually changes. Treat a duplicate request as a documented no-op where practical.
7. Return a documented result code for success, duplicate/no-op, missing job, invalid transition, and inconsistent data.

Redis Lua scripts execute atomically with respect to other commands on the serving node, but they block other commands on that node while running. Keep scripts short and bounded. Atomic execution does not guarantee that a write has been persisted to disk or replicated to every replica before a failover.

## Dashboard read path

The Java service can read one `summary:type:{serverType}` per known server type for the initial screen. On drill-down, it reads `servers:type:{serverType}` and the corresponding instance summaries. Job detail reads use the instance SET to get job IDs, then fetch the associated job HASHes. Batch or pipeline reads where supported; do not issue one browser request per job.

Because all keys for a type share a slot, a single type's data is colocated. Different types can distribute across slots, but a particularly busy type can become a hot partition. Measure traffic and growth before finalizing this partition size.

## Script loading and cluster behavior

- Keep script source version-controlled and test it independently.
- Use the client library's supported script API. If using `SCRIPT LOAD`/`EVALSHA`, handle `NOSCRIPT` by loading the script on the node that will execute it and retrying safely.
- In a cluster, do not assume a script loaded on one node is cached on every primary. Verify how the chosen redis-plus-plus version routes and retries script operations.
- Test scripts against a real Redis Cluster or Valkey cluster, not only a standalone development container.

## Validation checklist

- [ ] Every key in each lifecycle script uses the same `{serverType}` hash tag.
- [ ] A caller can construct the job key using the routing server type.
- [ ] The script verifies stored type and server ownership before modifying records.
- [ ] All supported transitions and completion-from-each-state are tested.
- [ ] Duplicate requests do not double-adjust counters.
- [ ] Invalid transitions and inconsistent data leave records unchanged.
- [ ] Type counters equal the sum of that type's instance counters after transitions.
- [ ] Live jobs appear in exactly one type index and one instance index.
- [ ] Failover, `NOSCRIPT`, stale heartbeat, recovery, and reconciliation paths are tested.
