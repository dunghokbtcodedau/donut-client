package dev.donut.client.config;

/** Preferences only; building always requires an explicit Start action. */
public final class AutoBuilderSettings {
    public boolean favorite = false;
    public double previewOpacity = .28;

    /** Climb to blocks that are too high by jumping and placing a temporary block under the feet. */
    public boolean pillarUp = true;
    /** Leave lava alone: avoid walking next to it and use /home when the player ends up inside it. */
    public boolean lavaGuard = true;
    /** Run /home when the builder makes no progress for 30 seconds. */
    public boolean autoHome = true;
    /** Run /sethome after each finished layer, once the player stands on the next one. */
    public boolean autoSetHome = true;
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
