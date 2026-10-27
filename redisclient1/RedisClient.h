/**
 * @file RedisClient.h
 * @brief Singleton wrapper for a standalone Redis or Redis Cluster client.
 *
 * Requires C++17 and redis-plus-plus. The project must expose the
 * `get_reg_val` configuration accessor to RedisClient.cc.
 */

#ifndef REDIS_CLIENT_H
#define REDIS_CLIENT_H

#include <memory>
#include <variant>

#include <sw/redis++/redis++.h>

/**
 * @class RedisClient
 * @brief Provides access to one configured Redis client instance.
 *
 * The client type is selected from configuration:
 * - `redisUrl`: connection URL.
 * - `isRedisCluster`: whether to create a Redis Cluster client.
 *
 * Call GetClient() only after configuration has been initialized. Do not
 * access this singleton from ELF initialization constructors.
 */
class RedisClient final {
public:
    /**
     * @brief Variant holding either a standalone Redis or Redis Cluster client.
     */
    using Client = std::variant<
        std::unique_ptr<sw::redis::Redis>,
        std::unique_ptr<sw::redis::RedisCluster>>;

    /**
     * @brief Gets the process-wide RedisClient instance.
     * @return Shared pointer to the singleton instance.
     * @throws std::invalid_argument if the configured Redis URL is empty.
     * @throws Any exception raised by configuration access or redis-plus-plus
     *         during client construction.
     *
     * Function-local static initialization is thread-safe in C++11 and later.
     */
    static std::shared_ptr<RedisClient> GetClient();

    RedisClient(const RedisClient&) = delete;
    RedisClient& operator=(const RedisClient&) = delete;
    RedisClient(RedisClient&&) = delete;
    RedisClient& operator=(RedisClient&&) = delete;

    /**
     * @brief Invokes a callable with the active Redis client.
     * @tparam Fn Callable accepting either sw::redis::Redis& or
     *             sw::redis::RedisCluster&.
     * @param fn Callable to invoke.
     * @return Whatever the callable returns.
     *
     * Example:
     * @code
     * auto client = RedisClient::GetClient();
     * client->Visit([](auto& redis) {
     *     // Use the active Redis client here.
     * });
     * @endcode
     */
    template <typename Fn>
    decltype(auto) Visit(Fn&& fn) {
        return std::visit(
            [&fn](auto& client) -> decltype(auto) {
                return fn(*client);
            },
            client_);
    }

private:
    /**
     * @brief Reads configuration and constructs the selected Redis client.
     */
    RedisClient();

    Client client_;
};

#endif // REDIS_CLIENT_H
