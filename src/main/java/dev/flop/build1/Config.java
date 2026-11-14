package dev.flop.build1;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Tiny properties-file config at config/build1.properties. */
public final class Config {
    public enum Mode {
        /** Snappy rotations, packet rotations, air-place, multiple blocks per tick. Fast, obvious. */
        BLATANT,
        /** Looks like a player: smooth mouse-like aim, only clicks what the crosshair really sees, human delays. */
        CLOSET
    }

    public static Mode mode = Mode.CLOSET;
    /** Blatant only: place blocks in mid-air with nothing to click against. */
    public static boolean airPlace = true;
    /** Only build what Litematica's render layers show (its Layer mode). */
    public static boolean followLayers = true;
    /** Fill sign text from the schematic and close the sign editor. */
    public static boolean signText = true;

    /** Survival: refill from materials chests when out of blocks (and every restockMinutes). */
    public static boolean survival = true;
    public static int restockMinutes = 2;
    /** Materials chests, saved as x,y,z;x,y,z */
    public static String chests = "";

    public static boolean closet() { return mode == Mode.CLOSET; }

    /** Ticks to wait between placing / starting to break blocks (0 = every tick). */
    public static int actionDelay = 1;
    /** Max blocks placed in one tick. */
    public static int blocksPerTick = 1;
    /** Walk around with the built-in pathfinder. Off = stand still and only touch what's in reach. */
    public static boolean walk = true;
    /** Allow jumping + placing a block under yourself to climb (uses dirt/cobble/netherrack etc). */
    public static boolean pillar = true;
    /** Break blocks that are in the way where the schematic wants a different block. */
    public static boolean replaceWrong = false;
    /** Break blocks inside the schematic box where the schematic has air. */
    public static boolean clearExtra = false;
    /** Hotbar slot (0-8) Build1 swaps items into from your inventory. */
    public static int hotbarSlot = 8;
    /** Miner: skip blocks touching lava. */
    public static boolean avoidLava = true;
    /** Pause everything if health drops to this (half-hearts). 0 = off. */
    public static int minHealth = 8;
    /** Reach used for placing / breaking (capped at your real interaction range). */
    public static double reach = 4.5;
    public static boolean hud = true;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("build1.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) { save(); return; }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException e) { return; }
        actionDelay = i(p, "actionDelay", actionDelay, 0, 20);
        blocksPerTick = i(p, "blocksPerTick", blocksPerTick, 1, 4);
        walk = b(p, "walk", walk);
        pillar = b(p, "pillar", pillar);
        replaceWrong = b(p, "replaceWrong", replaceWrong);
        clearExtra = b(p, "clearExtra", clearExtra);
        hotbarSlot = i(p, "hotbarSlot", hotbarSlot, 0, 8);
        avoidLava = b(p, "avoidLava", avoidLava);
        minHealth = i(p, "minHealth", minHealth, 0, 19);
        hud = b(p, "hud", hud);
        airPlace = b(p, "airPlace", airPlace);
        followLayers = b(p, "followLayers", followLayers);
        signText = b(p, "signText", signText);
        survival = b(p, "survival", survival);
        restockMinutes = i(p, "restockMinutes", restockMinutes, 1, 30);
        chests = p.getProperty("chests", "");
        try { mode = Mode.valueOf(p.getProperty("mode", mode.name()).trim().toUpperCase()); } catch (IllegalArgumentException ignored) {}
        try { reach = Math.max(2, Math.min(6, Double.parseDouble(p.getProperty("reach", "" + reach)))); } catch (NumberFormatException ignored) {}
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("actionDelay", "" + actionDelay);
        p.setProperty("blocksPerTick", "" + blocksPerTick);
        p.setProperty("walk", "" + walk);
        p.setProperty("pillar", "" + pillar);
        p.setProperty("replaceWrong", "" + replaceWrong);
        p.setProperty("clearExtra", "" + clearExtra);
        p.setProperty("hotbarSlot", "" + hotbarSlot);
        p.setProperty("avoidLava", "" + avoidLava);
        p.setProperty("minHealth", "" + minHealth);
        p.setProperty("reach", "" + reach);
        p.setProperty("hud", "" + hud);
        p.setProperty("airPlace", "" + airPlace);
        p.setProperty("followLayers", "" + followLayers);
        p.setProperty("signText", "" + signText);
        p.setProperty("survival", "" + survival);
        p.setProperty("restockMinutes", "" + restockMinutes);
        p.setProperty("chests", chests);
        p.setProperty("mode", mode.name());
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) { p.store(w, "Build1 settings"); }
        } catch (IOException ignored) {}
    }

    private static int i(Properties p, String k, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(k, "" + def).trim()))); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean b(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private Config() {}
}
