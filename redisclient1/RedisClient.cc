/**
 * @file RedisClient.cc
 * @brief Implementation of the RedisClient singleton.
 */

#include "RedisClient.h"

#include <stdexcept>
#include <string>

// Make the project header defining get_reg_val available to this translation
// unit, or ensure the build system provides that declaration/macro.

RedisClient::RedisClient() {
    const std::string redisUrl = get_reg_val("redisUrl");
    const bool isCluster =
        static_cast<bool>(get_reg_val("isRedisCluster"));

    if (redisUrl.empty()) {
        throw std::invalid_argument("RedisClient: redisUrl is empty");
    }

    if (isCluster) {
        client_.emplace<std::unique_ptr<sw::redis::RedisCluster>>(
            std::make_unique<sw::redis::RedisCluster>(redisUrl));
    } else {
        client_.emplace<std::unique_ptr<sw::redis::Redis>>(
            std::make_unique<sw::redis::Redis>(redisUrl));
    }
}

/**
 * @brief Returns the process-wide RedisClient instance.
 *
 * Call only after application configuration and the get_reg_val registry are
 * initialized. Do not call from ELF constructors or plugin registration hooks.
 */
std::shared_ptr<RedisClient> RedisClient::GetClient() {
    static const std::shared_ptr<RedisClient> instance(new RedisClient());
    return instance;
}
