/**
 * @file RedisStatusCli.cc
 * @brief Interactive drill-down from server types to instances and jobs.
 */

#include "RedisJobStatus.h"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <map>
#include <string>

namespace
{
void initializeApplicationConfiguration()
{
  // TODO: initialize the host application's configuration registry before constructing RedisJobStatus.
}

void printCounters(const std::map<std::string, std::int64_t>& counters)
{
  for (const auto& item : counters)
  {
    std::cout << "    " << item.first << ": " << item.second << '\n';
  }
}

std::size_t choose(std::size_t count)
{
  std::cout << "Choice (0 = back/exit): ";
  std::size_t value = 0;
  if (!(std::cin >> value) || value > count)
  {
    throw std::runtime_error("Invalid selection");
  }
  return value;
}
} // namespace

int main()
{
  try
  {
    initializeApplicationConfiguration();
    RedisJobStatus status;

    bool done = false;
    while (!done)
    {
      const auto types = status.getServerTypes();
      std::cout << "\n=== Server types ===\n";
      for (std::size_t i = 0; i < types.size(); ++i)
      {
        std::cout << i + 1 << ") " << types[i] << '\n';
        printCounters(status.getTypeSummary(types[i]));
      }
      std::cout << "0) Exit\n";
      const auto typeChoice = choose(types.size());
      if (typeChoice == 0)
      {
        break;
      }

      const auto& type = types[typeChoice - 1];
      bool backToTypes = false;
      while (!backToTypes)
      {
        const auto servers = status.getServerSummaries(type);
        std::cout << "\n=== Instances: " << type << " ===\n";
        for (std::size_t i = 0; i < servers.size(); ++i)
        {
          std::cout << i + 1 << ") " << servers[i].serverId << '\n';
          printCounters(servers[i].counters);
        }
        std::cout << "0) Back\n";
        const auto serverChoice = choose(servers.size());
        if (serverChoice == 0)
        {
          backToTypes = true;
          continue;
        }

        const auto& serverId = servers[serverChoice - 1].serverId;
        const auto jobs = status.getServerJobs(type, serverId);
        std::cout << "\n=== Jobs: " << type << " / " << serverId
                  << " (" << jobs.size() << ") ===\n";
        for (const auto& job : jobs)
        {
          std::cout << "jobId=" << job.jobId << " status=" << job.status
                    << " serverId=" << job.serverId << '\n';
          for (const auto& field : job.fields)
          {
            std::cout << "    " << field.first << ": "
                      << field.second << '\n';
          }
        }
        std::cout << "Press Enter to return to instances...";
        std::cin.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
        std::cin.get();
      }
    }
    return EXIT_SUCCESS;
  }
  catch (const std::exception& ex)
  {
    std::cerr << "RedisStatusCli: " << ex.what() << '\n';
    return EXIT_FAILURE;
  }
}
