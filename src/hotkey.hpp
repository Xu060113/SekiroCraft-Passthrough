#pragma once
namespace bridge {
class KeyEdge {
    bool down_{};

  public:
    bool update(bool down, bool notified) {
        bool pressed = !down_ && (down || notified);
        down_ = down;
        return pressed;
    }
};
} // namespace bridge
