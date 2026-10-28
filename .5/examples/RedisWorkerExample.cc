/**
 * @file RedisWorkerExample.cc
 * @brief Register a worker instance and maintain its heartbeat.
 */

#include "RedisJobStatus.h"

#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/socket.h>

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>

using namespace std;

namespace
{
atomic<bool> g_running{true};

void onSignal(int)
{
  g_running.store(false);
}

void initializeApplicationConfiguration()
{
  // TODO: initialize the host application's configuration registry before constructing RedisJobStatus.
}

string hostIPv4()
{
  ifaddrs* interfaces = nullptr;
  if (getifaddrs(&interfaces) != 0)
  {
    return "127.0.0.1";
  }

  string result = "127.0.0.1";
  for (const ifaddrs* item = interfaces; item; item = item->ifa_next)
  {
    if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET ||
        (item->ifa_flags & IFF_LOOPBACK))
    {
      continue;
    }

    char text[INET_ADDRSTRLEN] = {};
    const auto* address = reinterpret_cast<const sockaddr_in*>(item->ifa_addr);
    if (inet_ntop(AF_INET, &address->sin_addr, text, sizeof(text)))
    {
      result = text;
      break;
    }
  }
  freeifaddrs(interfaces);
  return result;
}

string randomFiveLetters()
{
  static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  random_device rd;
  uniform_int_distribution<int> choose(0, 25);
  string suffix;
  for (int i = 0; i < 5; ++i)
  {
    suffix.push_back(alphabet[choose(rd)]);
  }
  return suffix;
}

string parseServerType(int argc, char** argv)
{
  const string prefix = "--serverType=";
  for (int i = 1; i < argc; ++i)
  {
    const string arg(argv[i]);
    if (arg.rfind(prefix, 0) == 0 && arg.size() > prefix.size())
    {
      return arg.substr(prefix.size());
    }
  }
  throw invalid_argument(
      "Usage: RedisWorkerExample --serverType=query-server-type1");
}
} // namespace

int main(int argc, char** argv)
{
  try
  {
    const auto serverType = parseServerType(argc, argv);
    initializeApplicationConfiguration();

    const auto serverId = hostIPv4() + "-" + randomFiveLetters();
    RedisJobStatus status;
    status.registerServer(serverType, serverId);

    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);

    constexpr int64_t ttlSeconds = 30;
    status.refreshHeartbeat(serverType, serverId, ttlSeconds);
    cout << "Registered " << serverType << " / " << serverId << '\n';

    while (g_running.load())
    {
      this_thread::sleep_for(chrono::seconds(10));
      if (g_running.load())
      {
        status.refreshHeartbeat(serverType, serverId, ttlSeconds);
      }
    }

    cout << "Stopping; heartbeat will expire in up to "
              << ttlSeconds << " seconds.\n";
    return EXIT_SUCCESS;
  }
  catch (const exception& ex)
  {
    cerr << "RedisWorkerExample: " << ex.what() << '\n';
    return EXIT_FAILURE;
  }
}
