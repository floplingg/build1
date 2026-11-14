package dev.flop.build1;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Talks to Litematica through reflection so Build1 has no compile-time dependency on it
 * (Litematica builds change often; this keeps working across versions).
 *
 * The key trick: Litematica keeps a "schematic world" with every loaded placement already
 * rotated/mirrored into real world coordinates. We just compare that world to the real one.
 */
public final class LitematicaBridge {

    /** Axis-aligned inclusive box. */
    public record Box(BlockPos min, BlockPos max) {
        public static Box of(BlockPos a, BlockPos b) {
            return new Box(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        }
        public Box union(Box o) {
            return new Box(new BlockPos(Math.min(min.getX(), o.min.getX()), Math.min(min.getY(), o.min.getY()), Math.min(min.getZ(), o.min.getZ())),
                    new BlockPos(Math.max(max.getX(), o.max.getX()), Math.max(max.getY(), o.max.getY()), Math.max(max.getZ(), o.max.getZ())));
        }
        public int sizeX() { return max.getX() - min.getX() + 1; }
        public int sizeY() { return max.getY() - min.getY() + 1; }
        public int sizeZ() { return max.getZ() - min.getZ() + 1; }
        public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }
        public boolean contains(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }
        @Override public String toString() {
            return min.toShortString() + " -> " + max.toShortString() + " (" + sizeX() + "x" + sizeY() + "x" + sizeZ() + ")";
        }
    }

    public record Placement(String name, Box box) {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** Litematica's schematic world (a Level subclass), or null. */
    public static Level schematicWorld() {
        if (!installed()) return null;
        try {
            Class<?> c = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            Object w = callStatic(c, "getSchematicWorld");
            if (w == null) {
                Object inst = staticField(c, "INSTANCE");
                if (inst != null) w = call(inst, "getSchematicWorld");
            }
            return w instanceof Level l ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The placement selected in Litematica's placement menu (falls back to the only/first loaded one). */
    public static Placement selectedPlacement() {
        if (!installed()) return null;
        try {
            Object mgr = callStatic(Class.forName("fi.dy.masa.litematica.data.DataManager"), "getSchematicPlacementManager");
            if (mgr == null) return null;
            Object pl = call(mgr, "getSelectedSchematicPlacement");
            if (pl == null) {
                Object all = call(mgr, "getAllSchematicsPlacements");
                if (all instanceof Collection<?> col && !col.isEmpty()) pl = col.iterator().next();
            }
            if (pl == null) return null;
            Box box = placementBox(pl);
            if (box == null) return null;
            Object name = call(pl, "getName");
            return new Placement(name == null ? "schematic" : name.toString(), box);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Litematica's current area selection box (for the miner), or null. */
    public static Box areaSelection() {
        if (!installed()) return null;
        try {
            Object sm = callStatic(Class.forName("fi.dy.masa.litematica.data.DataManager"), "getSelectionManager");
            if (sm == null) return null;
            Object sel = call(sm, "getCurrentSelection");
            if (sel == null) return null;
            Box total = null;
            Object boxes = call(sel, "getAllSubRegionBoxes");
            if (boxes instanceof Map<?, ?> m) boxes = m.values();
            if (boxes instanceof Collection<?> col) {
                for (Object o : col) {
                    Box b = toBox(o);
                    if (b != null) total = total == null ? b : total.union(b);
                }
            }
            if (total == null) total = toBox(call(sel, "getSelectedSubRegionBox"));
            return total;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Filter from Litematica's render layer range (Layer mode in its menu). Null = everything. */
    public static java.util.function.Predicate<BlockPos> layerFilter() {
        if (!installed()) return null;
        try {
            Object range = callStatic(Class.forName("fi.dy.masa.litematica.data.DataManager"), "getRenderLayerRange");
            if (range == null) return null;
            Method byPos = null, byInts = null;
            for (Method m : range.getClass().getMethods()) {
                if (!m.getName().equals("isPositionWithinRange")) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 1 && t[0].isAssignableFrom(BlockPos.class)) byPos = m;
                if (t.length == 3 && t[0] == int.class) byInts = m;
            }
            final Method fp = byPos, fi = byInts;
            if (fp == null && fi == null) return null;
            return pos -> {
                try {
                    Object r = fp != null ? fp.invoke(range, pos) : fi.invoke(range, pos.getX(), pos.getY(), pos.getZ());
                    return !(r instanceof Boolean b) || b;
                } catch (Throwable t) {
                    return true;
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private static Box placementBox(Object pl) {
        for (String m : new String[]{"getEclosingBox", "getEnclosingBox"}) {
            Box b = toBox(call(pl, m));
            if (b != null) return b;
        }
        // Fallback: union of all sub-region boxes
        Box total = null;
        for (String m : new String[]{"getSubRegionBoxes", "getAllSubRegionBoxes"}) {
            Object res = callAnyEnumArg(pl, m);
            if (res instanceof Map<?, ?> map) res = map.values();
            if (res instanceof Collection<?> col) {
                for (Object o : col) {
                    Box b = toBox(o);
                    if (b != null) total = total == null ? b : total.union(b);
                }
            }
            if (total != null) return total;
        }
        return null;
    }

    /** Accepts Litematica Box (getPos1/getPos2) or malilib IntBoundingBox (minX.. fields). */
    private static Box toBox(Object o) {
        if (o == null) return null;
        Object a = call(o, "getPos1"), b = call(o, "getPos2");
        if (a instanceof BlockPos p1 && b instanceof BlockPos p2) return Box.of(p1, p2);
        try {
            int[] v = new int[6];
            String[] n = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
            for (int i = 0; i < 6; i++) {
                Field f = o.getClass().getField(n[i]);
                v[i] = f.getInt(o);
            }
            return Box.of(new BlockPos(v[0], v[1], v[2]), new BlockPos(v[3], v[4], v[5]));
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- reflection helpers ----

    private static Object callStatic(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object staticField(Class<?> c, String name) {
        try { return c.getField(name).get(null); } catch (Throwable t) { return null; }
    }

    private static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = o.getClass().getMethod(name);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Calls a 0-arg method, or a 1-arg method taking an enum (passes its first constant, e.g. RequiredEnabled.ANY). */
    private static Object callAnyEnumArg(Object o, String name) {
        Object r = call(o, name);
        if (r != null) return r;
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
                try {
                    Object[] consts = m.getParameterTypes()[0].getEnumConstants();
                    Object pick = consts[0];
                    for (Object c : consts) if (c.toString().equalsIgnoreCase("ANY")) pick = c;
                    return m.invoke(o, pick);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private LitematicaBridge() {}
}
