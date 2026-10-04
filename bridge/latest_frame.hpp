#pragma once
#include "protocol.hpp"
#include <atomic>
#include <memory>

namespace bridge {
// Atomic ownership transfer keeps the last render snapshot when no new frame arrives.
// A null snapshot is an explicit reset, distinct from an empty mailbox.
class LatestFrame {
    using Snapshot = std::shared_ptr<Frame>;
    std::atomic<Snapshot *> incoming_{};
    static_assert(std::atomic<Snapshot *>::is_always_lock_free);

  public:
    LatestFrame() = default;
    LatestFrame(const LatestFrame &) = delete;
    LatestFrame &operator=(const LatestFrame &) = delete;
    ~LatestFrame() { delete incoming_.exchange(nullptr); }
    void store(Snapshot value) {
        auto next = std::make_unique<Snapshot>(std::move(value));
        delete incoming_.exchange(next.release(), std::memory_order_acq_rel);
    }
    bool take(Snapshot &current) {
        std::unique_ptr<Snapshot> next(incoming_.exchange(nullptr, std::memory_order_acq_rel));
        if (!next)
            return false;
        current = std::move(*next);
        return true;
    }
};
} // namespace bridge
