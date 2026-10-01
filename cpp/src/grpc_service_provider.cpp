#include "grpc_service_provider.h"
#include <algorithm>
#include <chrono>
#include <stdexcept>

namespace grpcdiscovery {
ServiceProvider::ServiceProvider(std::shared_ptr<grpc::Channel> registry_channel,
                                 std::string service_name, std::string instance_id,
                                 std::string host, int port, int ttl_seconds)
    : registry_channel_(std::move(registry_channel)),
      stub_(registry::GrpcServiceRegistry::NewStub(registry_channel_)),
      service_name_(std::move(service_name)), instance_id_(std::move(instance_id)),
      host_(std::move(host)), port_(port), ttl_seconds_(ttl_seconds) {
    if (!stub_ || service_name_.empty() || instance_id_.empty() ||
        host_.empty() || port_ <= 0 || ttl_seconds_ <= 0)
        throw std::invalid_argument("Invalid service registration configuration");
}
ServiceProvider::~ServiceProvider() { stop(); }
void ServiceProvider::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) return;
    if (!registerService()) {
        running_ = false;
        throw std::runtime_error("Initial service registration failed");
    }
    heartbeat_thread_ = std::thread(&ServiceProvider::heartbeatLoop, this);
}
void ServiceProvider::stop() {
    bool expected = true;
    if (!running_.compare_exchange_strong(expected, false)) return;
    if (heartbeat_thread_.joinable()) heartbeat_thread_.join();
    unregisterService();
}
bool ServiceProvider::registerService() {
    registry::RegisterRequest req;
    req.set_service_name(service_name_); req.set_instance_id(instance_id_);
    req.set_host(host_); req.set_port(port_); req.set_ttl_seconds(ttl_seconds_);
    registry::RegisterResponse res;
    grpc::ClientContext ctx;
    auto status = stub_->Register(&ctx, req, &res);
    return status.ok() && res.success();
}
bool ServiceProvider::heartbeat() {
    registry::HeartbeatRequest req;
    req.set_service_name(service_name_); req.set_instance_id(instance_id_);
    registry::HeartbeatResponse res;
    grpc::ClientContext ctx;
    auto status = stub_->Heartbeat(&ctx, req, &res);
    return status.ok() && res.success();
}
void ServiceProvider::unregisterService() {
    registry::UnregisterRequest req;
    req.set_service_name(service_name_); req.set_instance_id(instance_id_);
    registry::UnregisterResponse res;
    grpc::ClientContext ctx;
    stub_->Unregister(&ctx, req, &res);
}
void ServiceProvider::heartbeatLoop() {
    const auto interval = std::chrono::seconds(std::max(1, ttl_seconds_ / 3));
    while (running_) {
        std::this_thread::sleep_for(interval);
        if (running_) heartbeat();
    }
}
}
