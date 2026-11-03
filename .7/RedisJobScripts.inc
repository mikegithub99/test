/**
 * @file RedisJobScripts.inc
 * @brief Embedded Lua scripts for RedisJobStatus.
 *
 * This file is included inside RedisJobStatus.cc's anonymous namespace.
 * Script bodies are C++ raw string literals so they ship with the executable
 * without a generator or runtime script-file lookup.
 *
 * Lua features used by these scripts:
 * - `local` and `function` for scoped variables and helper functions.
 * - `if`, `and`, `or`, and `not` for validation and state checks.
 * - `for`, `#`, and `table.insert` to collect optional field/value pairs.
 * - Tables and `unpack` to pass a variable-length HSET argument list.
 * - `string.lower` and `tonumber` to normalize and validate counters.
 * - `KEYS[n]` and `ARGV[n]` at the start of each script to assign descriptive
 *   local names to the explicit keys and arguments supplied by the C++ caller;
 *   Lua tables are one-indexed.
 * - `redis.call` to execute Redis commands and `return` to report results.
 *
 * Redis executes each script atomically with respect to other commands, but
 * scripts do not provide general rollback after a runtime error. Validate
 * key types and state before writing, and keep scripts short.
 */

/**
 * @brief Creates a PENDING job and updates its indexes and summary counters.
 *
 * jobKey job hash; typeJobsKey type job-ID set; serverJobsKey server job-ID set;
 * typeSummaryKey type summary hash; serverSummaryKey server summary hash.
 * ARGV[1..4] are jobId, serverType, serverId, and composite reference. Remaining ARGV values are
 * optional metadata field/value pairs.
 *
 * Returns 0 on success, 5 if the job already exists, and 4 if a Redis key
 * has an unexpected type.
 */
const char* const kCreatePendingScript = R"lua(
local jobKey = jobKey
local typeJobsKey = typeJobsKey
local serverJobsKey = serverJobsKey
local typeSummaryKey = typeSummaryKey
local serverSummaryKey = serverSummaryKey

local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', jobKey) == 1 then
  return 5
end
if not validtype(typeJobsKey, 'set') or
   not validtype(serverJobsKey, 'set') or
   not validtype(typeSummaryKey, 'hash') or
   not validtype(serverSummaryKey, 'hash') then
  return 4
end

local jobId = ARGV[1]
local serverType = ARGV[2]
local serverId = ARGV[3]
local reference = ARGV[4]
local metadataStart = 5
local fields = {'jobId', jobId, 'status', 'PENDING',
                'serverType', serverType, 'serverId', serverId}
for i = metadataStart, #ARGV, 2 do
  table.insert(fields, ARGV[i])
  table.insert(fields, ARGV[i + 1])
end

redis.call('HSET', jobKey, unpack(fields))
redis.call('SADD', typeJobsKey, reference)
redis.call('SADD', serverJobsKey, reference)
redis.call('HINCRBY', typeSummaryKey, 'pending', 1)
redis.call('HINCRBY', typeSummaryKey, 'total', 1)
redis.call('HINCRBY', serverSummaryKey, 'pending', 1)
redis.call('HINCRBY', serverSummaryKey, 'total', 1)
return 0
)lua";

/**
 * @brief Moves a job between supported lifecycle states and updates counters.
 *
 * jobKey job hash; typeJobsKey type job-ID set; serverJobsKey server job-ID set;
 * typeSummaryKey type summary hash; serverSummaryKey server summary hash.
 * ARGV[1..5] are jobId, serverType, serverId, target state, and composite reference.
 *
 * Returns 0 on success, 1 if the state is unchanged, 2 if the job is missing,
 * 3 for an invalid transition, and 4 if stored ownership, indexes, key types,
 * or counters are inconsistent.
 */
const char* const kTransitionScript = R"lua(
local jobKey = jobKey
local typeJobsKey = typeJobsKey
local serverJobsKey = serverJobsKey
local typeSummaryKey = typeSummaryKey
local serverSummaryKey = serverSummaryKey

local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', jobKey) == 0 then
  return 2
end
if not validtype(typeJobsKey, 'set') or
   not validtype(serverJobsKey, 'set') or
   not validtype(typeSummaryKey, 'hash') or
   not validtype(serverSummaryKey, 'hash') then
  return 4
end

local old = redis.call('HGET', jobKey, 'status')
local storedType = redis.call('HGET', jobKey, 'serverType')
local storedServer = redis.call('HGET', jobKey, 'serverId')
local jobId = ARGV[1]
local serverType = ARGV[2]
local serverId = ARGV[3]
local newState = ARGV[4]
local reference = ARGV[5]
if storedType ~= serverType or storedServer ~= serverId or not old then
  return 4
end
if not redis.call('SISMEMBER', typeJobsKey, reference) or
   not redis.call('SISMEMBER', serverJobsKey, reference) then
  return 4
end
if old == newState then
  return 1
end

local oldCounter = string.lower(old)
local newCounter = string.lower(newState)
local allowed =
  (old == 'PENDING' and newState == 'ACTIVE') or
  (old == 'ACTIVE' and newState == 'SUSPENDED') or
  (old == 'SUSPENDED' and newState == 'ACTIVE')
if not allowed then
  return 3
end

local function hasCounters(key)
  local oldValue = tonumber(redis.call('HGET', key, oldCounter) or '0')
  local total = tonumber(redis.call('HGET', key, 'total') or '0')
  return oldValue and total and oldValue >= 1 and total >= 1
end
if not hasCounters(typeSummaryKey) or not hasCounters(serverSummaryKey) then
  return 4
end

redis.call('HSET', jobKey, 'status', newState)
redis.call('HINCRBY', typeSummaryKey, oldCounter, -1)
redis.call('HINCRBY', typeSummaryKey, newCounter, 1)
redis.call('HINCRBY', serverSummaryKey, oldCounter, -1)
redis.call('HINCRBY', serverSummaryKey, newCounter, 1)
return 0
)lua";

/**
 * @brief Removes a job and decrements its state and total counters.
 *
 * jobKey job hash; typeJobsKey type job-ID set; serverJobsKey server job-ID set;
 * typeSummaryKey type summary hash; serverSummaryKey server summary hash.
 * ARGV[1..4] are jobId, serverType, serverId, and composite reference.
 *
 * Returns 0 on success, 2 if the job is missing, and 4 if stored ownership,
 * indexes, key types, state, or counters are inconsistent.
 */
const char* const kCompleteScript = R"lua(
local jobKey = jobKey
local typeJobsKey = typeJobsKey
local serverJobsKey = serverJobsKey
local typeSummaryKey = typeSummaryKey
local serverSummaryKey = serverSummaryKey

local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', jobKey) == 0 then
  return 2
end
if not validtype(typeJobsKey, 'set') or
   not validtype(serverJobsKey, 'set') or
   not validtype(typeSummaryKey, 'hash') or
   not validtype(serverSummaryKey, 'hash') then
  return 4
end

local status = redis.call('HGET', jobKey, 'status')
local storedType = redis.call('HGET', jobKey, 'serverType')
local storedServer = redis.call('HGET', jobKey, 'serverId')
local jobId = ARGV[1]
local serverType = ARGV[2]
local serverId = ARGV[3]
local reference = ARGV[4]
if storedType ~= serverType or storedServer ~= serverId or not status then
  return 4
end
if not redis.call('SISMEMBER', typeJobsKey, reference) or
   not redis.call('SISMEMBER', serverJobsKey, reference) then
  return 4
end

local counter = string.lower(status)
if status ~= 'PENDING' and status ~= 'ACTIVE' and status ~= 'SUSPENDED' then
  return 4
end
local function hasCounters(key)
  local count = tonumber(redis.call('HGET', key, counter) or '0')
  local total = tonumber(redis.call('HGET', key, 'total') or '0')
  return count and total and count >= 1 and total >= 1
end
if not hasCounters(typeSummaryKey) or not hasCounters(serverSummaryKey) then
  return 4
end

redis.call('HINCRBY', typeSummaryKey, counter, -1)
redis.call('HINCRBY', typeSummaryKey, 'total', -1)
redis.call('HINCRBY', serverSummaryKey, counter, -1)
redis.call('HINCRBY', serverSummaryKey, 'total', -1)
redis.call('SREM', typeJobsKey, reference)
redis.call('SREM', serverJobsKey, reference)
redis.call('DEL', jobKey)
return 0
)lua";

/**
 * @brief Adds a server type to global discovery and an instance to that type.
 *
 * jobKey is the global server-type set and typeJobsKey is the type-specific
 * server-ID set. Both keys must use the {registry} Redis Cluster hash tag.
 * ARGV[1] is serverType and ARGV[2] is serverId.
 *
 * Returns the SADD result for typeJobsKey. Returns a Redis error reply before
 * writing if either key exists with a type other than SET.
 */
const char* const kRegisterServerScript = R"lua(
local serverTypesKey = serverTypesKey
local typeServersKey = typeServersKey

local function validtype(key)
  local t = redis.call('TYPE', key).ok
  return t == 'none' or t == 'set'
end

-- Validate both keys before either write so a wrong-type key cannot leave
-- the two registries out of sync.
if not validtype(serverTypesKey) or not validtype(typeServersKey) then
  return redis.error_reply('wrong key type')
end

local serverType = ARGV[1]
local serverId = ARGV[2]
redis.call('SADD', serverTypesKey, serverType)
return redis.call('SADD', typeServersKey, serverId)
)lua";
