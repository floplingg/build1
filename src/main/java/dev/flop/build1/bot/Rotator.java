package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.Random;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Where the camera should be looking.
 *
 * Blatant: snaps instantly.
 * Closet: moves like a hand on a mouse —
 *  - runs every rendered frame, not every tick
 *  - accelerates into a flick and decelerates out of it (no constant-speed robot turns)
 *  - flicks curve slightly instead of travelling in a perfectly straight line
 *  - big flicks sometimes overshoot a little and correct back
 *  - a faint tremor, and every step is a whole number of mouse "pixels" at your sensitivity
 */
public final class Rotator {
    private static final Random R = new Random();

    private static boolean hasTarget;
    private static float tYaw, tPitch;           // final target
    private static float oYaw, oPitch;           // overshoot point (if overshooting)
    private static boolean overshooting;
    private static double v;                     // current angular speed, deg/s
    private static double accel = 2400, maxV = 420;
    private static double startDist = 1, curveAmp, curveDone;
    private static long lastFrame;
    private static int framesThisTick;
    private static int onTargetTicks;
    private static double carryX, carryY;

    public static void aim(float yaw, float pitch) {
        float ny = Mth.wrapDegrees(yaw), np = Mth.clamp(pitch, -90f, 90f);
        LocalPlayer p = Bot.mc().player;
        boolean newFlick = !hasTarget || Math.abs(Mth.wrapDegrees(ny - tYaw)) > 6 || Math.abs(np - tPitch) > 6;
        tYaw = ny;
        tPitch = np;
        hasTarget = true;
        if (!Config.closet()) { Bot.setRotation(tYaw, tPitch); return; }
        if (newFlick && p != null) {
            float dy = Mth.wrapDegrees(ny - p.getYRot()), dp = np - p.getXRot();
            double dist = Math.sqrt(dy * dy + dp * dp);
            startDist = Math.max(dist, 0.5);
            accel = 1500 + R.nextDouble() * 2200;
            maxV = 240 + R.nextDouble() * 320 + Math.min(260, dist * 2.2); // big flicks are faster
            curveAmp = (R.nextDouble() * 2 - 1) * Math.min(7, dist * 0.10);
            curveDone = 0;
            overshooting = dist > 18 && R.nextDouble() < 0.35;
            if (overshooting) {
                double k = (1.0 + R.nextDouble() * 2.5) / dist;
                oYaw = Mth.wrapDegrees((float) (ny + dy * k));
                oPitch = Mth.clamp((float) (np + dp * k), -90f, 90f);
            }
        }
    }

    public static void clear() { hasTarget = false; onTargetTicks = 0; overshooting = false; v = 0; }

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
        if (!overshooting && on(tYaw, tPitch, 1.0f)) onTargetTicks++; else onTargetTicks = 0;
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
        float gy = overshooting ? oYaw : tYaw, gp = overshooting ? oPitch : tPitch;
        float dy = Mth.wrapDegrees(gy - p.getYRot());
        float dp = gp - p.getXRot();
        double dist = Math.sqrt(dy * dy + dp * dp);
        if (overshooting && dist < 0.5) { overshooting = false; v *= 0.25; return; }
        if (dist < 0.04) { v = 0; return; }

        // speed profile: accelerate, cruise, then brake so we arrive at ~0 speed
        double brakeV = Math.sqrt(2 * accel * 0.8 * dist) + 6;
        double want = Math.min(maxV, brakeV);
        if (v < want) v = Math.min(want, v + accel * dt); else v = want;
        double move = Math.min(dist, v * dt);

        double ux = dy / dist, uy = dp / dist;
        double mx = ux * move, my = uy * move;

        // curved path: sideways offset that grows then shrinks back to 0 over the flick
        double prog = Mth.clamp(1.0 - dist / startDist, 0, 1);
        double curveNow = curveAmp * Math.sin(Math.PI * prog);
        double dc = curveNow - curveDone;
        curveDone = curveNow;
        mx += -uy * dc;
        my += ux * dc;

        // tremor, a bit stronger while moving fast
        double tremor = 0.012 + Math.min(0.05, v / 9000.0);
        mx += R.nextGaussian() * tremor;
        my += R.nextGaussian() * tremor * 0.8;

        // degrees -> mouse units (turn() multiplies by 0.15), snapped to whole mouse pixels
        double f = Bot.mc().options.sensitivity().get() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0;
        double tx = mx / 0.15 + carryX, ty = my / 0.15 + carryY;
        double px = Math.round(tx / gcd), py = Math.round(ty / gcd);
        carryX = tx - px * gcd;
        carryY = ty - py * gcd;
        if (px != 0 || py != 0) p.turn(px * gcd, py * gcd);
    }

    private Rotator() {}
}
