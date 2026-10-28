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

using std::cerr;
using std::cin;
using std::cout;
using std::exception;
using std::int64_t;
using std::map;
using std::numeric_limits;
using std::runtime_error;
using std::size_t;
using std::streamsize;
using std::string;


namespace
{
void initializeApplicationConfiguration()
{
  // TODO: initialize the host application's configuration registry before constructing RedisJobStatus.
}

void printCounters(const map<string, int64_t>& counters)
{
  for (const auto& item : counters)
  {
    cout << "    " << item.first << ": " << item.second << '\n';
  }
}

size_t choose(size_t count)
{
  cout << "Choice (0 = back/exit): ";
  size_t value = 0;
  if (!(cin >> value) || value > count)
  {
    throw runtime_error("Invalid selection");
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
      cout << "\n=== Server types ===\n";
      for (size_t i = 0; i < types.size(); ++i)
      {
        cout << i + 1 << ") " << types[i] << '\n';
        printCounters(status.getTypeSummary(types[i]));
      }
      cout << "0) Exit\n";
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
        cout << "\n=== Instances: " << type << " ===\n";
        for (size_t i = 0; i < servers.size(); ++i)
        {
          cout << i + 1 << ") " << servers[i].serverId << '\n';
          printCounters(servers[i].counters);
        }
        cout << "0) Back\n";
        const auto serverChoice = choose(servers.size());
        if (serverChoice == 0)
        {
          backToTypes = true;
          continue;
        }

        const auto& serverId = servers[serverChoice - 1].serverId;
        const auto jobs = status.getServerJobs(type, serverId);
        cout << "\n=== Jobs: " << type << " / " << serverId
                  << " (" << jobs.size() << ") ===\n";
        for (const auto& job : jobs)
        {
          cout << "jobId=" << job.jobId << " status=" << job.status
                    << " serverId=" << job.serverId << '\n';
          for (const auto& field : job.fields)
          {
            cout << "    " << field.first << ": "
                      << field.second << '\n';
          }
        }
        cout << "Press Enter to return to instances...";
        cin.ignore(numeric_limits<streamsize>::max(), '\n');
        cin.get();
      }
    }
    return EXIT_SUCCESS;
  }
  catch (const exception& ex)
  {
    cerr << "RedisStatusCli: " << ex.what() << '\n';
    return EXIT_FAILURE;
  }
}
