package dev.donut.client.automine;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import java.util.List;

/**
 * The only class that touches Baritone. It is loaded lazily, and only after the controller has confirmed that
 * the "baritone-meteor" mod is present, so Donut Client still starts without Baritone installed.
 */
final class BaritoneLink {
    private BaritoneLink() { }

    private static IBaritone baritone() { return BaritoneAPI.getProvider().getPrimaryBaritone(); }

    /** quantity 0 means "until stopped". */
    static void mine(int quantity, List<String> names) {
        baritone().getMineProcess().mineByName(quantity, names.toArray(new String[0]));
    }

    static boolean active() { return baritone().getMineProcess().isActive(); }

    static void cancel() {
        baritone().getMineProcess().cancel();
        baritone().getPathingBehavior().cancelEverything();
    }
}
