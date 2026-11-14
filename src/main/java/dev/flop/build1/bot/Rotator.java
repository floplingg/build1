package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.Random;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Where the camera should be looking.
 *
 * Blatant: snaps instantly.
 * Closet: turns like a real mouse. Moves every frame (not every tick), eases in and out,
 * varies speed per target, adds tiny hand noise, and every step is a whole number of
 * "mouse pixels" at your sensitivity (the same quantisation a real mouse produces).
 */
public final class Rotator {
    private static final Random R = new Random();

    private static boolean hasTarget;
    private static float tYaw, tPitch;
    private static float speed = 400f; // deg/sec cap for this target
    private static long lastFrame;
    private static int framesThisTick;
    private static int onTargetTicks;
    private static double carryX, carryY; // sub-pixel remainder so slow turns still happen

    public static void aim(float yaw, float pitch) {
        float ny = Mth.wrapDegrees(yaw), np = Mth.clamp(pitch, -90f, 90f);
        if (!hasTarget || Math.abs(Mth.wrapDegrees(ny - tYaw)) > 8 || Math.abs(np - tPitch) > 8) {
            speed = 260f + R.nextFloat() * 260f; // new "flick": new speed
        }
        hasTarget = true;
        tYaw = ny;
        tPitch = np;
        if (!Config.closet()) Bot.setRotation(tYaw, tPitch);
    }

    public static void clear() { hasTarget = false; onTargetTicks = 0; }

    public static boolean hasTarget() { return hasTarget; }

    /** Within tol degrees of (yaw, pitch) right now. */
    public static boolean on(float yaw, float pitch, float tol) {
        LocalPlayer p = Bot.mc().player;
        if (p == null) return false;
        return Math.abs(Mth.wrapDegrees(p.getYRot() - yaw)) <= tol && Math.abs(p.getXRot() - pitch) <= tol;
    }

    /** True once we've been on target for at least one full tick, so the server has seen this rotation. */
    public static boolean settled() { return onTargetTicks >= 2; }

    /** Called at the end of every client tick. */
    public static void tick() {
        if (!hasTarget) { framesThisTick = 0; onTargetTicks = 0; return; }
        if (!Config.closet()) {
            Bot.setRotation(tYaw, tPitch);
            onTargetTicks = 2;
            return;
        }
        if (framesThisTick == 0) step(0.05); // no frames rendered (F1 / minimized): step per tick
        framesThisTick = 0;
        if (on(tYaw, tPitch, 1.2f)) onTargetTicks++; else onTargetTicks = 0;
    }

    /** Called every rendered frame. */
    public static void frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (!hasTarget || !Config.closet()) return;
        framesThisTick++;
        step(dt);
    }

    private static void step(double dt) {
        LocalPlayer p = Bot.mc().player;
        if (p == null) return;
        float dy = Mth.wrapDegrees(tYaw - p.getYRot());
        float dp = tPitch - p.getXRot();
        double dist = Math.sqrt(dy * dy + dp * dp);
        if (dist < 0.05) return;
        // ease-out: fast far away, slow near the end
        double v = Math.min(speed, dist * 9.0 + 25.0);
        double move = Math.min(dist, v * dt);
        double mx = dy / dist * move, my = dp / dist * move;
        // hand jitter
        mx += (R.nextGaussian() * 0.04) * (move > 0.5 ? 1 : 0.3);
        my += (R.nextGaussian() * 0.03) * (move > 0.5 ? 1 : 0.3);

        // Convert degrees -> mouse units the same way vanilla does (turn() multiplies by 0.15),
        // and snap to whole mouse "pixels" for the current sensitivity.
        double f = Bot.mc().options.sensitivity().get() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0;
        double ux = mx / 0.15 + carryX, uy = my / 0.15 + carryY;
        double px = Math.round(ux / gcd), py = Math.round(uy / gcd);
        carryX = ux - px * gcd;
        carryY = uy - py * gcd;
        if (px != 0 || py != 0) p.turn(px * gcd, py * gcd);
    }

    private Rotator() {}
}
