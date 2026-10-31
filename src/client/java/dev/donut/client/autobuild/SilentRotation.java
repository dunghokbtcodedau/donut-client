package dev.donut.client.autobuild;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Silent rotation. The camera never turns: for the single movement packet that vanilla sends this tick
 * (LocalPlayer.sendPosition) the player's yaw/pitch are swapped for the placement's yaw/pitch and restored right
 * after, so the server sees the player looking at the block when the placement packet arrives, which follows at the
 * end of the same tick. No extra packet is sent; only the content of the regular one changes.
 */
public final class SilentRotation {
    private static boolean queued, applied, carried;
    private static float yaw, pitch, realYaw, realPitch;

    private SilentRotation() { }

    /** Use this yaw/pitch for the next movement packet. */
    public static void queue(float newYaw, float newPitch) {
        yaw = newYaw; pitch = newPitch; queued = true; applied = false; carried = false;
    }

    /** Forget a queued rotation without sending anything. */
    public static void cancel() { finish(); }

    /** Ends the cycle; returns true when this tick's movement packet really carried the queued rotation. */
    public static boolean finish() {
        boolean sent = carried;
        if (applied) { // sendPosition did not return normally: never leave the camera turned
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) { player.setYRot(realYaw); player.setXRot(realPitch); }
        }
        queued = false; applied = false; carried = false;
        return sent;
    }

    /** Called at the start of LocalPlayer.sendPosition. */
    public static void before(LocalPlayer player) {
        if (!queued || applied || carried) return;
        realYaw = player.getYRot(); realPitch = player.getXRot();
        player.setYRot(yaw); player.setXRot(pitch);
        applied = true;
    }

    /** Called when LocalPlayer.sendPosition returns. */
    public static void after(LocalPlayer player) {
        if (!applied) return;
        player.setYRot(realYaw); player.setXRot(realPitch);
        applied = false; carried = true;
    }
}
