package dev.sekirobridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.option.KeyBinding;
import dev.sekirobridge.mixin.InputInvoker;
import dev.sekirobridge.mixin.KeyboardInvoker;
import org.lwjgl.glfw.GLFW;

final class InputForwarder {
    private final boolean[] pressed = new boolean[256];
    private int buttons, wheel;
    private long textSequence, epoch;
    private boolean initialized;
    static int glfwKey(int vk) {
        if (vk >= 48 && vk <= 57 || vk >= 65 && vk <= 90)
            return vk;
        return switch (vk) {
            case 8 -> GLFW.GLFW_KEY_BACKSPACE;
            case 9 -> GLFW.GLFW_KEY_TAB;
            case 13 -> GLFW.GLFW_KEY_ENTER;
            case 16, 160 -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case 161 -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case 17, 162 -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case 163 -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case 18, 164 -> GLFW.GLFW_KEY_LEFT_ALT;
            case 165 -> GLFW.GLFW_KEY_RIGHT_ALT;
            case 27 -> GLFW.GLFW_KEY_ESCAPE;
            case 32 -> GLFW.GLFW_KEY_SPACE;
            case 33 -> GLFW.GLFW_KEY_PAGE_UP;
            case 34 -> GLFW.GLFW_KEY_PAGE_DOWN;
            case 35 -> GLFW.GLFW_KEY_END;
            case 36 -> GLFW.GLFW_KEY_HOME;
            case 37 -> GLFW.GLFW_KEY_LEFT;
            case 38 -> GLFW.GLFW_KEY_UP;
            case 39 -> GLFW.GLFW_KEY_RIGHT;
            case 40 -> GLFW.GLFW_KEY_DOWN;
            case 46 -> GLFW.GLFW_KEY_DELETE;
            case 186 -> GLFW.GLFW_KEY_SEMICOLON;
            case 187 -> GLFW.GLFW_KEY_EQUAL;
            case 188 -> GLFW.GLFW_KEY_COMMA;
            case 189 -> GLFW.GLFW_KEY_MINUS;
            case 190 -> GLFW.GLFW_KEY_PERIOD;
            case 191 -> GLFW.GLFW_KEY_SLASH;
            case 192 -> GLFW.GLFW_KEY_GRAVE_ACCENT;
            case 219 -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case 220 -> GLFW.GLFW_KEY_BACKSLASH;
            case 221 -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case 222 -> GLFW.GLFW_KEY_APOSTROPHE;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }
    void update(Protocol.State s) {
        var c = MinecraftClient.getInstance();
        long window = c.getWindow().getHandle();
        if (!initialized || epoch != s.epoch()) {
            release();
            initialized = true;
            epoch = s.epoch();
            wheel = s.wheel();
            textSequence = s.textSequence();
        }
        boolean screen = c.currentScreen != null, edit = (s.flags() & Protocol.EDIT) != 0;
        int mods = (s.key(16) ? GLFW.GLFW_MOD_SHIFT : 0) | (s.key(17) ? GLFW.GLFW_MOD_CONTROL : 0) |
                   (s.key(18) ? GLFW.GLFW_MOD_ALT : 0);
        for (int vk = 8; vk < 256; ++vk) {
            // WASD and camera movement belong to Sekiro. In screens, forward their UI key events.
            boolean allowed =
                screen ||
                (edit && (vk == 69 || vk == 81 || vk == 70 || vk >= 49 && vk <= 57 || vk == 16 || vk == 17));
            int key = glfwKey(vk);
            if (key == GLFW.GLFW_KEY_UNKNOWN)
                continue;
            boolean down = allowed && s.key(vk);
            if (down != pressed[vk]) {
                pressed[vk] = down;
                ((KeyboardInvoker)c.keyboard)
                    .bridgeKey(window, key, 0, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, mods);
            }
        }
        // Mouse coordinates in the actual MC window; its GUI scale is handled by vanilla Mouse.
        if (screen)
            ((InputInvoker)c.mouse)
                .bridgeCursor(window, Math.max(0, Math.min(1, s.mouseX())) * c.getWindow().getWidth(),
                              Math.max(0, Math.min(1, s.mouseY())) * c.getWindow().getHeight());
        int nextButtons = screen || edit ? s.buttons() : 0;
        for (int i = 0; i < 3; ++i)
            if (((buttons ^ nextButtons) & (1 << i)) != 0)
                ((InputInvoker)c.mouse)
                    .bridgeButton(window, i,
                                  (nextButtons & (1 << i)) != 0 ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, mods);
        buttons = nextButtons;
        int delta = s.wheel() - wheel;
        wheel = s.wheel();
        if ((screen || edit) && delta != 0 && Math.abs(delta) <= 120 * 8)
            ((InputInvoker)c.mouse).bridgeScroll(window, 0, delta / 120.0);
        if (s.textSequence() >= textSequence) {
            long first = Math.max(textSequence, s.textSequence() - 8);
            for (long i = first; i < s.textSequence(); ++i)
                if (screen) {
                    int cp = s.text()[(int)(i % 8)];
                    if (Character.isValidCodePoint(cp))
                        ((KeyboardInvoker)c.keyboard).bridgeChar(window, cp, mods);
                }
        }
        textSequence = s.textSequence();
        if (!screen)
            c.player.input.movementForward = c.player.input.movementSideways = 0;
    }
    void release() {
        if (!initialized)
            return;
        var c = MinecraftClient.getInstance();
        if (c == null || c.getWindow() == null)
            return;
        long w = c.getWindow().getHandle();
        for (int vk = 8; vk < 256; ++vk)
            if (pressed[vk]) {
                pressed[vk] = false;
                int key = glfwKey(vk);
                if (key != GLFW.GLFW_KEY_UNKNOWN)
                    ((KeyboardInvoker)c.keyboard).bridgeKey(w, key, 0, GLFW.GLFW_RELEASE, 0);
            }
        for (int i = 0; i < 3; ++i)
            if ((buttons & (1 << i)) != 0)
                ((InputInvoker)c.mouse).bridgeButton(w, i, GLFW.GLFW_RELEASE, 0);
        buttons = 0;
        initialized = false;
        // Release any vanilla action whose press was consumed by the client tick.
        if (c.options != null) {
            c.options.attackKey.setPressed(false);
            c.options.useKey.setPressed(false);
        }
    }
}
