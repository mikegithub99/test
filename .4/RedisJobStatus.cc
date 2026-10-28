/**
 * @file RedisJobStatus.cc
 * @brief Implementation of logical Redis job-status APIs.
 */

#include "RedisJobStatus.h"

#include <chrono>
#include <iterator>
#include <stdexcept>
#include <utility>

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
local t = redis.call('TYPE', KEYS[1]).ok
if t ~= 'none' and t ~= 'set' then return redis.error_reply('wrong key type') end
return redis.call('SADD', KEYS[1], ARGV[1])
)lua";

std::string jobKey(const std::string& serverType, const std::string& jobId)
{
  return "job:{" + serverType + "}:" + jobId;
}

std::string typeJobsKey(const std::string& serverType)
{
  return "jobs:type:{" + serverType + "}";
}

std::string serverJobsKey(
    const std::string& serverType,
    const std::string& serverId)
{
  return "jobs:server:{" + serverType + "}:" + serverId;
}

std::string typeSummaryKey(const std::string& serverType)
{
  return "summary:type:{" + serverType + "}";
}

std::string serverSummaryKey(
    const std::string& serverType,
    const std::string& serverId)
{
  return "summary:server:{" + serverType + "}:" + serverId;
}

std::string serverRegistryKey(const std::string& serverType)
{
  return "servers:type:{" + serverType + "}";
}

std::string heartbeatKey(
    const std::string& serverType,
    const std::string& serverId)
{
  return "heartbeat:server:{" + serverType + "}:" + serverId;
}

using KeyList = std::vector<std::string>;
using ArgList = std::vector<std::string>;

template <typename Redis>
std::int64_t evalResult(
    Redis& redis,
    const std::string& script,
    const KeyList& keys,
    const ArgList& args)
{
  return redis.template eval<std::int64_t>(
      script, keys.begin(), keys.end(), args.begin(), args.end());
}

template <typename Redis>
std::map<std::string, std::int64_t> readCounters(
    Redis& redis,
    const std::string& key)
{
  std::vector<std::pair<std::string, std::string>> fields;
  redis.hgetall(key, std::back_inserter(fields));

  std::map<std::string, std::int64_t> counters;
  for (const auto& field : fields)
  {
    try
    {
      counters[field.first] = std::stoll(field.second);
    }
    catch (const std::exception&)
    {
      throw std::runtime_error(
          "RedisJobStatus: non-integer counter '" + field.first + "'");
    }
  }
  return counters;
}

template <typename Redis>
RedisJobStatus::Job readJob(Redis& redis, const std::string& key)
{
  std::vector<std::pair<std::string, std::string>> fields;
  redis.hgetall(key, std::back_inserter(fields));

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

RedisJobStatus::RedisJobStatus()
    : m_client(RedisClient::GetClient())
{
}

RedisJobStatus::Result RedisJobStatus::createPendingJob(
    const std::string& jobId,
    const std::string& serverType,
    const std::string& serverId,
    const std::map<std::string, std::string>& fields)
{
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
    const std::string& jobId,
    const std::string& serverType,
    State newState)
{
  validateIdentifier(jobId, "jobId");
  validateIdentifier(serverType, "serverType", true);
  if (newState == State::Pending)
  {
    return Result::InvalidTransition;
  }

  const std::string serverIdKeyPrefix = "jobs:server:{" + serverType + "}:";
  const std::string summaryPrefix = "summary:server:{" + serverType + "}:";
  const auto job = m_client->execute(
      [&](auto& redis)
      {
        std::vector<std::pair<std::string, std::string>> fields;
        redis.hgetall(jobKey(serverType, jobId), std::back_inserter(fields));
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

  const std::string newStateName = stateName(newState);
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
    const std::string& jobId,
    const std::string& serverType)
{
  validateIdentifier(jobId, "jobId");
  validateIdentifier(serverType, "serverType", true);

  const auto job = m_client->execute(
      [&](auto& redis)
      {
        std::vector<std::pair<std::string, std::string>> fields;
        redis.hgetall(jobKey(serverType, jobId), std::back_inserter(fields));
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
    const std::string& serverType,
    const std::string& serverId)
{
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  const KeyList keys = {serverRegistryKey(serverType)};
  const ArgList args = {serverId};

  m_client->execute(
      [&](auto& redis)
      {
        return evalResult(redis, kRegisterServerScript, keys, args);
      });
}

void RedisJobStatus::refreshHeartbeat(
    const std::string& serverType,
    const std::string& serverId,
    std::int64_t ttlSeconds)
{
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  if (ttlSeconds <= 0)
  {
    throw std::invalid_argument(
        "RedisJobStatus: heartbeat TTL must be positive");
  }

  const auto key = heartbeatKey(serverType, serverId);
  m_client->execute(
      [&](auto& redis)
      {
        redis.set(
            key,
            serverId,
            std::chrono::seconds(ttlSeconds));
      });
}

std::map<std::string, std::int64_t> RedisJobStatus::getTypeSummary(
    const std::string& serverType)
{
  validateIdentifier(serverType, "serverType", true);
  const auto key = typeSummaryKey(serverType);
  return m_client->execute(
      [&](auto& redis)
      {
        return readCounters(redis, key);
      });
}

std::vector<RedisJobStatus::ServerSummary>
RedisJobStatus::getServerSummaries(const std::string& serverType)
{
  validateIdentifier(serverType, "serverType", true);
  const auto registryKey = serverRegistryKey(serverType);

  return m_client->execute(
      [&](auto& redis)
      {
        std::vector<std::string> serverIds;
        redis.smembers(registryKey, std::back_inserter(serverIds));

        std::vector<ServerSummary> summaries;
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

std::vector<RedisJobStatus::Job> RedisJobStatus::getServerJobs(
    const std::string& serverType,
    const std::string& serverId)
{
  validateIdentifier(serverType, "serverType", true);
  validateIdentifier(serverId, "serverId");
  const auto indexKey = serverJobsKey(serverType, serverId);

  return m_client->execute(
      [&](auto& redis)
      {
        std::vector<std::string> jobIds;
        redis.smembers(indexKey, std::back_inserter(jobIds));

        std::vector<Job> jobs;
        jobs.reserve(jobIds.size());
        for (const auto& jobId : jobIds)
        {
          jobs.push_back(readJob(redis, jobKey(serverType, jobId)));
        }
        return jobs;
      });
}

std::string RedisJobStatus::stateName(State state)
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
  throw std::invalid_argument("RedisJobStatus: unknown state");
}

void RedisJobStatus::validateIdentifier(
    const std::string& value,
    const char* name,
    bool isServerType)
{
  if (value.empty())
  {
    throw std::invalid_argument(
        std::string("RedisJobStatus: ") + name + " must not be empty");
  }
  if (isServerType &&
      (value.find('{') != std::string::npos ||
       value.find('}') != std::string::npos))
  {
    throw std::invalid_argument(
        "RedisJobStatus: serverType must not contain '{' or '}'");
  }
}
