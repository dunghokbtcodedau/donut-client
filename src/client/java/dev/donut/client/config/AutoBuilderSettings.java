package dev.donut.client.config;

/** Preferences only; building always requires an explicit Start action. */
public final class AutoBuilderSettings {
    public boolean favorite = false;
    public double previewOpacity = .28;

    /** Last resort once stuck: jump and place a temporary block under the feet. If that fails, /home is used. */
    public boolean pillarUp = true;
    /** Allow parkour: sprint-jump across 1-2 block gaps and hop up/down single blocks while walking to a build spot. */
    public boolean parkour = true;
    /** Leave lava alone: avoid walking next to it and use /home when the player ends up inside it. */
    public boolean lavaGuard = true;
    /** Run /home when the builder makes no progress for 30 seconds. */
    public boolean autoHome = true;
    /**
     * /sethome one block above the layer being built, and at the end of each layer break the temporary supports.
     * /home is used only when the player falls or is stuck.
     */
    public boolean autoSetHome = true;
    /**
     * Silent rotation, only for directional blocks (facing/axis/rotation) and buckets: the yaw/pitch ride on the
     * regular movement packet of that tick and the vanilla placement follows. Other blocks are placed without any
     * rotation change. Off: nothing is turned at all.
     */
    public boolean autoRotate = true;
    /** Always stand one block above the layer being placed, climbing there first whenever the player is lower. */
    public boolean alwaysAbove = false;
    /** Home slot used by /home and /sethome (the build site). */
    public int homeSlot = 3;
    /**
     * When the lava or water buckets run out: /sethome at the build, /home to the supply, find and scoop with
     * Baritone, then /home back to the build.
     */
    public boolean autoRestock = true;
    /** Home next to lava (where lava buckets are filled). */
    public int lavaHomeSlot = 2;
    /** Home next to water (where water buckets are filled). */
    public int waterHomeSlot = 1;

    public void sanitize() {
        homeSlot = Math.clamp(homeSlot, 1, 100);
        lavaHomeSlot = Math.clamp(lavaHomeSlot, 1, 100);
        waterHomeSlot = Math.clamp(waterHomeSlot, 1, 100);
        previewOpacity = Double.isFinite(previewOpacity) ? Math.clamp(previewOpacity, .08, .8) : .28;
    }
}
