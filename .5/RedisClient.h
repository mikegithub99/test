/**
 * @file RedisClient.h
 * @brief Lazy singleton wrapper for standalone Redis or Redis Cluster.
 *
 * Configure a provider before the first GetClient() call. Applications can
 * adapt their get_reg_val() configuration registry; examples and tests can
 * provide deterministic mock configuration without defining a global symbol.
 */

#ifndef REDIS_CLIENT_H
#define REDIS_CLIENT_H

#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <variant>

#include <sw/redis++/redis++.h>

/**
 * @class RedisClient
 * @brief Provides one configured Redis client instance.
 *
 * The configuration provider is called once, during lazy initialization.
 * If no provider is installed, REDIS_URL and REDIS_CLUSTER environment
 * variables are used (REDIS_CLUSTER accepts "1" or "true" for cluster mode).
 */
class RedisClient final
{
public:
  struct Config
  {
    std::string redisUrl;
    bool isCluster = false;
  };

  using ConfigProvider = std::function<Config()>;
  using Client = std::variant<
      std::unique_ptr<sw::redis::Redis>,
      std::unique_ptr<sw::redis::RedisCluster>>;

  /**
   * @brief Installs configuration logic before the first GetClient() call.
   * @throws std::logic_error if client initialization has already started.
   */
  static void SetConfigProvider(ConfigProvider provider);

  /** @brief Gets the process-wide client wrapper, initializing it lazily. */
  static std::shared_ptr<RedisClient> GetClient();

  RedisClient(const RedisClient&) = delete;
  RedisClient& operator=(const RedisClient&) = delete;

  /**
   * @brief Invokes a callable with the active Redis or Redis Cluster client.
   * @tparam Fn Callable accepting either concrete client reference.
   */
  template <typename Fn>
  decltype(auto) execute(Fn&& fn)
  {
    return std::visit(
        [&fn](auto& client) -> decltype(auto)
        {
          return std::forward<Fn>(fn)(*client);
        },
        m_client);
  }

private:
  RedisClient();
  Client m_client;
};

#endif // REDIS_CLIENT_H
