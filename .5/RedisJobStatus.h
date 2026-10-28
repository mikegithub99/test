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
#include <std::string>
#include <std::vector>

#include "RedisClient.h"

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
  enum class Result : std::int64_t
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
    std::string jobId;
    std::string status;
    std::string serverType;
    std::string serverId;
    std::map<std::string, std::string> fields;
  };

  struct ServerSummary
  {
    std::string serverId;
    std::map<std::string, std::int64_t> counters;
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
      const std::string& jobId,
      const std::string& serverType,
      const std::string& serverId,
      const std::map<std::string, std::string>& fields = {});

  /**
   * @brief Transitions a tracked job to ACTIVE or SUSPENDED.
   *
   * Valid transitions are PENDING->ACTIVE, ACTIVE->SUSPENDED, and
   * SUSPENDED->ACTIVE. Repeating the current state returns NoOp.
   */
  Result transitionJob(
      const std::string& jobId,
      const std::string& serverType,
      State newState);

  /**
   * @brief Removes a tracked job and updates indexes/counters atomically.
   */
  Result completeJob(
      const std::string& jobId,
      const std::string& serverType);

  /** @brief Registers a server instance under its type. */
  void registerServer(
      const std::string& serverType,
      const std::string& serverId);

  /** @brief Refreshes the server heartbeat with a positive TTL in seconds. */
  void refreshHeartbeat(
      const std::string& serverType,
      const std::string& serverId,
      std::int64_t ttlSeconds);

  /** @brief Discovers server types registered by workers. */
  std::vector<std::string> getServerTypes();

  /** @brief Returns the materialized summary for one server type. */
  std::map<std::string, std::int64_t> getTypeSummary(
      const std::string& serverType);

  /** @brief Returns registered instances and their summary counters. */
  std::vector<ServerSummary> getServerSummaries(
      const std::string& serverType);

  /**
   * @brief Reads current jobs for an instance.
   *
   * This returns the complete current set; callers should add bounded
   * pagination if an instance can own a large number of jobs.
   */
  std::vector<Job> getServerJobs(
      const std::string& serverType,
      const std::string& serverId);

private:
  static std::string stateName(State state);
  static void validateIdentifier(
      const std::string& value,
      const char* name,
      bool isServerType = false);

  std::shared_ptr<RedisClient> m_client;
};

#endif // REDIS_JOB_STATUS_H
