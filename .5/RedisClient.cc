/**
 * @file RedisClient.cc
 * @brief Implementation of RedisClient configuration and lazy singleton.
 */

#include "RedisClient.h"

#include <cstdlib>
#include <stdexcept>
#include <string>
#include <utility>

namespace
{
RedisClient::Config defaultConfigProvider()
{
  const char* url = std::getenv("REDIS_URL");
  const char* cluster = std::getenv("REDIS_CLUSTER");
  if (!url)
  {
    throw std::invalid_argument(
        "RedisClient: set REDIS_URL or pass a ConfigProvider to GetClient()");
  }

  const std::string clusterValue = cluster ? cluster : "false";
  return RedisClient::Config{
      url, clusterValue == "1" || clusterValue == "true" ||
               clusterValue == "TRUE"};
}
} // namespace

RedisClient::RedisClient(ConfigProvider provider)
{
  const Config config = provider ? provider() : defaultConfigProvider();
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

std::shared_ptr<RedisClient> RedisClient::GetClient(ConfigProvider provider)
{
  static const std::shared_ptr<RedisClient> instance(
      new RedisClient(std::move(provider)));
  return instance;
}
