package dev.donut.client.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Baritone-driven mining. Never starts by itself when the game launches. */
public final class AutoMineSettings {
    public transient boolean enabled = false;
    public transient String status = "Idle";
    public boolean favorite = false;

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

    public void sanitize() {
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
