#include "RedisClient.h"

#include <stdexcept>
#include <string>

// The project must make get_reg_val available before compiling this file.
// This implementation assumes it accepts string keys.

namespace app::redis {

RedisClient::RedisClient() {
    const std::string redisUrl = get_reg_val("redisUrl");
    const bool isCluster = static_cast<bool>(get_reg_val("isRedisCluster"));

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

std::shared_ptr<RedisClient> RedisClient::GetClient() {
    // Function-local static initialization is thread-safe in C++11 and later.
    // Call only after registry/config initialization, never from ELF constructors.
    static const std::shared_ptr<RedisClient> instance(new RedisClient());
    return instance;
}

} // namespace app::redis
