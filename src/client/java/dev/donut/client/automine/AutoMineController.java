package dev.donut.client.automine;

import dev.donut.client.config.AutoMineSettings;
import dev.donut.client.config.ClientConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Lets Baritone mine the selected ores and watches every block it breaks. A break counts as accepted only once the
 * block stays gone for a few ticks. If the server puts it back, Baritone is paused and this controller keeps
 * hitting that exact block until the server accepts the break, then hands control back to Baritone.
 */
public final class AutoMineController {
    public static final String BARITONE_ID = "baritone-meteor";
    private static AutoMineController instance;

    private static final int WATCH_TIMEOUT = 400;
    private static final int HAMMER_RESTART = 600;

    private final ClientConfig config;
    private final Map<BlockPos, Watch> watches = new HashMap<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private Hammer hammer;
    private boolean running;
    private boolean resume;
    private long tick;
    private long graceUntil;
    private int mined;

    private static final class Watch {
        final Block block;
        final long created;
        long brokenAt = -1;
        Watch(Block block, long created) { this.block = block; this.created = created; }
    }

    private static final class Hammer {
        final BlockPos pos;
        final Block block;
        final long started;
        int clearTicks;
        boolean sawAir;
        Hammer(BlockPos pos, Block block, long started) { this.pos = pos; this.block = block; this.started = started; }
    }

    public AutoMineController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static AutoMineController getInstance() { return instance; }
    public static boolean enabled() { return instance != null && instance.isActive(); }

    public boolean isActive() { return running && config.autoMine.enabled; }
    public static boolean baritoneInstalled() { return FabricLoader.getInstance().isModLoaded(BARITONE_ID); }

    public void setEnabled(boolean on) {
        AutoMineSettings s = config.autoMine;
        if (!on) { stop("Stopped"); return; }
        if (running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) { s.status = "Join a world first"; return; }
        if (!baritoneInstalled()) { s.status = "Install Baritone (baritone-meteor) to use AutoMine"; say(mc, s.status); return; }
        if (s.blockNames().isEmpty()) { s.status = "Pick at least one block to mine"; say(mc, s.status); return; }
        s.enabled = true;
        running = true;
        resume = true;
        mined = 0;
        hammer = null;
        watches.clear();
        attempts.clear();
        s.status = "Starting Baritone";
    }

    /** Called when leaving a world or closing the game. */
    public void disconnect() { stop("Stopped"); }

    private void stop(String status) {
        boolean wasRunning = running;
        running = false;
        config.autoMine.enabled = false;
        config.autoMine.status = status;
        hammer = null;
        watches.clear();
        if (wasRunning && baritoneInstalled()) {
            try { BaritoneLink.cancel(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    public void tick(Minecraft mc) {
        AutoMineSettings s = config.autoMine;
        if (!s.enabled) { if (running) stop("Stopped"); return; }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) { stop("Stopped"); return; }
        if (!running) { s.enabled = false; return; }
        tick++;
        try {
            if (hammer != null) { tickHammer(mc, player, level); return; }
            if (resume) {
                resume = false;
                int remaining = s.quantity > 0 ? s.quantity - mined : 0;
                if (s.quantity > 0 && remaining <= 0) { stop("Finished: mined " + mined); say(mc, "AutoMine finished: " + mined + " blocks"); return; }
                BaritoneLink.mine(remaining, s.blockNames());
                graceUntil = tick + 10;
                s.status = "Mining " + describeTargets(s);
            } else if (tick > graceUntil && !BaritoneLink.active()) {
                stop("Baritone finished or was stopped");
                say(mc, "AutoMine: Baritone is no longer mining.");
                return;
            }
            observe(mc, level);
        } catch (RuntimeException | LinkageError e) {
            stop("Baritone error: " + e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- watching Baritone's breaks

    private void observe(Minecraft mc, ClientLevel level) {
        AutoMineSettings s = config.autoMine;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK && watches.size() < 64) {
            BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
            BlockState state = level.getBlockState(pos);
            // Falling blocks legitimately re-form in the hole they leave, so they cannot be judged this way.
            if (!state.isAir() && !(state.getBlock() instanceof FallingBlock) && !watches.containsKey(pos)) {
                watches.put(pos.immutable(), new Watch(state.getBlock(), tick));
            }
        }
        if (!s.retryRejected) { watches.clear(); return; }
        int confirm = confirmTicks(mc);
        for (Iterator<Map.Entry<BlockPos, Watch>> it = watches.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Watch> entry = it.next();
            BlockPos pos = entry.getKey();
            Watch w = entry.getValue();
            Block now = level.getBlockState(pos).getBlock();
            if (w.brokenAt < 0) {
                if (now != w.block) w.brokenAt = tick;
                else if (tick - w.created > WATCH_TIMEOUT) it.remove();
            } else if (now == w.block) {
                // It was gone on the client and the server put it back: the break was rejected.
                it.remove();
                startHammer(mc, pos, w.block);
                return;
            } else if (tick - w.brokenAt >= confirm) {
                mined++;
                attempts.remove(pos);
                it.remove();
            }
        }
    }

    private int confirmTicks(Minecraft mc) {
        int latency = 0;
        if (mc.getConnection() != null && mc.player != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) latency = info.getLatency();
        }
        return Mth.clamp(6 + 2 * (latency / 50), 6, 40);
    }

    // ---------------------------------------------------------------- hitting a rejected block until it breaks

    private void startHammer(Minecraft mc, BlockPos pos, Block block) {
        AutoMineSettings s = config.autoMine;
        int tries = attempts.merge(pos, 1, Integer::sum);
        if (s.retryLimit > 0 && tries > s.retryLimit) {
            String where = pos.toShortString();
            stop("Server keeps refusing the block at " + where);
            say(mc, "AutoMine stopped: the server refused the block at " + where + " " + s.retryLimit + " times.");
            return;
        }
        try { BaritoneLink.cancel(); } catch (RuntimeException | LinkageError ignored) { }
        hammer = new Hammer(pos.immutable(), block, tick);
        s.status = "Server rejected " + block.getName().getString() + " at " + pos.toShortString() + ", hitting it again (" + tries + ")";
    }

    private void tickHammer(Minecraft mc, LocalPlayer player, ClientLevel level) {
        AutoMineSettings s = config.autoMine;
        Hammer h = hammer;
        BlockState state = level.getBlockState(h.pos);
        boolean present = state.getBlock() == h.block;
        if (!present) {
            h.sawAir = true;
            if (++h.clearTicks >= confirmTicks(mc)) {
                // Still gone after the server had time to answer: accepted.
                mined++;
                attempts.remove(h.pos);
                hammer = null;
                resume = true;
                s.status = "Block broke, handing back to Baritone";
            }
            return;
        }
        if (h.sawAir) { // it came back again after we broke it: another rejection
            h.sawAir = false;
            int tries = attempts.merge(h.pos, 1, Integer::sum);
            if (s.retryLimit > 0 && tries > s.retryLimit) {
                String where = h.pos.toShortString();
                stop("Server keeps refusing the block at " + where);
                say(mc, "AutoMine stopped: the server refused the block at " + where + " " + s.retryLimit + " times.");
                return;
            }
            s.status = "Server rejected it again at " + h.pos.toShortString() + " (" + tries + ")";
        }
        h.clearTicks = 0;
        if (tick - h.started > HAMMER_RESTART) { // stuck for 30 s; let Baritone re-plan, it will come back to it
            hammer = null;
            resume = true;
            s.status = "Re-planning around " + h.pos.toShortString();
            return;
        }
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() - 0.2;
        Vec3 aim = visiblePoint(level, player, eye, h.pos, reach);
        if (aim == null) {
            hammer = null;
            resume = true;
            s.status = "Out of reach of " + h.pos.toShortString() + ", Baritone will move closer";
            return;
        }
        selectBestTool(player, state);
        float[] rot = lookAt(eye, aim);
        player.setYRot(rot[0]);
        player.setXRot(rot[1]);
        Direction face = Direction.getApproximateNearest(eye.subtract(Vec3.atCenterOf(h.pos)));
        mc.gameMode.continueDestroyBlock(h.pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private Vec3 visiblePoint(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
        for (Direction d : Direction.values()) {
            Vec3 point = Vec3.atCenterOf(pos).add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
            if (eye.distanceTo(point) > reach) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return point;
        }
        return null;
    }

    private void selectBestTool(LocalPlayer player, BlockState state) {
        Inventory inventory = player.getInventory();
        int best = inventory.getSelectedSlot();
        float speed = inventory.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            float candidate = inventory.getItem(i).getDestroySpeed(state);
            if (candidate > speed) { speed = candidate; best = i; }
        }
        if (best != inventory.getSelectedSlot()) inventory.setSelectedSlot(best);
    }

    private static float[] lookAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * 180.0 / Math.PI);
        return new float[] {Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    private static String describeTargets(AutoMineSettings s) {
        List<String> names = new ArrayList<>(s.blockNames());
        return names.size() + " block types";
    }

    private static void say(Minecraft mc, String message) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Donut] " + message), false);
    }
}
