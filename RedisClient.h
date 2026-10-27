#ifndef APP_REDIS_REDIS_CLIENT_H
#define APP_REDIS_REDIS_CLIENT_H

#include <memory>
#include <variant>
#include <sw/redis++/redis++.h>
#include <sw/redis++/redis_cluster.h>

namespace app::redis {

class RedisClient final {
public:
    using Client = std::variant<std::unique_ptr<sw::redis::Redis>,
                                std::unique_ptr<sw::redis::RedisCluster>>;
    static std::shared_ptr<RedisClient> GetClient();

    RedisClient(const RedisClient&) = delete;
    RedisClient& operator=(const RedisClient&) = delete;

    template <typename Fn>
    decltype(auto) Visit(Fn&& fn) {
        return std::visit([&fn](auto& client) -> decltype(auto) {
            return fn(*client);
        }, client_);
    }

private:
    RedisClient();
    Client client_;
};

} // namespace app::redis

#endif // APP_REDIS_REDIS_CLIENT_H
