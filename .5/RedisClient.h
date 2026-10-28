/**
 * @file RedisClient.h
 * @brief Lazy singleton wrapper for standalone Redis or Redis Cluster.
 *
 * The default provider reads the application's get_reg_val() registry. Pass a ConfigProvider to Instance() on its first call only when overriding configuration (for example, in tests).
 */

#ifndef REDIS_CLIENT_H
#define REDIS_CLIENT_H

#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <variant>

#include <sw/redis++/redis++.h>

using std::forward;
using std::function;
using std::shared_ptr;
using std::string;
using std::unique_ptr;
using std::variant;
using std::visit;
using sw::redis::Redis;
using sw::redis::RedisCluster;

/**
 * @class RedisClient
 * @brief Provides one configured Redis client instance.
 *
 * Configuration is read once during lazy initialization. The first call to
 * Instance() determines the provider for the process-wide singleton.
 */
class RedisClient final
{
public:
  struct Config
  {
    string redisUrl;
    bool isCluster = false;
  };

  using ConfigProvider = function<Config()>;
  using Client = variant<
      unique_ptr<Redis>,
      unique_ptr<RedisCluster>>;

  /**
   * @brief Gets the process-wide client wrapper, initializing it lazily.
   * @param provider Optional configuration provider. If omitted, the default
   *        provider reads redisUrl and isRedisCluster through get_reg_val().
   *        A supplied provider must be passed on the first call.
   */
  static shared_ptr<RedisClient> Instance(ConfigProvider provider = {});

  RedisClient(const RedisClient&) = delete;
  RedisClient& operator=(const RedisClient&) = delete;

  /**
   * @brief Invokes a callable with the active Redis or Redis Cluster client.
   * @tparam Fn Callable accepting either concrete client reference.
   */
  template <typename Fn>
  decltype(auto) execute(Fn&& fn)
  {
    return visit(
        [&fn](auto& client) -> decltype(auto)
        {
          return forward<Fn>(fn)(*client);
        },
        m_client);
  }

private:
  explicit RedisClient(ConfigProvider provider = {});
  Client m_client;
};

#endif // REDIS_CLIENT_H
