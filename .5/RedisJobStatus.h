/**
 * @file RedisJobStatus.h
 * @brief Logical job-status APIs backed by Redis/Redis Cluster.
 *
 * Requires C++17 and the RedisClient wrapper.
 */

#ifndef REDIS_JOB_STATUS_H
#define REDIS_JOB_STATUS_H

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "RedisClient.h"

using std::int64_t;
using std::map;
using std::shared_ptr;
using std::string;
using std::vector;

/**
 * @class RedisJobStatus
 * @brief Implements atomic job lifecycle operations and dashboard reads.
 *
 * Server types are tracked in the global SET servers:types; each type's
 * instances are tracked in servers:type:{serverType}.
 *
 * All lifecycle keys use the server type as their Redis Cluster hash tag.
 * The caller supplies serverType as a routing hint; Lua scripts validate
 * ownership against the stored job HASH before changing state.
 */
class RedisJobStatus final
{
public:
  enum class Result : int64_t
  {
    Success = 0,
    NoOp = 1,
    JobNotFound = 2,
    InvalidTransition = 3,
    InconsistentData = 4,
    JobAlreadyExists = 5
  };

  enum class State
  {
    Pending,
    Active,
    Suspended
  };

  struct Job
  {
    string jobId;
    string status;
    string serverType;
    string serverId;
    map<string, string> fields;
  };

  struct ServerSummary
  {
    string serverId;
    map<string, int64_t> counters;
  };

  RedisJobStatus();

  /**
   * @brief Creates a PENDING job and updates indexes and counters atomically.
   * @param jobId Unique job identifier within the server type.
   * @param serverType Hash-tag partition and owning server type.
   * @param serverId Owning server instance identifier.
   * @param fields Additional display metadata; reserved fields are overwritten.
   */
  Result createPendingJob(
      const string& jobId,
      const string& serverType,
      const string& serverId,
      const map<string, string>& fields = {});

  /**
   * @brief Transitions a tracked job to ACTIVE or SUSPENDED.
   *
   * Valid transitions are PENDING->ACTIVE, ACTIVE->SUSPENDED, and
   * SUSPENDED->ACTIVE. Repeating the current state returns NoOp.
   */
  Result transitionJob(
      const string& jobId,
      const string& serverType,
      State newState);

  /**
   * @brief Removes a tracked job and updates indexes/counters atomically.
   */
  Result completeJob(
      const string& jobId,
      const string& serverType);

  /** @brief Registers a server instance under its type. */
  void registerServer(
      const string& serverType,
      const string& serverId);

  /** @brief Refreshes the server heartbeat with a positive TTL in seconds. */
  void refreshHeartbeat(
      const string& serverType,
      const string& serverId,
      int64_t ttlSeconds);

  /** @brief Discovers server types registered by workers. */
  vector<string> getServerTypes();

  /** @brief Returns the materialized summary for one server type. */
  map<string, int64_t> getTypeSummary(
      const string& serverType);

  /** @brief Returns registered instances and their summary counters. */
  vector<ServerSummary> getServerSummaries(
      const string& serverType);

  /**
   * @brief Reads current jobs for an instance.
   *
   * This returns the complete current set; callers should add bounded
   * pagination if an instance can own a large number of jobs.
   */
  vector<Job> getServerJobs(
      const string& serverType,
      const string& serverId);

private:
  static string stateName(State state);
  static void validateIdentifier(
      const string& value,
      const char* name,
      bool isServerType = false);

  shared_ptr<RedisClient> m_client;
};

#endif // REDIS_JOB_STATUS_H
