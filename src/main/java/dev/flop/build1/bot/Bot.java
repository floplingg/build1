package dev.flop.build1.bot;

import dev.flop.build1.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Shared low-level helpers: looking, inventory, keys. */
public final class Bot {
    public static Minecraft mc() { return Minecraft.getInstance(); }

    // ---------- rotation ----------

    public static float[] anglesTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, h) * (180.0 / Math.PI));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    /** Sets the client rotation (camera follows, like Baritone). */
    public static void setRotation(float yaw, float pitch) {
        LocalPlayer p = mc().player;
        if (p == null) return;
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
    }

    /** Sets rotation AND tells the server right now, so the next action packet is judged with it. */
    public static void faceNow(float yaw, float pitch) {
        LocalPlayer p = mc().player;
        if (p == null || mc().getConnection() == null) return;
        setRotation(yaw, pitch);
        mc().getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround(), p.horizontalCollision));
    }

    public static Vec3 eye() { return mc().player.getEyePosition(); }

    public static double reach() {
        LocalPlayer p = mc().player;
        double real = p == null ? 4.5 : p.blockInteractionRange();
        return Math.min(Config.reach, real) - 0.05;
    }

    /** Face of pos that points most toward the player's eye. */
    public static Direction faceToward(BlockPos pos) {
        Vec3 d = eye().subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
        return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    // ---------- inventory ----------

    /** Inventory index (0-8 hotbar, 9-35 main) holding item, or -1. Hotbar first. */
    public static int find(Item item) {
        Inventory inv = mc().player.getInventory();
        int sel = inv.getSelectedSlot();
        if (inv.getItem(sel).is(item)) return sel;
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }

    /**
     * Makes inventory index `slot` the held item. Returns true if it's held right now,
     * false if a swap was just sent (wait a tick before using it).
     */
    public static boolean hold(int slot) {
        LocalPlayer p = mc().player;
        Inventory inv = p.getInventory();
        if (slot < 9) {
            inv.setSelectedSlot(slot);
            return true;
        }
        int hb = Config.hotbarSlot;
        mc().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, hb, ClickType.SWAP, p);
        inv.setSelectedSlot(hb);
        return false;
    }

    /** In creative, conjure the item into the swap hotbar slot. */
    public static boolean creativeGive(Item item) {
        LocalPlayer p = mc().player;
        if (!p.getAbilities().instabuild) return false;
        int hb = Config.hotbarSlot;
        ItemStack st = new ItemStack(item);
        st.setCount(st.getMaxStackSize());
        p.getInventory().setItem(hb, st);
        mc().gameMode.handleCreativeModeItemAdd(st, 36 + hb);
        p.getInventory().setSelectedSlot(hb);
        return true;
    }

    // ---------- movement keys ----------

    private static boolean pressing;

    public static void keys(boolean forward, boolean jump, boolean sneak) {
        var o = mc().options;
        o.keyUp.setDown(forward);
        o.keyJump.setDown(jump);
        o.keyShift.setDown(sneak);
        o.keySprint.setDown(false);
        pressing = forward || jump || sneak;
    }

    public static void releaseKeys() {
        if (!pressing) return;
        keys(false, false, false);
        pressing = false;
    }

    private Bot() {}
}
