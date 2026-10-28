/**
 * @file RedisWorkerLifecycleExample.cc
 * @brief Demonstrates every public RedisJobStatus API.
 *
 * Usage:
 *   ./RedisWorkerLifecycleExample --serverType=query-server-type1
 *       [--redisUrl=redis://127.0.0.1:6379] [--cluster=false]
 *
 * The default configuration provider below is a mock equivalent of the
 * application's get_reg_val("redisUrl") and get_reg_val("isRedisCluster").
 * Replace mockGetRegVal() with your real registry lookup for application use.
 */

#include "RedisJobStatus.h"
#include "RedisClient.h"

#include <cstdlib>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>

namespace
{
struct Options
{
  std::string serverType;
  std::string redisUrl = "redis://127.0.0.1:6379";
  bool cluster = false;
};

// Mock configuration registry for this standalone example.
std::string mockGetRegVal(const std::string& key, const Options& options)
{
  if (key == "redisUrl") return options.redisUrl;
  if (key == "isRedisCluster") return options.cluster ? "true" : "false";
  throw std::invalid_argument("Unknown mock configuration key: " + key);
}

Options parseArgs(int argc, char** argv)
{
  Options options;
  for (int i = 1; i < argc; ++i)
  {
    const std::string arg(argv[i]);
    if (arg.rfind("--serverType=", 0) == 0)
      options.serverType = arg.substr(13);
    else if (arg.rfind("--redisUrl=", 0) == 0)
      options.redisUrl = arg.substr(11);
    else if (arg.rfind("--cluster=", 0) == 0)
    {
      const auto value = arg.substr(10);
      options.cluster = value == "true" || value == "1";
    }
    else
      throw std::invalid_argument("Unknown argument: " + arg);
  }
  if (options.serverType.empty())
    throw std::invalid_argument(
        "Usage: RedisWorkerLifecycleExample "
        "--serverType=query-server-type1 "
        "[--redisUrl=redis://127.0.0.1:6379] [--cluster=false]");
  return options;
}

const char* resultName(RedisJobStatus::Result result)
{
  switch (result)
  {
    case RedisJobStatus::Result::Success: return "Success";
    case RedisJobStatus::Result::NoOp: return "NoOp";
    case RedisJobStatus::Result::JobNotFound: return "JobNotFound";
    case RedisJobStatus::Result::InvalidTransition: return "InvalidTransition";
    case RedisJobStatus::Result::InconsistentData: return "InconsistentData";
    case RedisJobStatus::Result::JobAlreadyExists: return "JobAlreadyExists";
  }
  return "UnknownResult";
}

void printCounters(const std::map<std::string, std::int64_t>& counters)
{
  for (const auto& item : counters)
    std::cout << "  " << item.first << "=" << item.second << '\n';
}
} // namespace

int main(int argc, char** argv)
{
  try
  {
    const Options options = parseArgs(argc, argv);

    // Inject configuration before RedisJobStatus/GetClient is constructed.
    RedisClient::SetConfigProvider([options]
    {
      const auto url = mockGetRegVal("redisUrl", options);
      const auto cluster = mockGetRegVal("isRedisCluster", options);
      return RedisClient::Config{
          url, cluster == "true" || cluster == "1"};
    });

    RedisJobStatus api;
    const std::string serverId = "lifecycle-example-" + std::to_string(
        static_cast<unsigned long long>(std::hash<std::string>{}(
            options.serverType + options.redisUrl)) % 1000000ULL);

    std::cout << "1) registerServer\n";
    api.registerServer(options.serverType, serverId);

    std::cout << "2) refreshHeartbeat\n";
    api.refreshHeartbeat(options.serverType, serverId, 60);

    const std::string jobId = "lifecycle-example-job-" + serverId;
    const std::map<std::string, std::string> metadata = {
        {"command", "demo"}, {"submittedBy", "RedisWorkerLifecycleExample"}};

    std::cout << "3) createPendingJob\n";
    auto result = api.createPendingJob(
        jobId, options.serverType, serverId, metadata);
    std::cout << "  result=" << resultName(result) << '\n';
    if (result != RedisJobStatus::Result::Success)
      throw std::runtime_error(
          "createPendingJob did not return Success; use a fresh serverType "
          "or remove the prior demo job");

    std::cout << "4) getServerTypes\n";
    for (const auto& type : api.getServerTypes())
      std::cout << "  " << type << '\n';

    std::cout << "5) getTypeSummary (PENDING expected)\n";
    printCounters(api.getTypeSummary(options.serverType));

    std::cout << "6) getServerSummaries\n";
    for (const auto& server : api.getServerSummaries(options.serverType))
    {
      std::cout << "  serverId=" << server.serverId << '\n';
      printCounters(server.counters);
    }

    std::cout << "7) getServerJobs (PENDING expected)\n";
    for (const auto& job : api.getServerJobs(options.serverType, serverId))
      std::cout << "  jobId=" << job.jobId << " status=" << job.status << '\n';

    std::cout << "8) transitionJob PENDING -> ACTIVE\n";
    result = api.transitionJob(
        jobId, options.serverType, RedisJobStatus::State::Active);
    std::cout << "  result=" << resultName(result) << '\n';

    std::cout << "9) transitionJob ACTIVE -> SUSPENDED\n";
    result = api.transitionJob(
        jobId, options.serverType, RedisJobStatus::State::Suspended);
    std::cout << "  result=" << resultName(result) << '\n';

    std::cout << "10) transitionJob SUSPENDED -> ACTIVE\n";
    result = api.transitionJob(
        jobId, options.serverType, RedisJobStatus::State::Active);
    std::cout << "  result=" << resultName(result) << '\n';

    std::cout << "11) completeJob\n";
    result = api.completeJob(jobId, options.serverType);
    std::cout << "  result=" << resultName(result) << '\n';

    std::cout << "12) final getTypeSummary\n";
    printCounters(api.getTypeSummary(options.serverType));

    std::cout << "13) final getServerJobs (job should be absent)\n";
    std::cout << "  jobs=" <<
        api.getServerJobs(options.serverType, serverId).size() << '\n';

    std::cout << "Demo complete. Server registry remains; heartbeat expires "
                 "after its TTL.\n";
    return EXIT_SUCCESS;
  }
  catch (const std::exception& ex)
  {
    std::cerr << "RedisWorkerLifecycleExample: " << ex.what() << '\n';
    return EXIT_FAILURE;
  }
}
