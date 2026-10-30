package dev.donut.client.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Baritone-driven mining (ores or a rectangular area). Never starts by itself when the game launches. */
public final class AutoMineSettings {
    public transient boolean enabled = false;
    public transient String status = "Idle";
    public boolean favorite = false;

    /** true: dig the rectangle between point 1 and point 2. false: the original ore mining. */
    public boolean areaMode = true;
    public boolean hasPoint1, hasPoint2;
    public int p1x, p1y, p1z, p2x, p2y, p2z;

    public boolean diamond = true;
    public boolean ancientDebris = false;
    public boolean iron = false;
    public boolean gold = false;
    public boolean coal = false;
    public boolean redstone = false;
    public boolean lapis = false;
    public boolean emerald = false;
    public boolean copper = false;
    public boolean quartz = false;
    /** Extra block ids (for example "minecraft:obsidian"), edited in the config file. */
    public List<String> customBlocks = new ArrayList<>();

    /** Re-break a block whenever the server puts it back after the client broke it. */
    public boolean retryRejected = true;
    /** 0 keeps hitting the same block until it really breaks. */
    public int retryLimit = 0;
    /** 0 mines until stopped. */
    public int quantity = 0;

    public boolean areaReady() { return hasPoint1 && hasPoint2; }

    public void setPoint1(int x, int y, int z) { p1x = x; p1y = y; p1z = z; hasPoint1 = true; }
    public void setPoint2(int x, int y, int z) { p2x = x; p2y = y; p2z = z; hasPoint2 = true; }
    public void clearPoints() { hasPoint1 = false; hasPoint2 = false; }

    public int minX() { return Math.min(p1x, p2x); }
    public int maxX() { return Math.max(p1x, p2x); }
    public int minY() { return Math.min(p1y, p2y); }
    public int maxY() { return Math.max(p1y, p2y); }
    public int minZ() { return Math.min(p1z, p2z); }
    public int maxZ() { return Math.max(p1z, p2z); }

    /** Number of blocks inside the selected area, 0 when a point is missing. */
    public long areaVolume() {
        if (!areaReady()) return 0;
        return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
    }

    public void sanitize() {
        final int limit = 30_000_000;
        p1x = Math.clamp(p1x, -limit, limit); p2x = Math.clamp(p2x, -limit, limit);
        p1z = Math.clamp(p1z, -limit, limit); p2z = Math.clamp(p2z, -limit, limit);
        p1y = Math.clamp(p1y, -2048, 2048); p2y = Math.clamp(p2y, -2048, 2048);
        retryLimit = Math.clamp(retryLimit, 0, 200);
        quantity = Math.clamp(quantity, 0, 2048);
        if (status == null) status = "Idle";
        List<String> clean = new ArrayList<>();
        if (customBlocks != null) {
            for (String id : customBlocks) {
                if (id == null) continue;
                String value = id.trim().toLowerCase(java.util.Locale.ROOT);
                if (value.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?") && value.length() <= 64 && clean.size() < 32) clean.add(value);
            }
        }
        customBlocks = clean;
    }

    /** Block names handed to Baritone, deepslate variants included. */
    public List<String> blockNames() {
        Set<String> names = new LinkedHashSet<>();
        if (diamond) { names.add("diamond_ore"); names.add("deepslate_diamond_ore"); }
        if (ancientDebris) names.add("ancient_debris");
        if (iron) { names.add("iron_ore"); names.add("deepslate_iron_ore"); }
        if (gold) { names.add("gold_ore"); names.add("deepslate_gold_ore"); names.add("nether_gold_ore"); }
        if (coal) { names.add("coal_ore"); names.add("deepslate_coal_ore"); }
        if (redstone) { names.add("redstone_ore"); names.add("deepslate_redstone_ore"); }
        if (lapis) { names.add("lapis_ore"); names.add("deepslate_lapis_ore"); }
        if (emerald) { names.add("emerald_ore"); names.add("deepslate_emerald_ore"); }
        if (copper) { names.add("copper_ore"); names.add("deepslate_copper_ore"); }
        if (quartz) names.add("nether_quartz_ore");
        if (customBlocks != null) names.addAll(customBlocks);
        return new ArrayList<>(names);
    }
}
