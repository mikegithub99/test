/**
 * @file RedisClient.cc
 * @brief Implementation of RedisClient configuration and lazy singleton.
 */

#include "RedisClient.h"

#include <algorithm>
#include <cctype>
#include <stdexcept>
#include <string>
#include <utility>

// The host application provides this registry lookup.
std::string get_reg_val(const std::string& key);

namespace
{
bool parseClusterFlag(std::string value)
{
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char ch)
                 {
                   return static_cast<char>(std::tolower(ch));
                 });

  if (value.empty() || value == "false" || value == "0" || value == "no")
  {
    return false;
  }
  if (value == "true" || value == "1" || value == "yes")
  {
    return true;
  }

  throw std::invalid_argument(
      "RedisClient: isRedisCluster must be true/false, 1/0, or yes/no");
}

RedisClient::Config defaultConfigProvider()
{
  const auto url = get_reg_val("redisUrl");
  if (url.empty())
  {
    throw std::invalid_argument(
        "RedisClient: get_reg_val(\"redisUrl\") returned an empty value");
  }

  return RedisClient::Config{
      url, parseClusterFlag(get_reg_val("isRedisCluster"))};
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

std::shared_ptr<RedisClient> RedisClient::Instance(ConfigProvider provider)
{
  static const std::shared_ptr<RedisClient> instance(
      new RedisClient(std::move(provider)));
  return instance;
}
