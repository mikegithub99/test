# Redis Job Status Design — Agent Guidance

## Purpose

This directory documents the job-status monitoring design shared by C++ worker processes, Redis, a Java REST service, and the operator UI. Follow these conventions when implementing or changing this design.

## Architecture and UI read path

The UI must not query every worker for every job just to render the initial screen. C++ workers publish job lifecycle transitions to Redis. Redis stores job details, membership indexes, and precomputed counters. The Java REST service reads the counters for the initial screen and retrieves job details only when the operator drills down.

Expected navigation:
1. Initial screen: one rolled-up row per server type.
2. Click a server type: show the instances registered for that type and each instance's counters.
3. Click a server instance: list its current job IDs and fetch details for those jobs.

For approximately 50 server types, 2 instances per type, and around 1,000 jobs, these reads should remain bounded by the displayed rows until the operator asks for job details.

## Redis key model

Use a consistent, documented naming scheme. Example keys:

- `job:{jobId}` — Redis HASH containing `jobId`, `status`, `serverType`, `serverId`, timestamps, and any required display fields.
- `jobs:type:{serverType}` — Redis SET of current job IDs assigned to the server type.
- `jobs:server:{serverId}` — Redis SET of current job IDs assigned to that server instance.
- `servers:type:{serverType}` — Redis SET of server instance IDs registered under that type.
- `summary:type:{serverType}` — Redis HASH with integer fields `pending`, `active`, `suspended`, and `total`.
- `summary:server:{serverId}` — Redis HASH with integer fields `pending`, `active`, `suspended`, and `total`.
- `heartbeat:server:{serverId}` — expiring key refreshed periodically by the worker.

Server IDs must be globally unique across server types, or the key scheme must include server type in the server-specific key. Do not silently assume uniqueness if deployment naming does not guarantee it.

The job HASH is the source of truth for an individual job's current status and ownership. SETs are membership indexes, not records with fields. Summary HASHes are precomputed counters, not job indexes. The `total` counter means current tracked jobs and should equal `pending + active + suspended` if these are the only counted live states.

Completed jobs are removed from the current-job indexes and their job HASH is deleted, per the agreed design. If historical audit is needed, store it separately rather than keeping completed jobs in the current-job indexes.

## Lifecycle and counter rules

Supported live states:
- `PENDING`
- `ACTIVE`
- `SUSPENDED`

Normal transitions:
- New job: create HASH with `PENDING`; add job ID to both type and instance SETs; increment `pending` and `total` in both summary HASHes.
- `PENDING -> ACTIVE`: update job status; decrement `pending`, increment `active` in both summaries.
- `ACTIVE -> SUSPENDED`: update job status; decrement `active`, increment `suspended` in both summaries.
- `SUSPENDED -> ACTIVE`: update job status; decrement `suspended`, increment `active` in both summaries.
- Completion from any tracked state: decrement the counter for the job's previous state and decrement `total` in both summaries; remove the job ID from both indexes; delete the job HASH.

Only apply counter deltas when the stored state actually changes. Duplicate transition messages must not double-increment or double-decrement counters. Validate the current state and required metadata before modifying anything. Unexpected transitions should return a clear error/result and leave data unchanged.

## Atomicity: Lua scripts

Perform each logical transition with a Redis Lua script so the job HASH, both membership SETs (when relevant), and both summary HASHes change atomically from the perspective of other Redis clients.

Scripts should:
- Validate that the job exists when a transition requires an existing job.
- Read the previous status, server type, and server ID from Redis rather than trusting caller-supplied old state.
- Validate allowed transitions and required metadata before writes.
- Make duplicate requests idempotent where practical.
- Update both instance and type counters in the same script.
- Avoid negative counters; report inconsistent data rather than silently hiding it.
- Use stable, documented KEYS/ARGV conventions.
- Return a structured or clearly documented result for success, missing job, duplicate/no-op, and invalid transition.

All keys accessed by a script must be compatible with the deployment topology. In Redis Cluster, multi-key Lua scripts require the keys to be in the same hash slot; design key hash tags or the deployment layout accordingly. Do not assume that a script spanning arbitrary keys works in Redis Cluster.

Use `SCRIPT LOAD`/ `EVALSHA` or the client's supported script API where appropriate. Handle `NOSCRIPT` by loading/retrying the script. Keep script source version-controlled and test it independently.

## Server registration and heartbeats

Register an instance in `servers:type:{serverType}` when it starts, and remove it only according to the deployment's lifecycle policy. Refresh `heartbeat:server:{serverId}` periodically with a TTL. A missing heartbeat indicates that an instance may be unhealthy; it does not by itself prove that every job is failed or safe to reassign.

Define a recovery policy explicitly. A monitor/reconciler should detect stale instances and then, according to business semantics, mark jobs lost/failed, requeue recoverable jobs, or flag them for operator action. Apply each job's recovery transition through an idempotent atomic path so summary counters and indexes stay aligned. Avoid blindly subtracting all counts for a dead server without reconciling individual job records.

## Java REST service and UI

The Java REST service should expose endpoints equivalent to:
- `GET /jobSummary` — one row per server type from `summary:type:{serverType}`.
- `GET /jobSummary/{serverType}/servers` — instance IDs from `servers:type:{serverType}`, then their instance summary HASHes.
- `GET /jobs/{serverType}/{serverId}` — current job IDs from the instance SET, then job details for those IDs.

Use pipelining or batched reads for collections of hashes when supported by the client. Do not make one REST request per job from the browser. Validate path parameters and encode Redis key components consistently. Return numeric counters as JSON numbers, not strings where the API contract permits conversion.

If the list of server types is not otherwise configured, maintain a deliberate registry of known types; do not discover them by running `KEYS` in production. Avoid `KEYS` for scans; use explicit registries or `SCAN` for administrative reconciliation.

## Consistency, crash handling, and reconciliation

Lua makes the updates within one script atomic, but it does not make the dashboard a globally frozen snapshot across multiple independent reads. The UI may see a recent transition on one refresh and the next transition on another. This eventual view is normally acceptable for monitoring.

Counters are materialized data and can drift after operational mistakes, legacy writers, data loss, or bugs. Provide an administrative reconciliation job that recomputes counts from live job HASHes/indexes during a controlled process and reports discrepancies before repairing them. Do not use full reconciliation on every dashboard request.

Decide whether Redis persistence/replication and failover guarantees meet the required durability level. A successful write to a primary is not automatically a guarantee that every replica or disk has persisted it. Document the accepted loss window and recovery procedure.

## Operational and implementation safeguards

- Set sensible connection, command, and request timeouts.
- Apply bounded pagination to job-detail responses if a server can own many jobs.
- Include server health separately from job counts so a dead worker with stale jobs is visible.
- Track transition errors, stale heartbeats, counter mismatches, and script failures with metrics/logging.
- Add tests for every valid transition, invalid transition, duplicate request, missing job, completion from each live state, server crash recovery, and counter reconciliation.
- Test that after every transition: each live job is in exactly one type index and one instance index; the status-specific counters sum to total; and type counters equal the sum of their instance counters.
- Do not treat summary hashes as authoritative job records; use the job HASH to validate individual job state.

## Example dashboard data

A type summary may look like:

```text
summary:type:queryType1
  pending   5
  active    18
  suspended 2
  total     25
```

An instance summary may look like:

```text
summary:server:worker-01
  pending   2
  active    10
  suspended 1
  total     13
```

The initial screen reads only type summary HASHes. Job details are fetched only after an operator selects a specific server instance.
