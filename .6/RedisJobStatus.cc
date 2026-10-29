/**
 * @file RedisJobStatus.cc
 * @brief Implementation of logical Redis job-status APIs.
 */

#include "RedisJobStatus.h"

#include <chrono>
#include <algorithm>
#include <cctype>
#include <iterator>
#include <stdexcept>
#include <utility>

using std::back_inserter;
using std::chrono::seconds;
using std::tolower;
using std::transform;
using std::exception;
using std::int64_t;
using std::invalid_argument;
using std::map;
using std::pair;
using std::runtime_error;
using std::stoll;
using std::string;
using std::vector;


namespace
{
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

string jobKey(const string& serverType, const string& jobId)
{
  return "job:{" + serverType + "}:" + jobId;
}

string typeJobsKey(const string& serverType)
{
  return "jobs:type:{" + serverType + "}";
}

string serverJobsKey(
    const string& serverType,
    const string& serverId)
{
  return "jobs:server:{" + serverType + "}:" + serverId;
}

string typeSummaryKey(const string& serverType)
{
  return "summary:type:{" + serverType + "}";
}

string serverSummaryKey(
    const string& serverType,
    const string& serverId)
{
  return "summary:server:{" + serverType + "}:" + serverId;
}

string serverRegistryKey(const string& serverType)
{
  return "servers:{registry}:type:" + serverType;
}

const string kServerTypesKey = "servers:{registry}:types";

string heartbeatKey(
    const string& serverType,
    const string& serverId)
{
  return "heartbeat:server:{" + serverType + "}:" + serverId;
}

using KeyList = vector<string>;
using ArgList = vector<string>;

template <typename Redis>
int64_t evalResult(
    Redis& redis,
    const string& script,
    const KeyList& keys,
    const ArgList& args)
{
  return redis.template eval<int64_t>(
      script, keys.begin(), keys.end(), args.begin(), args.end());
}

template <typename Redis>
map<string, int64_t> readCounters(
    Redis& redis,
    const string& key)
{
  vector<pair<string, string>> fields;
  redis.hgetall(key, back_inserter(fields));

  map<string, int64_t> counters;
  for (const auto& field : fields)
  {
    try
    {
      counters[field.first] = stoll(field.second);
    }
    catch (const exception&)
    {
      throw runtime_error(
          "RedisJobStatus: non-integer counter '" + field.first + "'");
    }
  }
  return counters;
}

template <typename Redis>
RedisJobStatus::Job readJob(Redis& redis, const string& key)
{
  vector<pair<string, string>> fields;
  redis.hgetall(key, back_inserter(fields));

  RedisJobStatus::Job job;
  for (const auto& field : fields)
  {
    if (field.first == "jobId")
    {
      job.jobId = field.second;
    }
    else if (field.first == "status")
    {
      job.status = field.second;
    }
    else if (field.first == "serverType")
    {
      job.serverType = field.second;
    }
    else if (field.first == "serverId")
    {
      job.serverId = field.second;
    }
    else
    {
      job.fields[field.first] = field.second;
    }
  }
  return job;
}
} // namespace

namespace
{
bool parseEnabledFlag(string value)
{
  transform(value.begin(), value.end(), value.begin(),
            [](unsigned char ch)
            {
              return static_cast<char>(tolower(ch));
            });

  if (value.empty() || value == "true" || value == "1" || value == "yes")
  {
    return true;
  }
  if (value == "false" || value == "0" || value == "no")
  {
    return false;
  }
  throw invalid_argument(
      "RedisJobStatus: redisJobStatusEnabled must be true/false, 1/0, or yes/no");
}

RedisJobStatus::Config defaultJobStatusConfigProvider()
{
  return RedisJobStatus::Config{parseEnabledFlag(
      get_reg_val("redisJobStatusEnabled"))};
}
} // namespace

RedisJobStatus::RedisJobStatus(ConfigProvider provider)
{
  const Config config = provider ? provider() : defaultJobStatusConfigProvider();
  m_enabled = config.enabled;
  if (m_enabled)
  {
    m_client = RedisClient::Instance();
  }
}

RedisJobStatus::Result RedisJobStatus::createPendingJob(
    const string& jobId,
    const string& serverType,
    const string& serverId,
    const map<string, string>& fields)
{
  if (!m_enabled) return Result::Disabled;
  validateIdentifier(jobId, "jobId");
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");

  KeyList keys = {
      jobKey(serverType, jobId),
      typeJobsKey(serverType),
      serverJobsKey(serverType, serverId),
      typeSummaryKey(serverType),
      serverSummaryKey(serverType, serverId)};

  ArgList args = {jobId, serverType, serverId};
  for (const auto& field : fields)
  {
    if (field.first == "jobId" || field.first == "status" ||
        field.first == "serverType" || field.first == "serverId")
    {
      continue;
    }
    args.push_back(field.first);
    args.push_back(field.second);
  }

  const auto result = m_client->execute(
      [&](auto& redis)
      {
        return evalResult(redis, kCreatePendingScript, keys, args);
      });
  return static_cast<Result>(result);
}

RedisJobStatus::Result RedisJobStatus::transitionJob(
    const string& jobId,
    const string& serverType,
    State newState)
{
  if (!m_enabled) return Result::Disabled;
  validateIdentifier(jobId, "jobId");
  validateIdentifier(serverType, "serverType", true);
  if (newState == State::Pending)
  {
    return Result::InvalidTransition;
  }

  const string serverIdKeyPrefix = "jobs:server:{" + serverType + "}:";
  const string summaryPrefix = "summary:server:{" + serverType + "}:";
  const auto job = m_client->execute(
      [&](auto& redis)
      {
        vector<pair<string, string>> fields;
        redis.hgetall(jobKey(serverType, jobId), back_inserter(fields));
        Job value;
        for (const auto& field : fields)
        {
          if (field.first == "serverId") value.serverId = field.second;
          if (field.first == "serverType") value.serverType = field.second;
        }
        return value;
      });

  if (job.serverId.empty() || job.serverType != serverType)
  {
    return Result::JobNotFound;
  }

  const string newStateName = stateName(newState);
  KeyList keys = {
      jobKey(serverType, jobId),
      typeJobsKey(serverType),
      serverIdKeyPrefix + job.serverId,
      typeSummaryKey(serverType),
      summaryPrefix + job.serverId};
  ArgList args = {jobId, serverType, job.serverId, newStateName};

  const auto result = m_client->execute(
      [&](auto& redis)
      {
        return evalResult(redis, kTransitionScript, keys, args);
      });
  return static_cast<Result>(result);
}

RedisJobStatus::Result RedisJobStatus::completeJob(
    const string& jobId,
    const string& serverType)
{
  if (!m_enabled) return Result::Disabled;
  validateIdentifier(jobId, "jobId");
  validateIdentifier(serverType, "serverType", true);

  const auto job = m_client->execute(
      [&](auto& redis)
      {
        vector<pair<string, string>> fields;
        redis.hgetall(jobKey(serverType, jobId), back_inserter(fields));
        Job value;
        for (const auto& field : fields)
        {
          if (field.first == "serverId") value.serverId = field.second;
          if (field.first == "serverType") value.serverType = field.second;
        }
        return value;
      });

  if (job.serverId.empty() || job.serverType != serverType)
  {
    return Result::JobNotFound;
  }

  KeyList keys = {
      jobKey(serverType, jobId),
      typeJobsKey(serverType),
      serverJobsKey(serverType, job.serverId),
      typeSummaryKey(serverType),
      serverSummaryKey(serverType, job.serverId)};
  ArgList args = {jobId, serverType, job.serverId};

  const auto result = m_client->execute(
      [&](auto& redis)
      {
        return evalResult(redis, kCompleteScript, keys, args);
      });
  return static_cast<Result>(result);
}

void RedisJobStatus::registerServer(
    const string& serverType,
    const string& serverId)
{
  if (!m_enabled) return;
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  const KeyList keys = {
      kServerTypesKey,
      serverRegistryKey(serverType)};
  const ArgList args = {serverType, serverId};

  // Both registry keys share the {registry} hash tag, so one script can
  // update them atomically in standalone Redis and Redis Cluster.
  m_client->execute(
      [&](auto& redis)
      {
        return evalResult(redis, kRegisterServerScript, keys, args);
      });
}

vector<string> RedisJobStatus::getServerTypes()
{
  if (!m_enabled) return {};
  return m_client->execute(
      [&](auto& redis)
      {
        vector<string> serverTypes;
        redis.smembers(kServerTypesKey, back_inserter(serverTypes));
        return serverTypes;
      });
}

void RedisJobStatus::refreshHeartbeat(
    const string& serverType,
    const string& serverId,
    int64_t ttlSeconds)
{
  if (!m_enabled) return;
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  if (ttlSeconds <= 0)
  {
    throw invalid_argument(
        "RedisJobStatus: heartbeat TTL must be positive");
  }

  const auto key = heartbeatKey(serverType, serverId);
  m_client->execute(
      [&](auto& redis)
      {
        redis.set(
            key,
            serverId,
            seconds(ttlSeconds));
      });
}

map<string, int64_t> RedisJobStatus::getTypeSummary(
    const string& serverType)
{
  if (!m_enabled) return {};
  validateIdentifier(serverType, "serverType", true);
  const auto key = typeSummaryKey(serverType);
  return m_client->execute(
      [&](auto& redis)
      {
        return readCounters(redis, key);
      });
}

vector<RedisJobStatus::ServerSummary>
RedisJobStatus::getServerSummaries(const string& serverType)
{
  if (!m_enabled) return {};
  validateIdentifier(serverType, "serverType", true);
  const auto registryKey = serverRegistryKey(serverType);

  return m_client->execute(
      [&](auto& redis)
      {
        vector<string> serverIds;
        redis.smembers(registryKey, back_inserter(serverIds));

        vector<ServerSummary> summaries;
        summaries.reserve(serverIds.size());
        for (const auto& serverId : serverIds)
        {
          summaries.push_back(
              ServerSummary{serverId,
                            readCounters(
                                redis,
                                serverSummaryKey(serverType, serverId))});
        }
        return summaries;
      });
}

vector<RedisJobStatus::Job> RedisJobStatus::getServerJobs(
    const string& serverType,
    const string& serverId)
{
  if (!m_enabled) return {};
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  const auto indexKey = serverJobsKey(serverType, serverId);

  return m_client->execute(
      [&](auto& redis)
      {
        vector<string> jobIds;
        redis.smembers(indexKey, back_inserter(jobIds));

        vector<Job> jobs;
        jobs.reserve(jobIds.size());
        for (const auto& jobId : jobIds)
        {
          jobs.push_back(readJob(redis, jobKey(serverType, jobId)));
        }
        return jobs;
      });
}

string RedisJobStatus::stateName(State state)
{
  switch (state)
  {
    case State::Pending:
      return "PENDING";
    case State::Active:
      return "ACTIVE";
    case State::Suspended:
      return "SUSPENDED";
  }
  throw invalid_argument("RedisJobStatus: unknown state");
}

void RedisJobStatus::validateIdentifier(
    const string& value,
    const char* name,
    bool isServerType)
{
  if (value.empty())
  {
    throw invalid_argument(
        string("RedisJobStatus: ") + name + " must not be empty");
  }
  if (isServerType &&
      (value.find('{') != string::npos ||
       value.find('}') != string::npos))
  {
    throw invalid_argument(
        "RedisJobStatus: serverType must not contain '{' or '}'");
  }
}
