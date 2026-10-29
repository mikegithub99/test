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
 * - `KEYS[n]` and `ARGV[n]` to access explicit keys and arguments supplied
 *   by the C++ caller; Lua tables are one-indexed.
 * - `redis.call` to execute Redis commands and `return` to report results.
 *
 * Redis executes each script atomically with respect to other commands, but
 * scripts do not provide general rollback after a runtime error. Validate
 * key types and state before writing, and keep scripts short.
 */

/**
 * @brief Creates a PENDING job and updates its indexes and summary counters.
 *
 * KEYS[1] job hash; KEYS[2] type job-ID set; KEYS[3] server job-ID set;
 * KEYS[4] type summary hash; KEYS[5] server summary hash.
 * ARGV[1..3] are jobId, serverType, serverId. Remaining ARGV values are
 * optional metadata field/value pairs.
 *
 * Returns 0 on success, 5 if the job already exists, and 4 if a Redis key
 * has an unexpected type.
 */
const char* const kCreatePendingScript = R"lua(
local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', KEYS[1]) == 1 then
  return 5
end
if not validtype(KEYS[2], 'set') or
   not validtype(KEYS[3], 'set') or
   not validtype(KEYS[4], 'hash') or
   not validtype(KEYS[5], 'hash') then
  return 4
end

local jobId = ARGV[1]
local serverType = ARGV[2]
local serverId = ARGV[3]
local fields = {'jobId', jobId, 'status', 'PENDING',
                'serverType', serverType, 'serverId', serverId}
for i = 4, #ARGV, 2 do
  table.insert(fields, ARGV[i])
  table.insert(fields, ARGV[i + 1])
end

redis.call('HSET', KEYS[1], unpack(fields))
redis.call('SADD', KEYS[2], jobId)
redis.call('SADD', KEYS[3], jobId)
redis.call('HINCRBY', KEYS[4], 'pending', 1)
redis.call('HINCRBY', KEYS[4], 'total', 1)
redis.call('HINCRBY', KEYS[5], 'pending', 1)
redis.call('HINCRBY', KEYS[5], 'total', 1)
return 0
)lua";

/**
 * @brief Moves a job between supported lifecycle states and updates counters.
 *
 * KEYS[1] job hash; KEYS[2] type job-ID set; KEYS[3] server job-ID set;
 * KEYS[4] type summary hash; KEYS[5] server summary hash.
 * ARGV[1..4] are jobId, serverType, serverId, and target state.
 *
 * Returns 0 on success, 1 if the state is unchanged, 2 if the job is missing,
 * 3 for an invalid transition, and 4 if stored ownership, indexes, key types,
 * or counters are inconsistent.
 */
const char* const kTransitionScript = R"lua(
local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', KEYS[1]) == 0 then
  return 2
end
if not validtype(KEYS[2], 'set') or
   not validtype(KEYS[3], 'set') or
   not validtype(KEYS[4], 'hash') or
   not validtype(KEYS[5], 'hash') then
  return 4
end

local old = redis.call('HGET', KEYS[1], 'status')
local storedType = redis.call('HGET', KEYS[1], 'serverType')
local storedServer = redis.call('HGET', KEYS[1], 'serverId')
local newState = ARGV[4]
if storedType ~= ARGV[2] or storedServer ~= ARGV[3] or not old then
  return 4
end
if not redis.call('SISMEMBER', KEYS[2], ARGV[1]) or
   not redis.call('SISMEMBER', KEYS[3], ARGV[1]) then
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
if not hasCounters(KEYS[4]) or not hasCounters(KEYS[5]) then
  return 4
end

redis.call('HSET', KEYS[1], 'status', newState)
redis.call('HINCRBY', KEYS[4], oldCounter, -1)
redis.call('HINCRBY', KEYS[4], newCounter, 1)
redis.call('HINCRBY', KEYS[5], oldCounter, -1)
redis.call('HINCRBY', KEYS[5], newCounter, 1)
return 0
)lua";

/**
 * @brief Removes a job and decrements its state and total counters.
 *
 * KEYS[1] job hash; KEYS[2] type job-ID set; KEYS[3] server job-ID set;
 * KEYS[4] type summary hash; KEYS[5] server summary hash.
 * ARGV[1..3] are jobId, serverType, and serverId.
 *
 * Returns 0 on success, 2 if the job is missing, and 4 if stored ownership,
 * indexes, key types, state, or counters are inconsistent.
 */
const char* const kCompleteScript = R"lua(
local function keytype(key)
  return redis.call('TYPE', key).ok
end
local function validtype(key, expected)
  local t = keytype(key)
  return t == 'none' or t == expected
end

if redis.call('EXISTS', KEYS[1]) == 0 then
  return 2
end
if not validtype(KEYS[2], 'set') or
   not validtype(KEYS[3], 'set') or
   not validtype(KEYS[4], 'hash') or
   not validtype(KEYS[5], 'hash') then
  return 4
end

local status = redis.call('HGET', KEYS[1], 'status')
local storedType = redis.call('HGET', KEYS[1], 'serverType')
local storedServer = redis.call('HGET', KEYS[1], 'serverId')
if storedType ~= ARGV[2] or storedServer ~= ARGV[3] or not status then
  return 4
end
if not redis.call('SISMEMBER', KEYS[2], ARGV[1]) or
   not redis.call('SISMEMBER', KEYS[3], ARGV[1]) then
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
if not hasCounters(KEYS[4]) or not hasCounters(KEYS[5]) then
  return 4
end

redis.call('HINCRBY', KEYS[4], counter, -1)
redis.call('HINCRBY', KEYS[4], 'total', -1)
redis.call('HINCRBY', KEYS[5], counter, -1)
redis.call('HINCRBY', KEYS[5], 'total', -1)
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('SREM', KEYS[3], ARGV[1])
redis.call('DEL', KEYS[1])
return 0
)lua";

/**
 * @brief Adds a server type to global discovery and an instance to that type.
 *
 * KEYS[1] is the global server-type set and KEYS[2] is the type-specific
 * server-ID set. Both keys must use the {registry} Redis Cluster hash tag.
 * ARGV[1] is serverType and ARGV[2] is serverId.
 *
 * Returns the SADD result for KEYS[2]. Returns a Redis error reply before
 * writing if either key exists with a type other than SET.
 */
const char* const kRegisterServerScript = R"lua(
local function validtype(key)
  local t = redis.call('TYPE', key).ok
  return t == 'none' or t == 'set'
end

-- Validate both keys before either write so a wrong-type key cannot leave
-- the two registries out of sync.
if not validtype(KEYS[1]) or not validtype(KEYS[2]) then
  return redis.error_reply('wrong key type')
end

redis.call('SADD', KEYS[1], ARGV[1])
return redis.call('SADD', KEYS[2], ARGV[2])
)lua";
