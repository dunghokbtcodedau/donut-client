package dev.donut.client.autobuild;

import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Mines only support blocks this builder placed and later received from the server. */
public final class SupportCleanup {
    public enum Result { ACTIVE, DONE, BLOCKED }
    private static final int SERVER_TIMEOUT_TICKS = 20 * 45;
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<BlockPos,Integer> failures = new HashMap<>();
    private BlockPos mining;
    private Direction face;
    private int ticks;
    private boolean started, serverRemoved, serverRejected;
    private String reason = "Checking temporary cobbled-deepslate supports...";

    public String reason() { return reason; }

    /** Stops transient mining state but leaves the caller's ownership set untouched. */
    public void reset(Minecraft client) {
        stop(client);
        skipped.clear(); failures.clear(); ticks=0;
        reason="Checking temporary cobbled-deepslate supports...";
    }

    /** Receives only server block updates through BuilderPacketMixin. */
    public void serverBlockUpdate(BlockPos pos,BlockState state,Set<BlockPos> owned) {
        if (owned.contains(pos) && !state.is(Blocks.COBBLED_DEEPSLATE)) owned.remove(pos);
        if (mining==null || !mining.equals(pos)) return;
        if (state.isAir()) serverRemoved=true;
        else serverRejected=true;
    }

    public Result tick(Minecraft client,BuilderNavigator navigator,Set<BlockPos> owned,Set<BlockPos> schematic) {
        if (client==null || client.player==null || client.level==null || client.gameMode==null) {
            reason="Cannot clean supports without an active world."; return Result.BLOCKED;
        }
        if (owned.isEmpty()) { stop(client); reason="No temporary supports remain."; return Result.DONE; }
        if (mining!=null) {
            if (serverRemoved) {
                owned.remove(mining);
                BlockPos removed=mining;
                        clearMining();
                reason="Server confirmed support removal at "+coordinates(removed)+".";
                return Result.ACTIVE;
            }
            if (serverRejected) {
                BlockPos rejected=mining;
                int attempt=failures.merge(rejected,1,Integer::sum);
                stop(client); skipped.add(rejected);
                if (attempt>=2) { reason="Server rejected mining at "+coordinates(rejected)+"; the support was left in place."; return Result.BLOCKED; }
                reason="Mining was rejected; skipping this support.";
                return Result.ACTIVE;
            }
            if (!MaterialTracker.isLoaded(client.level,mining)) {
                stop(client); reason="Support area unloaded while mining; no block was counted as removed."; return Result.BLOCKED;
            }
            BlockState current=client.level.getBlockState(mining);
            if (current.isAir()) {
                reason="Waiting for the server to confirm predicted support break at "+coordinates(mining)+".";
                return Result.ACTIVE;
            }
            if (!current.is(Blocks.COBBLED_DEEPSLATE) || schematic.contains(mining)) {
                skipped.add(mining); stop(client); reason="Support changed or overlaps a schematic cell; leaving it untouched."; return Result.ACTIVE;
            }
            if (!safeToMine(client,mining,owned,schematic)) {
                skipped.add(mining); stop(client); reason="A support is adjacent to the finished build, a non-owned block, or the player; keeping it."; return Result.ACTIVE;
            }
            if (ticks++>SERVER_TIMEOUT_TICKS) {
                stop(client); reason="No server block-update confirmation after mining; support was retained."; return Result.BLOCKED;
            }
            PlacementPlanner.Plan plan=PlacementPlanner.breakPlan(client,mining);
            if (plan==null || !plan.hit().getBlockPos().equals(mining)) {
                BlockPos target=mining;
                stop(client);
                BuilderNavigator.Result movement=navigator.moveTo(client,target);
                if (movement==BuilderNavigator.Result.NO_PATH || movement==BuilderNavigator.Result.STUCK || movement==BuilderNavigator.Result.ARRIVED) {
                    skipped.add(target); navigator.reset();
                }
                reason="Moving within vanilla reach of temporary support at "+coordinates(target)+".";
                return Result.ACTIVE;
            }
            navigator.hold(client,false);
            client.player.setYRot(plan.yaw()); client.player.setXRot(plan.pitch());
            if (!rotationSent(client,plan)) {
                reason="Aligning the normal player rotation before mining support at "+coordinates(mining)+".";
                return Result.ACTIVE;
            }
            if (!started) {
                face=plan.hit().getDirection();
                started=client.gameMode.startDestroyBlock(mining,face);
                if (!started) {
                    BlockPos failed=mining; failures.merge(failed,1,Integer::sum); skipped.add(failed); stop(client);
                    reason="Vanilla could not start mining support at "+coordinates(failed)+".";
                    return Result.ACTIVE;
                }
                reason="Mining bot-placed support at "+coordinates(mining)+".";
                return Result.ACTIVE;
            }
            client.gameMode.continueDestroyBlock(mining,face);
            reason="Mining bot-placed support at "+coordinates(mining)+"; waiting for server confirmation.";
            return Result.ACTIVE;
        }

        List<BlockPos> candidates=new ArrayList<>(owned);
        candidates.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
                .thenComparingDouble(pos->client.player.position().distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)));
        for (BlockPos pos:candidates) {
            if (skipped.contains(pos)) continue;
            if (!MaterialTracker.isLoaded(client.level,pos)) { skipped.add(pos); continue; }
            BlockState state=client.level.getBlockState(pos);
            if (state.isAir() || !state.is(Blocks.COBBLED_DEEPSLATE) || schematic.contains(pos)) { skipped.add(pos); continue; }
            if (!safeToMine(client,pos,owned,schematic)) { skipped.add(pos); continue; }
            PlacementPlanner.Plan plan=PlacementPlanner.breakPlan(client,pos);
            if (plan==null) {
                BuilderNavigator.Result movement=navigator.moveTo(client,pos);
                if (movement==BuilderNavigator.Result.NO_PATH || movement==BuilderNavigator.Result.STUCK || movement==BuilderNavigator.Result.ARRIVED) {
                    skipped.add(pos); navigator.reset(); continue;
                }
                reason="Walking into vanilla reach of temporary support at "+coordinates(pos)+".";
                return Result.ACTIVE;
            }
            mining=pos.immutable(); ticks=0; started=false; serverRemoved=false; serverRejected=false;
            reason="Preparing to mine bot-placed support at "+coordinates(pos)+".";
            return Result.ACTIVE;
        }
        stop(client);
        if (owned.isEmpty()) reason="All bot-placed cobbled-deepslate supports were removed and server-confirmed.";
        else reason=owned.size()+" cobbled-deepslate support block(s) remain; kept because they could support the build/player or were unreachable.";
        return Result.DONE;
    }

    private static boolean safeToMine(Minecraft client,BlockPos pos,Set<BlockPos> owned,Set<BlockPos> schematic) {
        if (client.player.onGround() && client.player.blockPosition().below().equals(pos)) return false;
        AABB playerBox=client.player.getBoundingBox();
        AABB blockBox=new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
        if (playerBox.intersects(blockBox)) return false;
        for (Direction direction:Direction.values()) {
            BlockPos neighbor=pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) return false;
            if (schematic.contains(neighbor)) return false;
            if (owned.contains(neighbor)) continue;
            BlockState state=client.level.getBlockState(neighbor);
            if (state.isAir()) continue;
            // A full block below is only a foundation, not something resting on this support.
            if (direction==Direction.DOWN && state.isCollisionShapeFullBlock(client.level,neighbor)) continue;
            return false;
        }
        return true;
    }

    private static boolean rotationSent(Minecraft client,PlacementPlanner.Plan plan) {
        BuilderLocalPlayerAccessor sent=(BuilderLocalPlayerAccessor)client.player;
        return Math.abs(Mth.wrapDegrees(sent.donut$lastSentYaw()-plan.yaw()))<=.001f
                && Math.abs(Mth.wrapDegrees(sent.donut$lastSentPitch()-plan.pitch()))<=.001f;
    }

    private void stop(Minecraft client) {
        if (mining!=null && started && !serverRemoved && client!=null && client.gameMode!=null)
            client.gameMode.stopDestroyBlock();
                clearMining();
    }
    private void clearMining() {
        mining=null; face=null; started=false; serverRemoved=false; serverRejected=false; ticks=0;
    }
    private static String coordinates(BlockPos pos) { return pos.getX()+", "+pos.getY()+", "+pos.getZ(); }
}
