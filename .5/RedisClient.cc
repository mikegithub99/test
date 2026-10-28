/**
 * @file RedisClient.cc
 * @brief Implementation of RedisClient configuration and lazy singleton.
 */

#include "RedisClient.h"

#include <cstdlib>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

namespace
{
struct ConfigurationState
{
  std::mutex mutex;
  RedisClient::ConfigProvider provider;
  bool initializationStarted = false;
};

ConfigurationState& configurationState()
{
  static ConfigurationState state;
  return state;
}

RedisClient::Config environmentConfig()
{
  const char* url = std::getenv("REDIS_URL");
  const char* cluster = std::getenv("REDIS_CLUSTER");
  if (!url)
  {
    throw std::invalid_argument(
        "RedisClient: set REDIS_URL or install a ConfigProvider");
  }

  const std::string clusterValue = cluster ? cluster : "false";
  return RedisClient::Config{
      url, clusterValue == "1" || clusterValue == "true" ||
               clusterValue == "TRUE"};
}
} // namespace

void RedisClient::SetConfigProvider(ConfigProvider provider)
{
  if (!provider)
  {
    throw std::invalid_argument("RedisClient: ConfigProvider must be callable");
  }

  auto& state = configurationState();
  std::lock_guard<std::mutex> lock(state.mutex);
  if (state.initializationStarted)
  {
    throw std::logic_error(
        "RedisClient: configuration provider must be installed before "
        "GetClient() initializes the singleton");
  }
  state.provider = std::move(provider);
}

RedisClient::RedisClient()
{
  ConfigProvider provider;
  {
    auto& state = configurationState();
    std::lock_guard<std::mutex> lock(state.mutex);
    state.initializationStarted = true;
    provider = state.provider;
  }

  const Config config = provider ? provider() : environmentConfig();
  if (config.redisUrl.empty())
  {
    throw std::invalid_argument("RedisClient: redisUrl is empty");
  }

  if (config.isCluster)
  {
    m_client.emplace<std::unique_ptr<sw::redis::RedisCluster>>(
        std::make_unique<sw::redis::RedisCluster>(config.redisUrl));
  }
  else
  {
    m_client.emplace<std::unique_ptr<sw::redis::Redis>>(
        std::make_unique<sw::redis::Redis>(config.redisUrl));
  }
}

std::shared_ptr<RedisClient> RedisClient::GetClient()
{
  static const std::shared_ptr<RedisClient> instance(new RedisClient());
  return instance;
}
