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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Two modes. Ore mode lets Baritone mine the selected ores. Area mode digs the rectangle between point 1 and
 * point 2: it first walks to the block that was left half-dug (or, on a fresh start, to the nearest corner) and
 * only then lets Baritone clear the whole area.
 *
 * Ore mode: lets Baritone mine the selected ores and watches every block it breaks. A break counts as accepted only once the
 * block stays gone for a few ticks. If the server puts it back, Baritone is paused and this controller keeps
 * hitting that exact block until the server accepts the break, then hands control back to Baritone.
 */
public final class AutoMineController {
    public static final String BARITONE_ID = "baritone-meteor";
    private static AutoMineController instance;

    private static final int WATCH_TIMEOUT = 400;
    private static final int HAMMER_RESTART = 600;
    private static final long MAX_AREA_VOLUME = 2_000_000L;
    private static final int GOTO_TIMEOUT = 20 * 120;
    private static final int GOTO_RANGE = 3;

    private enum Phase { GOTO, DIG }

    private final ClientConfig config;
    private final Map<BlockPos, Watch> watches = new HashMap<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private Hammer hammer;
    private boolean running;
    private boolean resume;
    private long tick;
    private long graceUntil;
    private int mined;
    private Phase phase = Phase.GOTO;
    private BlockPos gotoTarget;
    private BlockPos lastDig;
    private long phaseStart;
    private int gotoAttempts;
    private int digRestarts;
    private boolean gotoIssued;

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
        if (s.areaMode) {
            if (!s.areaReady()) { s.status = "Set point 1 and point 2 first"; say(mc, s.status); return; }
            if (s.areaVolume() > MAX_AREA_VOLUME) { s.status = "Area is too large (max " + MAX_AREA_VOLUME + " blocks)"; say(mc, s.status); return; }
        } else if (s.blockNames().isEmpty()) { s.status = "Pick at least one block to mine"; say(mc, s.status); return; }
        s.enabled = true;
        running = true;
        resume = true;
        mined = 0;
        hammer = null;
        watches.clear();
        attempts.clear();
        phase = Phase.GOTO;
        gotoTarget = null;
        gotoIssued = false;
        gotoAttempts = 0;
        digRestarts = 0;
        s.status = "Starting Baritone";
    }

    /** Stores the block the player looks at (or stands on) as point 1 (index 1) or point 2 (index 2). */
    public void setPoint(int index) {
        Minecraft mc = Minecraft.getInstance();
        AutoMineSettings s = config.autoMine;
        if (mc.player == null || mc.level == null) { s.status = "Join a world first"; return; }
        BlockPos pos = mc.player.blockPosition().below();
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        if (index == 1) s.setPoint1(pos.getX(), pos.getY(), pos.getZ()); else s.setPoint2(pos.getX(), pos.getY(), pos.getZ());
        lastDig = null;
        s.status = "Point " + index + " = " + pos.toShortString();
        say(mc, s.status);
    }

    public void clearPoints() {
        config.autoMine.clearPoints();
        lastDig = null;
        config.autoMine.status = "Points cleared";
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
            if (s.areaMode) { tickArea(mc, player, level); return; }
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

    // ---------------------------------------------------------------- area mode

    private void tickArea(Minecraft mc, LocalPlayer player, ClientLevel level) {
        AutoMineSettings s = config.autoMine;
        BlockPos a = new BlockPos(s.p1x, s.p1y, s.p1z);
        BlockPos b = new BlockPos(s.p2x, s.p2y, s.p2z);
        if (phase == Phase.GOTO) {
            if (!gotoIssued) {
                gotoTarget = chooseStart(player, level, s);
                if (gotoTarget == null) { startDig(s, a, b); return; }
                if (isNear(player, gotoTarget)) { startDig(s, a, b); return; }
                BaritoneLink.goTo(gotoTarget, GOTO_RANGE);
                gotoIssued = true;
                graceUntil = tick + 10;
                phaseStart = tick;
                s.status = (lastDigStillValid(level, s) ? "Going to the half-dug block " : "Going to the corner ") + gotoTarget.toShortString();
            } else if (isNear(player, gotoTarget)) {
                startDig(s, a, b);
            } else if (tick - phaseStart > GOTO_TIMEOUT) {
                startDig(s, a, b); // Baritone will walk there itself while digging
            } else if (tick > graceUntil && !BaritoneLink.goToActive()) {
                if (++gotoAttempts >= 3) startDig(s, a, b);
                else gotoIssued = false; // path ended early, plan again
            }
            return;
        }
        if (resume) {
            resume = false;
            BaritoneLink.clearArea(a, b);
            graceUntil = tick + 20;
            s.status = "Digging area";
        } else if (tick > graceUntil && !BaritoneLink.builderActive()) {
            int left = remaining(level, s);
            if (left == 0) {
                stop("Finished: area cleared");
                say(mc, "AutoMine finished: the area is clear.");
                return;
            }
            if (++digRestarts > 5) {
                stop("Baritone cannot reach the last " + left + " blocks");
                say(mc, "AutoMine stopped: " + left + " blocks could not be dug.");
                return;
            }
            BaritoneLink.clearArea(a, b);
            graceUntil = tick + 20;
            s.status = "Digging area";
        }
        observe(mc, level);
    }

    private void startDig(AutoMineSettings s, BlockPos a, BlockPos b) {
        try { BaritoneLink.cancel(); } catch (RuntimeException | LinkageError ignored) { }
        phase = Phase.DIG;
        gotoTarget = null;
        gotoIssued = false;
        BaritoneLink.clearArea(a, b);
        graceUntil = tick + 20;
        s.status = "Digging area";
    }

    /** The block that was being dug when AutoMine stopped, otherwise the nearest corner that still has blocks. */
    private BlockPos chooseStart(LocalPlayer player, ClientLevel level, AutoMineSettings s) {
        if (lastDigStillValid(level, s)) return lastDig;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int[] xs = {s.minX(), s.maxX()};
        int[] zs = {s.minZ(), s.maxZ()};
        for (int x : xs) {
            for (int z : zs) {
                for (int y = s.maxY(); y >= s.minY(); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!diggable(level, pos, level.getBlockState(pos))) continue;
                    double d = player.position().distanceToSqr(Vec3.atCenterOf(pos));
                    if (d < bestDist) { bestDist = d; best = pos; }
                    break; // highest solid block of this corner column
                }
            }
        }
        return best;
    }

    private boolean lastDigStillValid(ClientLevel level, AutoMineSettings s) {
        return lastDig != null && inArea(s, lastDig) && diggable(level, lastDig, level.getBlockState(lastDig));
    }

    private static boolean isNear(LocalPlayer player, BlockPos target) {
        double reach = GOTO_RANGE + 1.5;
        return player.position().distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach;
    }

    private static boolean inArea(AutoMineSettings s, BlockPos p) {
        return p.getX() >= s.minX() && p.getX() <= s.maxX()
                && p.getY() >= s.minY() && p.getY() <= s.maxY()
                && p.getZ() >= s.minZ() && p.getZ() <= s.maxZ();
    }

    private static boolean diggable(ClientLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0;
    }

    /** Blocks still to dig in the area. Unloaded chunks count as unfinished so the job is never declared done early. */
    private int remaining(ClientLevel level, AutoMineSettings s) {
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = s.minX(); x <= s.maxX(); x++) {
            for (int z = s.minZ(); z <= s.maxZ(); z++) {
                if (level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) { count++; continue; }
                for (int y = s.minY(); y <= s.maxY(); y++) {
                    pos.set(x, y, z);
                    if (diggable(level, pos, level.getBlockState(pos))) count++;
                }
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- watching Baritone's breaks

    private void observe(Minecraft mc, ClientLevel level) {
        AutoMineSettings s = config.autoMine;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK && watches.size() < 64) {
            BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
            BlockState state = level.getBlockState(pos);
            // Falling blocks legitimately re-form in the hole they leave, so they cannot be judged this way.
            if (s.areaMode && inArea(s, pos) && diggable(level, pos, state)) lastDig = pos.immutable();
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
