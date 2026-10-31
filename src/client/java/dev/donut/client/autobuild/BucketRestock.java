package dev.donut.client.autobuild;

import dev.donut.client.automine.BaritoneLink;
import dev.donut.client.config.AutoBuilderSettings;
import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fetches lava and water when the builder runs out of them.
 *
 * <pre>
 *   /sethome 3  ->  /home 1  ->  find lava, walk there with Baritone, scoop with empty buckets
 *               ->  /home 2  ->  the same for water
 *               ->  /home 3  (back to the build)
 * </pre>
 *
 * The supply home for each fluid is configurable; the build home is {@code homeSlot}. Only the stops that are
 * actually needed are visited. The number of buckets filled never exceeds the number of empty buckets, the
 * quota for the fluid, or what the schematic still needs.
 */
final class BucketRestock {
    enum Outcome { RUNNING, DONE, FAILED }
    private enum Stage { IDLE, SETHOME, GO, SETTLE, FIND, WALK, AIM, VERIFY, RETURN, RETURN_WAIT, FAILED }
    private record Stop(Item item, int homeSlot, int quota) { }

    private static final int HOME_WAIT_TICKS = 20 * 15, SETTLE_TICKS = 60, WALK_TIMEOUT_TICKS = 20 * 90;
    private static final int SCAN_RADIUS = 28, SCAN_UP = 14, SCAN_DOWN = 14;

    private Stage stage = Stage.IDLE;
    private final Deque<Stop> stops = new ArrayDeque<>();
    private Stop stop;
    private final Set<BlockPos> bad = new HashSet<>();
    private BlockPos source;
    private Vec3 from;
    private String reason = "";
    private int tick, until, scooped, findAttempts, walkAttempts, useFailures, buildSlot, totalCollected, before;
    private int pinned = -1;
    private boolean sentHome;

    boolean active() { return stage != Stage.IDLE; }
    String reason() { return reason; }
    int collected() { return totalCollected; }

    void reset() {
        if (stage != Stage.IDLE) cancelBaritone();
        stage = Stage.IDLE; stops.clear(); stop = null; bad.clear(); source = null; from = null;
        totalCollected = 0; scooped = 0; sentHome = false;
    }

    /**
     * Starts a trip. {@code first} is the bucket that ran out; the other fluid is visited as well when it is also
     * needed and not in the inventory. Returns false when no trip is possible (no empty bucket to fill).
     */
    boolean start(Minecraft client, AutoBuilderSettings s, Item first, int lavaNeed, int waterNeed) {
        LocalPlayer player = client.player;
        int empties = count(player, Items.BUCKET);
        if (empties <= 0) return false;
        int needLava = Math.max(0, lavaNeed - count(player, Items.LAVA_BUCKET));
        int needWater = Math.max(0, waterNeed - count(player, Items.WATER_BUCKET));
        if (first == Items.LAVA_BUCKET) needLava = Math.max(needLava, 1); else needWater = Math.max(needWater, 1);
        int quotaLava, quotaWater;
        if (needLava > 0 && needWater > 0) {
            quotaLava = Math.min(needLava, (empties + 1) / 2);
            quotaWater = Math.min(needWater, empties - quotaLava);
        } else { quotaLava = Math.min(needLava, empties); quotaWater = Math.min(needWater, empties); }
        stops.clear();
        Stop lava = quotaLava > 0 ? new Stop(Items.LAVA_BUCKET, s.lavaHomeSlot, quotaLava) : null;
        Stop water = quotaWater > 0 ? new Stop(Items.WATER_BUCKET, s.waterHomeSlot, quotaWater) : null;
        if (first == Items.LAVA_BUCKET) { if (lava != null) stops.add(lava); if (water != null) stops.add(water); }
        else { if (water != null) stops.add(water); if (lava != null) stops.add(lava); }
        if (stops.isEmpty()) return false;
        buildSlot = s.homeSlot;
        totalCollected = 0; bad.clear(); stop = null; source = null;
        // Remember the build site first, unless the player stands somewhere unsafe (then the old home stays).
        boolean safe = player.onGround() && !LavaGuard.near(client.level, player.blockPosition())
                && !LavaGuard.plannedOnLayer(player.blockPosition()) && !LavaGuard.plannedOnLayer(player.blockPosition().above());
        if (safe) player.connection.sendCommand("sethome " + buildSlot);
        stage = Stage.SETHOME; until = tick + 20;
        reason = (first == Items.LAVA_BUCKET ? "Out of lava" : "Out of water") + (safe ? ", /sethome " + buildSlot + " done" : "");
        return true;
    }

    Outcome tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) return Outcome.RUNNING;
        tick++;
        if (stage == Stage.FAILED) return Outcome.FAILED; // set by fail(); the owner calls reset() after reading reason()
        switch (stage) {
            case SETHOME -> { if (tick >= until) nextStop(client); }
            case GO -> {
                if (player.position().distanceToSqr(from) > 16 * 16) { stage = Stage.SETTLE; until = tick + SETTLE_TICKS; reason = "Arrived at /home " + stop.homeSlot + ", loading the area"; }
                else if (tick >= until) return fail("/home " + stop.homeSlot + " did not move the player. Set that home first.");
            }
            case SETTLE -> { if (tick >= until) { stage = Stage.FIND; findAttempts = 0; } }
            case FIND -> find(client);
            case WALK -> walk(client);
            case AIM -> aim(client);
            case VERIFY -> verify(client);
            case RETURN -> {
                if (tick < until) break;
                player.connection.sendCommand("home " + buildSlot);
                from = player.position(); stage = Stage.RETURN_WAIT; until = tick + HOME_WAIT_TICKS;
                reason = "Going back to the build: /home " + buildSlot;
            }
            case RETURN_WAIT -> {
                if (player.position().distanceToSqr(from) > 16 * 16) { stage = Stage.IDLE; return Outcome.DONE; }
                if (tick >= until) return fail("/home " + buildSlot + " did not move the player back to the build.");
            }
            default -> { }
        }
        return Outcome.RUNNING;
    }

    // ---------------------------------------------------------------- stops

    private void nextStop(Minecraft client) {
        cancelBaritone();
        stop = stops.poll();
        if (stop == null) { stage = Stage.RETURN; until = tick + 10; reason = "Got " + totalCollected + " bucket(s), heading back"; return; }
        scooped = 0; bad.clear(); source = null;
        client.player.connection.sendCommand("home " + stop.homeSlot);
        from = client.player.position(); stage = Stage.GO; until = tick + HOME_WAIT_TICKS;
        reason = "Going to /home " + stop.homeSlot + " for " + stop.item.getName().getString();
    }

    private Outcome fail(String message) { reason = message; cancelBaritone(); stage = Stage.FAILED; return Outcome.FAILED; }

    private boolean stopDone(LocalPlayer player) {
        return scooped >= stop.quota || count(player, Items.BUCKET) <= 0;
    }

    private TagKey<Fluid> tag() { return stop.item == Items.LAVA_BUCKET ? FluidTags.LAVA : FluidTags.WATER; }

    private void find(Minecraft client) {
        if (stopDone(client.player)) { nextStop(client); return; }
        source = nearestSource(client);
        if (source == null) {
            if (++findAttempts > 6) { reason = "No " + (tag() == FluidTags.LAVA ? "lava" : "water") + " found near /home " + stop.homeSlot; nextStop(client); return; }
            reason = "Looking for " + (tag() == FluidTags.LAVA ? "lava" : "water") + "..."; until = tick + 30; stage = Stage.SETTLE; return;
        }
        walkAttempts = 0; useFailures = 0;
        startWalk(client);
    }

    private void startWalk(Minecraft client) {
        try { BaritoneLink.goTo(source, 2); }
        catch (RuntimeException | LinkageError e) { reason = "Baritone is not available: " + e.getMessage(); fail(reason); return; }
        stage = Stage.WALK; until = tick + 10; pinned = tick;
        reason = "Walking to " + (tag() == FluidTags.LAVA ? "lava" : "water") + " at " + source.getX() + " " + source.getY() + " " + source.getZ();
    }

    private void walk(Minecraft client) {
        if (aimPoint(client, source) != null) { cancelBaritone(); stage = Stage.AIM; useFailures = 0; return; }
        if (tick - pinned > WALK_TIMEOUT_TICKS) { blacklist(); return; }
        if (tick > until) {
            boolean active;
            try { active = BaritoneLink.goToActive(); } catch (RuntimeException | LinkageError e) { active = false; }
            if (!active) {
                if (++walkAttempts >= 3) { blacklist(); return; }
                startWalk(client);
            }
        }
    }

    private void blacklist() { bad.add(source); source = null; cancelBaritone(); stage = Stage.FIND; }

    // ---------------------------------------------------------------- scooping

    private void aim(Minecraft client) {
        LocalPlayer player = client.player;
        if (stopDone(player)) { nextStop(client); return; }
        if (!isSource(client, source)) { source = null; stage = Stage.FIND; return; } // taken (lava) or changed
        Vec3 point = aimPoint(client, source);
        if (point == null) { stage = Stage.WALK; startWalk(client); return; }
        int slot = emptyBucketSlot(player);
        if (slot < 0) { nextStop(client); return; }
        if (slot >= 9) {
            client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, player.getInventory().getSelectedSlot(), ClickType.SWAP, player);
            until = tick + 2; return;
        }
        if (player.getInventory().getSelectedSlot() != slot) { player.getInventory().setSelectedSlot(slot); return; }
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.setYRot(yaw); player.setXRot(pitch);
        var sent = (BuilderLocalPlayerAccessor) player;
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(sent.donut$lastSentYaw() - yaw)) > .01f
                || Math.abs(sent.donut$lastSentPitch() - pitch) > .01f) return; // wait until the server has the rotation
        before = count(player, stop.item);
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        stage = Stage.VERIFY; until = tick + 6;
        reason = "Scooping " + (scooped + 1) + "/" + stop.quota + " " + stop.item.getName().getString();
    }

    private void verify(Minecraft client) {
        if (tick < until) return;
        if (count(client.player, stop.item) > before) {
            scooped++; totalCollected++; useFailures = 0;
            if (!isSource(client, source)) source = null; // lava sources disappear when scooped
            stage = source == null ? Stage.FIND : Stage.AIM;
            if (source == null) findAttempts = 0;
        } else if (++useFailures >= 4) {
            blacklist();
        } else {
            stage = Stage.AIM;
        }
    }

    // ---------------------------------------------------------------- world scan

    private static boolean isSource(Minecraft client, BlockPos pos) {
        if (pos == null || client.level == null) return false;
        var state = client.level.getBlockState(pos);
        return (state.is(Blocks.LAVA) || state.is(Blocks.WATER)) && client.level.getFluidState(pos).isSource();
    }

    private BlockPos nearestSource(Minecraft client) {
        var level = client.level;
        BlockPos center = client.player.blockPosition();
        boolean lava = tag() == FluidTags.LAVA;
        BlockPos best = null; double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - SCAN_RADIUS; x <= center.getX() + SCAN_RADIUS; x++) {
            for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
                if (level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
                for (int y = center.getY() - SCAN_DOWN; y <= center.getY() + SCAN_UP; y++) {
                    if (y < level.getMinY() || y >= level.getMaxY()) continue;
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    if (!(lava ? state.is(Blocks.LAVA) : state.is(Blocks.WATER))) continue;
                    if (!level.getFluidState(pos).isSource() || bad.contains(pos) || !exposed(client, pos)) continue;
                    double distance = pos.distSqr(center);
                    if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
                }
            }
        }
        return best;
    }

    /** A source with at least one open face (air or similar) can actually be reached with a bucket. */
    private static boolean exposed(Minecraft client, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            var state = client.level.getBlockState(next);
            if (state.getFluidState().isEmpty() && state.getCollisionShape(client.level, next).isEmpty()) return true;
        }
        return false;
    }

    /** A point inside the source, in reach and with a clear line from the eyes; null when there is none yet. */
    private static Vec3 aimPoint(Minecraft client, BlockPos target) {
        if (target == null) return null;
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition();
        double reach = Math.min(4.3, player.blockInteractionRange() - .3);
        Vec3 c = Vec3.atCenterOf(target);
        List<Vec3> points = new ArrayList<>();
        points.add(c);
        for (Direction side : Direction.values()) points.add(c.add(side.getStepX() * .3, side.getStepY() * .3, side.getStepZ() * .3));
        points.sort(Comparator.comparingDouble(eye::distanceToSqr));
        for (Vec3 point : points) {
            if (eye.distanceTo(point) > reach) continue;
            BlockHitResult hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return point;
        }
        return null;
    }

    // ---------------------------------------------------------------- inventory

    static int count(LocalPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int emptyBucketSlot(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).is(Items.BUCKET)) return selected;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.BUCKET)) return i;
        return -1;
    }

    private static void cancelBaritone() {
        try { BaritoneLink.cancel(); } catch (RuntimeException | LinkageError ignored) { }
    }
}
