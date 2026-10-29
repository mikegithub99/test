// Lua scripts used by RedisJobStatus. Included inside its anonymous namespace.
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
