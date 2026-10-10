package dev.sekirobridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Protocol {
    public static final int CONTROL_BYTES = 200, META_BYTES = 112, INPUT_BYTES=6208, INPUT_HEADER=64, INPUT_EVENT=48, MAX_WIDTH = 1920, MAX_HEIGHT = 1080;
    public static final int MOUSE_BUTTONS = 5;
    public static final int CAMERA_ROLL_CAPABILITY=1<<14;
    public static final int SCENE = 1, FOCUS = 2, EDIT = 4, MENU = 8, NATIVE_DEAD = 32, NATIVE_ACTION = 64, NATIVE_UI=128, NATIVE_GRAPPLE=256, NATIVE_CINEMATIC=512;
    public record State(long sequence, long tickMs, long epoch, int flags, int capabilities, float px,
                        float py, float pz, float ex, float ey, float ez, float fx, float fy, float fz,
                        float fov, float aspect, float near, float far, float yOffset, float scale, int width,
                        int height, byte[] keys, float mouseX, float mouseY, int wheel, int buttons,
                        int command, long textSequence, int[] text,float captureYaw,float captureRoll) {
        // Keep source compatibility with the existing input/protocol fixtures.
        public State(long sequence,long tickMs,long epoch,int flags,int capabilities,float px,float py,float pz,
            float ex,float ey,float ez,float fx,float fy,float fz,float fov,float aspect,float near,float far,
            float yOffset,float scale,int width,int height,byte[] keys,float mouseX,float mouseY,int wheel,
            int buttons,int command,long textSequence,int[] text,float captureYaw) {
            this(sequence,tickMs,epoch,flags,capabilities,px,py,pz,ex,ey,ez,fx,fy,fz,fov,aspect,near,far,
                yOffset,scale,width,height,keys,mouseX,mouseY,wheel,buttons,command,textSequence,text,captureYaw,0);
        }
        public boolean valid() {
            if (sequence <= 0 || epoch == 0 || width <= 0 || width > MAX_WIDTH || height <= 0 ||
                height > MAX_HEIGHT || !Float.isFinite(yOffset) || Math.abs(yOffset) > 100000 ||
                !Float.isFinite(scale) || scale < .1 || scale > 10 || !Float.isFinite(captureRoll))
                return false;
            if ((flags & SCENE) == 0)
                return true;
            float norm = fx * fx + fy * fy + fz * fz;
            for (float f : new float[] {px, py, pz, ex, ey, ez, fx, fy, fz, fov, aspect, near, far})
                if (!Float.isFinite(f))
                    return false;
            return Math.abs(px) <= 1e5 && Math.abs(py) <= 1e5 && Math.abs(pz) <= 1e5 && Math.abs(ex) <= 1e6 &&
                Math.abs(ey) <= 1e6 && Math.abs(ez) <= 1e6 && norm > .99 && norm < 1.01 && fov > .025 &&
                fov < 3.05 && aspect >= .7 && aspect <= 4 && near > 0 && far > near && far <= 100000;
        }
        public boolean active(long now) {
            return valid() && fresh(now, tickMs) && (flags & (SCENE | FOCUS)) == (SCENE | FOCUS) &&
                (flags & NATIVE_CINEMATIC)==0;
        }
        public boolean key(int vk) { return vk >= 0 && vk < 256 && (keys[vk / 8] & (1 << (vk % 8))) != 0; }
        public double mcX(double x) { return x * scale; }
        public double mcY(double y) { return y * scale + yOffset; }
        public double mcZ(double z) { return -z * scale; }
        public float yaw() { return (float)Math.toDegrees(Math.atan2(-fx, -fz)); }
        public float pitch() { return (float)Math.toDegrees(Math.atan2(-fy, Math.hypot(fx, fz))); }
        public State withCamera(float x,float y,float z,float vx,float vy,float vz) {
            return withCamera(x,y,z,vx,vy,vz,(float)Math.toDegrees(Math.atan2(-vx,-vz)));
        }
        public State withCamera(float x,float y,float z,float vx,float vy,float vz,float yaw) {
            return withCamera(x,y,z,vx,vy,vz,yaw,0);
        }
        public State withCamera(float x,float y,float z,float vx,float vy,float vz,float yaw,float roll) {
            return new State(sequence,tickMs,epoch,flags,capabilities,px,py,pz,x,y,z,vx,vy,vz,
                fov,aspect,near,far,yOffset,scale,width,height,keys,mouseX,mouseY,wheel,buttons,command,textSequence,text,yaw,roll);
        }
        public State withFov(float value){
            return new State(sequence,tickMs,epoch,flags,capabilities,px,py,pz,ex,ey,ez,fx,fy,fz,
                value,aspect,near,far,yOffset,scale,width,height,keys,mouseX,mouseY,wheel,buttons,command,textSequence,text,captureYaw,captureRoll);
        }
    }
    public static boolean fresh(long now, long stamp) {
        return stamp > 0 && now >= stamp && now - stamp <= 350;
    }
    public static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    public static State decode(ByteBuffer source) {
        if (source.capacity() < CONTROL_BYTES)
            throw new IllegalArgumentException("Truncated control packet");
        ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.clear();
        long sequence = b.getLong(), tick = b.getLong(), epoch = b.getLong();
        int flags = b.getInt(), capabilities = b.getInt();
        float[] p = new float[15];
        for (int i = 0; i < p.length; i++)
            p[i] = b.getFloat();
        int width = b.getInt(), height = b.getInt();
        byte[] keys = new byte[32];
        b.get(keys);
        float mouseX = b.getFloat(), mouseY = b.getFloat();
        int wheel = b.getInt(), buttons = b.getInt(), command = b.getInt();
        b.position(160);
        long textSequence = b.getLong();
        int[] text = new int[8];
        for (int i = 0; i < 8; ++i)
            text[i] = b.getInt();
        return new State(sequence, tick, epoch, flags, capabilities, p[0], p[1], p[2], p[3], p[4], p[5], p[6],
                         p[7], p[8], p[9], p[10], p[11], p[12], p[13], p[14], width, height, keys, mouseX,
                         mouseY, wheel, buttons, command, textSequence, text,(float)Math.toDegrees(Math.atan2(-p[6],-p[8])));
    }
    public static ByteBuffer metadata(State s, long sequence, int width, int height) {
        return metadata(s,sequence,width,height,NativeBridge.clockMs());
    }
    public static ByteBuffer metadata(State s,long sequence,int width,int height,long capturedAt) {
        ByteBuffer b = direct(META_BYTES);
        b.putLong(sequence).putLong(capturedAt).putLong(s.epoch).putLong(s.sequence);
        b.putInt(width).putInt(height);
        if (Math.abs(s.captureRoll)>0.0001f) b.putInt(15).putInt(packAngles(s.captureYaw,s.captureRoll));
        else b.putInt(7).putFloat(s.captureYaw);
        b.putFloat(s.ex).putFloat(s.ey).putFloat(s.ez).putFloat(s.fx).putFloat(s.fy).putFloat(s.fz);
        b.putFloat(s.fov).putFloat(s.aspect).putFloat(s.near).putFloat(s.far).putLong(s.tickMs);
        b.putLong(0).putInt(0).putInt(0);
        return b.flip();
    }
    public static int packAngles(float yaw,float roll) {
        return (encodeAngle(yaw)&65535) | ((encodeAngle(roll)&65535)<<16);
    }
    private static int encodeAngle(float degrees) {
        float wrapped=((degrees+180)%360+360)%360-180;
        return Math.round(wrapped*32767/180);
    }
}
