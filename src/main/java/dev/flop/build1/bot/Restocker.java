package dev.flop.build1.bot;

import dev.flop.build1.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Walks to materials chests, opens them and shift-clicks out what the build still needs. */
public final class Restocker {
    private enum S { IDLE, WALK, OPEN, WAIT_OPEN, TAKE, CLOSE }

    private S s = S.IDLE;
    private final Walker walker = new Walker();
    private List<BlockPos> chests = new ArrayList<>();
    private int chestIdx, timer, lastPlan = -100, t;
    private Map<Item, Integer> needs = new HashMap<>();
    private int taken;
    private String status = "";

    public boolean active() { return s != S.IDLE; }
    public String status() { return status; }
    /** Items moved in the last run. */
    public int taken() { return taken; }

    public void begin(Map<Item, Integer> need) {
        needs = new HashMap<>(need);
        chests = ChestRegistry.all();
        chestIdx = 0;
        taken = 0;
        s = chests.isEmpty() || needs.isEmpty() ? S.IDLE : S.WALK;
        status = "restocking";
    }

    public void stop() {
        walker.stop();
        if (s == S.TAKE || s == S.WAIT_OPEN || s == S.CLOSE) closeMenu();
        s = S.IDLE;
    }

    /** Returns true when finished (successfully or not). */
    public boolean tick() {
        t++;
        if (s == S.IDLE) return true;
        var mc = Bot.mc();
        LocalPlayer p = mc.player;
        if (s != S.CLOSE && (chestIdx >= chests.size() || needs.isEmpty() || freeSlots() <= 1)) {
            if (s == S.TAKE || s == S.WAIT_OPEN) { s = S.CLOSE; timer = Config.closet() ? 4 + Bot.rng().nextInt(6) : 0; }
            else { s = S.IDLE; Rotator.clear(); return true; }
        }
        BlockPos chest = chestIdx < chests.size() ? chests.get(chestIdx) : null;
        switch (s) {
            case WALK -> {
                status = "walking to chest";
                if (Safety.inReach(chest)) { walker.stop(); s = S.OPEN; timer = 0; return false; }
                if (walker.busy()) { walker.tick(); return false; }
                if (walker.status() == Walker.Status.FAILED) { walker.stop(); chestIdx++; return false; }
                if (t - lastPlan < 5) return false;
                lastPlan = t;
                var path = PathFinder.find(p.blockPosition(), new ReachGoal(chest, Bot.reach() - 0.6), false, x -> false);
                if (path == null) { chestIdx++; return false; }
                walker.start(path);
                if (walker.status() == Walker.Status.DONE && !Safety.inReach(chest)) chestIdx++;
            }
            case OPEN -> {
                status = "opening chest";
                if (++timer > 60) { chestIdx++; s = S.WALK; return false; }
                if (!ChestRegistry.isContainer(mc.level.getBlockState(chest))) { chestIdx++; s = S.WALK; return false; }
                BlockHitResult hit;
                if (Config.closet()) {
                    Vec3 pt = Breaker.visiblePoint(chest);
                    if (pt == null) { chestIdx++; s = S.WALK; return false; }
                    float[] a = Bot.anglesTo(Bot.eye(), pt);
                    Rotator.aim(a[0], a[1]);
                    if (!Rotator.settled()) return false;
                    hit = PlacementSolver.raycast(Bot.eye(), p.getYRot(), p.getXRot(), Bot.reach() + 0.5);
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(chest)) return false;
                } else {
                    var face = Bot.faceToward(chest);
                    Vec3 c = Bot.faceCenter(chest, face);
                    float[] a = Bot.anglesTo(Bot.eye(), c);
                    Bot.faceNow(a[0], a[1]);
                    hit = new BlockHitResult(c, face, chest, false);
                }
                Bot.keys(false, false, false);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                p.swing(InteractionHand.MAIN_HAND);
                s = S.WAIT_OPEN;
                timer = 0;
            }
            case WAIT_OPEN -> {
                AbstractContainerMenu m = p.containerMenu;
                if (m instanceof InventoryMenu || m == null) {
                    if (++timer > 40) { chestIdx++; s = S.WALK; }
                    return false;
                }
                // give the server a moment to send contents (and look human)
                if (++timer < (Config.closet() ? 6 + Bot.rng().nextInt(5) : 3)) return false;
                s = S.TAKE;
                timer = 0;
            }
            case TAKE -> {
                status = "taking materials";
                AbstractContainerMenu m = p.containerMenu;
                if (m instanceof InventoryMenu) { s = S.WALK; chestIdx++; return false; }
                if (timer > 0) { timer--; return false; }
                int clicks = Config.closet() ? 1 : 6;
                int containerSlots = m.slots.size() - 36;
                boolean any = false;
                for (int i = 0; i < containerSlots && clicks > 0; i++) {
                    ItemStack st = m.getSlot(i).getItem();
                    if (st.isEmpty()) continue;
                    Integer need = needs.get(st.getItem());
                    if (need == null || need <= 0) continue;
                    if (freeSlots() <= 1 && !hasPartial(st)) break;
                    mc.gameMode.handleInventoryMouseClick(m.containerId, i, 0, ClickType.QUICK_MOVE, p);
                    int left = need - st.getCount();
                    if (left <= 0) needs.remove(st.getItem()); else needs.put(st.getItem(), left);
                    taken += st.getCount();
                    clicks--;
                    any = true;
                }
                if (!any) {
                    // nothing more useful here: close and try the next chest
                    s = S.CLOSE;
                    timer = Config.closet() ? 3 + Bot.rng().nextInt(6) : 0;
                } else if (Config.closet()) {
                    timer = 2 + Bot.rng().nextInt(3); // human click speed
                }
            }
            case CLOSE -> {
                if (timer-- > 0) return false;
                closeMenu();
                chestIdx++;
                s = (needs.isEmpty() || freeSlots() <= 1 || chestIdx >= chests.size()) ? S.IDLE : S.WALK;
                if (s == S.IDLE) { Rotator.clear(); return true; }
            }
            default -> {}
        }
        return false;
    }

    private static void closeMenu() {
        LocalPlayer p = Bot.mc().player;
        if (p != null && !(p.containerMenu instanceof InventoryMenu)) p.closeContainer();
    }

    private static int freeSlots() {
        Inventory inv = Bot.mc().player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) n++;
        return n;
    }

    private static boolean hasPartial(ItemStack st) {
        Inventory inv = Bot.mc().player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, st) && s.getCount() < s.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Count of an item in the player's inventory. */
    public static int have(Item item) {
        Inventory inv = Bot.mc().player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }
}
