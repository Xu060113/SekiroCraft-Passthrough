#pragma once
#include "latest_frame.hpp"

namespace bridge {
// The physics camera selects a completed image. A newer IPC image must not
// replace that image after the native scene has already been rendered.
class CameraFrames {
    LatestFrame incoming_, displayed_;
    std::shared_ptr<Frame> ready_;
  public:
    void receive(std::shared_ptr<Frame> frame) { incoming_.store(std::move(frame)); }
    std::shared_ptr<Frame> select(uint64_t epoch, uint64_t now) {
        incoming_.take(ready_);
        if (!ready_ || ready_->meta.epoch != epoch || !valid(ready_->meta) ||
            !fresh(now, ready_->meta.tickMs) || !fresh(now, ready_->meta.controlTickMs))
            return {};
        return ready_;
    }
    void applied(std::shared_ptr<Frame> frame) { displayed_.store(std::move(frame)); }
    void takeDisplayed(std::shared_ptr<Frame> &frame) { displayed_.take(frame); }
};
}
