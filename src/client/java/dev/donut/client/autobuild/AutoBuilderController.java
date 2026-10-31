package dev.donut.client.autobuild;

import dev.donut.client.automine.AutoMineController;
import dev.donut.client.automine.BaritoneLink;
import dev.donut.client.config.AutoBuilderSettings;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.BuilderClientLevelAccessor;
import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Vanilla interactions, bounded walking and server-confirmed construction. All access is on the client thread. */
public final class AutoBuilderController {
    public enum Status { IDLE, READY, RUNNING, PAUSED, COMPLETED }
    private static final AutoBuilderController INSTANCE = new AutoBuilderController();
    private final BuilderNavigator navigator = new BuilderNavigator();
    private final SupportCleanup cleanup = new SupportCleanup();
    private Schematic schematic;
    private PlacementTransform transform = new PlacementTransform(BlockPos.ZERO, 0, false);
    private boolean previewVisible;
    private Status status = Status.IDLE;
    private String reason = "Choose a schematic.";
    private ClientLevel session;
    private List<Target> targets = List.of();
    private Target[] byIndex = new Target[0];
    private final NavigableSet<Target> work = new TreeSet<>(Comparator.comparingInt((Target t)->t.pos.getY()).thenComparingInt(Target::index));
    private final Map<BlockPos, Integer> indices = new HashMap<>();
    private final BitSet correct = new BitSet();
    /** Blocks the builder can never place (flowing fluids, grown or lit states). They never hold a layer back. */
    private final BitSet skipped = new BitSet();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Set<BlockPos> deferred = new HashSet<>();
    private final Set<BlockPos> supportBlocks = new HashSet<>();
    private final Map<BlockPos,Integer> unconfirmed = new HashMap<>();
    private ClientLevel predictionLevel;
    private Target current;
    private Prepared prepared;
    private Pending pending;
    private int tick, scanIndex, cooldown, noProgress, revalidateIndex, finalCheckIndex, originalSlot = -1;
    private boolean scanned;
    private String lastFailure = "";
    private BuilderProgressStore progressStore;
    private int lastSavedCount = -1;
    private String restored = "";

    // ---- safety, climbing and recovery state
    private static final int STUCK_TICKS = 20 * 30, HOME_WAIT_TICKS = 20 * 15, HOME_COOLDOWN_TICKS = 20 * 3;
    private static final int MAX_FAILED_JUMPS = 6, PILLAR_TIMEOUT = 20 * 90;
    private static final Item SCAFFOLD = Items.COBBLED_DEEPSLATE;
    private AutoBuilderSettings settings = new AutoBuilderSettings();
    private Pillar pillar;
    private int layerY = Integer.MIN_VALUE, homeLayer = Integer.MIN_VALUE, setHomeUntil = -1;
    private boolean pendingSetHome;
    private net.minecraft.world.phys.Vec3 progressAnchor, homeFrom;
    private final BucketRestock restock = new BucketRestock();
    // End of a layer: walk to a spot above it, /sethome, break the temporary supports, /home back.
    private enum Wrap { NONE, WALK, SETHOME, CLEAN }
    private Wrap wrap = Wrap.NONE;
    private int wrapLayer, wrapStart, wrapUntil, wrapStuck;
    private BlockPos wrapCell;
    private int homeSetLayer = Integer.MIN_VALUE, noRouteTicks;
    private boolean wrapHomeSet, wrapSent, wrapHadSupports, justFell, relaxAbove;
    private net.minecraft.world.phys.Vec3 wrapFrom;
    private int restockFails;
    private double groundY = Double.NaN;
    private int lastFallTick = -100000;
    private static final int STUCK_HOME_TICKS = 20;
    private static final int FALL_BLOCKS = 3, FALL_MEMORY_TICKS = 20 * 30;
    private int progressBlocks = -1, progressSupports = -1, progressTick, stuckRecoveries, homeWaitUntil = -1, lastHomeTick = -100000;

    private static final class Pillar {
        final Target goal; final int started;
        BlockPos base; boolean airborne, placedThisJump; int failed;
        Pillar(Target goal, int started) { this.goal = goal; this.started = started; }
    }

    private record Target(BlockPos pos, BlockState state, int index, boolean support) { }
    private record Prepared(Target target, PlacementPlanner.Plan plan, int readyTick) { }
    private static final class Pending {
        final Target target;
        final BlockState before, predicted;
        final int sentTick, sequence;
        boolean acknowledged;
        BlockState observed;
        Pending(Target target, BlockState before, BlockState predicted, int sentTick, int sequence) {
            this.target=target; this.before=before; this.predicted=predicted; this.sentTick=sentTick; this.sequence=sequence;
        }
    }
    private AutoBuilderController() { }
    public static AutoBuilderController get() { return INSTANCE; }
    /** Hooks the persisted preferences (climbing, lava guard, /home and /sethome) into the builder. */
    public void configure(AutoBuilderSettings value) {
        settings = value == null ? new AutoBuilderSettings() : value;
        navigator.avoidLava = settings.lavaGuard;
    }
    private void resetSafety() {
        pillar = null; layerY = Integer.MIN_VALUE; homeLayer = Integer.MIN_VALUE; pendingSetHome = false; setHomeUntil = -1;
        progressAnchor = null; progressBlocks = -1; progressSupports = -1; progressTick = tick; stuckRecoveries = 0;
        homeWaitUntil = -1; homeFrom = null; navigator.avoidLava = settings.lavaGuard;
        groundY = Double.NaN; lastFallTick = -100000; justFell = false;
        wrap = Wrap.NONE; wrapCell = null; relaxAbove = false; homeSetLayer = Integer.MIN_VALUE; noRouteTicks = 0;
        LavaGuard.setPlannedLayer(Integer.MIN_VALUE);
    }
    public Schematic schematic() { return schematic; }
    public PlacementTransform transform() { return transform; }
    public boolean previewVisible() { return previewVisible; }
    public void setPreviewVisible(boolean value) { previewVisible = value && schematic != null; }
    public Status status() { return status; }
    public String reason() { return reason; }
    public boolean isRunning() { return status == Status.RUNNING; }
    public int confirmedBlocks() { return correct.cardinality(); }
    public double progress() { int total=totalBlocks(); return total<=0 ? (targets.isEmpty() ? 0 : 1) : (double)correct.cardinality()/total; }
    public int skippedBlocks() { return skipped.cardinality(); }
    public int supportsPlaced() { return supportBlocks.size(); }
    /** Blocks the builder is responsible for: everything except the ones it must skip. */
    public int totalBlocks() { return targets.size()-skipped.cardinality(); }

    // ---------------------------------------------------------------- saved progress

    private BuilderProgressStore store() {
        if (progressStore == null) {
            try { progressStore = new BuilderProgressStore(FabricLoader.getInstance().getConfigDir().resolve("donut-client").resolve("builder-progress")); }
            catch (RuntimeException | LinkageError e) { return null; }
        }
        return progressStore;
    }

    /** Every confirmed block, grouped by layer. Temporary supports are never recorded. */
    private BuilderProgressStore.Snapshot snapshot() {
        TreeMap<Integer, List<int[]>> layers = new TreeMap<>();
        for (int i = correct.nextSetBit(0); i >= 0; i = correct.nextSetBit(i + 1)) {
            Target target = byIndex[i];
            if (target != null) layers.computeIfAbsent(target.pos.getY(), key -> new ArrayList<>()).add(new int[]{target.pos.getX(), target.pos.getZ()});
        }
        BlockPos size = schematic.dimensions(), origin = transform.origin();
        return new BuilderProgressStore.Snapshot(schematic.name(), schematic.blocks().size(), new int[]{size.getX(), size.getY(), size.getZ()},
                new int[]{origin.getX(), origin.getY(), origin.getZ()}, transform.quarterTurns(), transform.mirrorX(),
                correct.cardinality(), totalBlocks(), layers);
    }

    /** Never replaces an existing file with an empty result, so a wrong position cannot wipe saved progress. */
    public void saveProgress() {
        if (schematic == null || targets.isEmpty() || correct.isEmpty()) return;
        BuilderProgressStore store = store();
        if (store == null) return;
        store.save(snapshot());
        lastSavedCount = correct.cardinality();
    }

    /** Reads the file again (it may have been edited) when it belongs to the current schematic and position. */
    private boolean loadProgress() {
        restored = "";
        if (schematic == null || targets.isEmpty()) return false;
        BuilderProgressStore store = store();
        if (store == null) return false;
        BuilderProgressStore.Snapshot saved = store.load(schematic.name(), schematic.blocks().size());
        BlockPos origin = transform.origin();
        if (saved == null || !Arrays.equals(saved.origin(), new int[]{origin.getX(), origin.getY(), origin.getZ()})
                || saved.quarterTurns() != transform.quarterTurns() || saved.mirrorX() != transform.mirrorX()) return false;
        correct.clear();
        for (var layer : saved.layers().entrySet())
            for (int[] column : layer.getValue()) {
                Integer index = indices.get(new BlockPos(column[0], layer.getKey(), column[1]));
                if (index != null && !skipped.get(index)) correct.set(index);
            }
        rebuildWork();
        lastSavedCount = correct.cardinality();
        restored = " Restored " + Math.round(progress() * 100) + "% from saved progress.";
        return true;
    }

    /**
     * Forgets everything recorded for the current schematic and position and deletes its progress file.
     * Blocks already standing in the world are not touched; Start counts the matching ones again.
     */
    public void clearProgress() {
        if (schematic == null) return;
        correct.clear(); // first, so cancel() has nothing to save
        cancel();
        BuilderProgressStore store = store();
        if (store != null) store.delete(schematic.name(), schematic.blocks().size());
        unconfirmed.clear(); failures.clear(); deferred.clear(); supportBlocks.clear();
        scanIndex = 0; scanned = false; revalidateIndex = 0; finalCheckIndex = 0; lastSavedCount = -1; restored = "";
        rebuildWork();
        status = Status.READY;
        reason = "Progress cleared. Start checks the world again and builds what is missing.";
    }

    private void rebuildWork() {
        work.clear();
        for (Target target : targets) if (!correct.get(target.index) && !skipped.get(target.index)) work.add(target);
    }

    /** States no player action can create by placing a block, so waiting for them would block a layer forever. */
    private static boolean neverPlaceable(BlockState state) {
        if ((state.getBlock() instanceof LiquidBlock) && state.getValue(LiquidBlock.LEVEL)!=0) return true; // flowing water/lava
        for (var property:state.getProperties()) {
            if ((property.getName().equals("age") || property.getName().equals("stage"))
                    && state.getValue(property) instanceof Integer age && age>0) return true; // grown
        }
        return state.getBlock() instanceof CandleBlock && state.getValue(BlockStateProperties.LIT);
    }

    /** Position stored with this schematic's saved progress, or null when nothing was saved. */
    public PlacementTransform savedTransform() {
        if (schematic == null) return null;
        BuilderProgressStore store = store();
        BuilderProgressStore.Snapshot saved = store == null ? null : store.load(schematic.name(), schematic.blocks().size());
        if (saved == null || saved.origin().length < 3) return null;
        return new PlacementTransform(new BlockPos(saved.origin()[0], saved.origin()[1], saved.origin()[2]), saved.quarterTurns(), saved.mirrorX());
    }

    public void setSchematic(Schematic value) {
        cancel(); schematic = value; rebuildTargets(); previewVisible = value != null;
        status = value == null ? Status.IDLE : Status.READY;
        reason = value == null ? "Choose a schematic." : "Position the preview, then Start." + restored;
    }
    public void setTransform(PlacementTransform value) {
        if (transform.equals(value)) return;
        cancel(); transform = Objects.requireNonNull(value); rebuildTargets();
        status = schematic == null ? Status.IDLE : Status.READY; reason = "Position updated." + restored;
    }
    private void rebuildTargets() {
        indices.clear(); correct.clear(); failures.clear(); deferred.clear(); supportBlocks.clear(); scanIndex=0; scanned=false;
        work.clear(); skipped.clear(); revalidateIndex=0; finalCheckIndex=0;
        if (schematic == null) { targets=List.of(); byIndex=new Target[0]; LavaGuard.setPlanned(Set.of()); return; }
        List<Target> values = new ArrayList<>(schematic.blocks().size());
        for (Schematic.Block block : schematic.blocks()) {
            BlockPos pos = transform.apply(block.pos());
            int index=values.size(); values.add(new Target(pos,transform.apply(block.state()),index,false)); indices.put(pos,index);
        }
        // Low structural blocks become supports for later floors, attachments and redstone.
        values.sort(Comparator.comparingInt((Target t) -> t.pos.getY())
                .thenComparingInt(t -> MaterialTracker.isSecondary(t.state) ? 2 : t.state.getBlock() instanceof FallingBlock ? 1 : 0));
        targets=List.copyOf(values); byIndex=new Target[values.size()];
        for (Target target:values) byIndex[target.index]=target;
        skipped.clear();
        for (Target target:values) if (neverPlaceable(target.state)) skipped.set(target.index);
        // Where the schematic will have lava: the player may not stand there to place blocks on the layer being built.
        Set<BlockPos> plannedLava=new HashSet<>();
        for (Target target:values) if (target.state.is(Blocks.LAVA)) plannedLava.add(target.pos);
        LavaGuard.setPlanned(plannedLava);
        rebuildWork();
        lastSavedCount=-1;
        loadProgress();
    }
    public void start() {
        Minecraft client=Minecraft.getInstance();
        if (schematic == null || targets.isEmpty()) { reason="Load a schematic containing blocks."; return; }
        if (!canRun(client)) return;
        if (predictionLevel!=client.level) { unconfirmed.clear(); supportBlocks.clear(); predictionLevel=client.level; }
        cleanup.reset(client);
        failures.clear(); deferred.clear(); scanIndex=0; scanned=false;
        // Saved progress is read again here so an edited file takes effect; the world scan then confirms every block.
        loadProgress();
        rebuildWork();
        revalidateIndex=0; finalCheckIndex=0;
        session=client.level; tick=0; noProgress=0; lastFailure=""; current=null; pending=null; prepared=null; resetSafety();
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; previewVisible=true;
        reason="Checking loaded blocks...";
    }
    public void pause() { pauseWith("Paused by you."); }
    /** After any teleport: forget the route, re-read the build and carry on. */
    private void resumeAfterTeleport(String message) {
        navigator.reset();
        failures.clear(); deferred.clear(); work.clear();
        rebuildWork();
        scanIndex=0; scanned=false; revalidateIndex=0; finalCheckIndex=0; noProgress=0; cooldown=40;
        current=null; prepared=null; pending=null; pillar=null;
        progressAnchor=null; progressTick=tick;
        reason=message;
    }

    // ---- fetching lava and water when the buckets run out

    /** Lava or water ran out: /sethome, /home to the supply, scoop with Baritone, /home back. False if not possible. */
    private boolean tryRestock(Minecraft client, Item item) {
        if (!settings.autoRestock || restockFails>=2) return false;
        if (item!=Items.LAVA_BUCKET && item!=Items.WATER_BUCKET) return false;
        if (!AutoMineController.baritoneInstalled()) return false; // Baritone finds and walks to the fluid
        int lava=bucketsNeeded(client,Items.LAVA_BUCKET), water=bucketsNeeded(client,Items.WATER_BUCKET);
        if (!restock.start(client,settings,item,lava,water)) return false;
        current=null; prepared=null; pending=null; pillar=null;
        navigator.release(); restoreSlot();
        reason=restock.reason();
        return true;
    }

    private void restockTick(Minecraft client) {
        navigator.release(); // Baritone drives the player during a trip
        var outcome=restock.tick(client);
        if (outcome==BucketRestock.Outcome.RUNNING) { reason=restock.reason(); return; }
        if (outcome==BucketRestock.Outcome.DONE) {
            if (restock.collected()==0) restockFails++; // went all that way and got nothing: do not loop forever
            resumeAfterTeleport("Back at the build with " + restock.collected() + " new bucket(s), continuing...");
        } else {
            String message=restock.reason();
            restock.reset(); restockFails++;
            pauseWith(message);
        }
    }

    /** How many buckets of this kind the unfinished part of the build still uses. */
    private int bucketsNeeded(Minecraft client, Item item) {
        int needed=0;
        for (Target target:work) {
            if (skipped.get(target.index)) continue;
            BlockState actual=MaterialTracker.isLoaded(client.level,target.pos) ? client.level.getBlockState(target.pos) : Blocks.AIR.defaultBlockState();
            if (FluidPlacement.requiredItem(target.state,actual)==item) needed++;
        }
        return needed;
    }

    private void pauseWith(String message) {
        if (status != Status.RUNNING && status != Status.PAUSED) { reason=message; return; }
        saveProgress();
        status=Status.PAUSED; reason=message; prepared=null; pillar=null; homeWaitUntil=-1; pendingSetHome=false;
        cleanup.reset(Minecraft.getInstance()); navigator.release(); restoreSlot();
        if (restock.active()) { try { BaritoneLink.cancel(); } catch (RuntimeException | LinkageError ignored) { } }
    }
    public void resume() {
        if (status != Status.PAUSED) { start(); return; }
        Minecraft client=Minecraft.getInstance();
        if (!canRun(client)) return;
        if (session != client.level) { start(); return; }
        failures.clear(); deferred.clear(); lastFailure=""; noProgress=0; current=null; prepared=null; navigator.reset(); resetSafety();
        loadProgress();
        rebuildWork();
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; reason="Resuming...";
    }
    private boolean canRun(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null) { reason="Join a world before starting."; return false; }
        if (FreecamController.enabled()) { reason="Disable Freecam before resuming AutoBuilder."; return false; }
        if (client.gameMode.isSpectator()) { reason="Spectator mode cannot place blocks."; return false; }
        if (client.player.isPassenger()) { reason="Leave the vehicle before starting AutoBuilder."; return false; }
        if (client.screen != null) { reason="Close the open menu before resuming."; return false; }
        return true;
    }
    public void cancel() {
        saveProgress();
        restock.reset(); restockFails=0; wrap=Wrap.NONE; wrapCell=null;
        cleanup.reset(Minecraft.getInstance()); supportBlocks.clear(); navigator.reset(); restoreSlot(); current=null; prepared=null; pending=null; session=null;
        failures.clear(); deferred.clear(); cooldown=0; pillar=null; homeWaitUntil=-1; pendingSetHome=false;
        status=schematic == null ? Status.IDLE : Status.READY; reason="Build cancelled.";
    }
    public void disconnect() {
        saveProgress();
        if (isRunning() || status==Status.PAUSED) { pauseWith("Disconnected. Rejoin, check the position, then Resume."); }
        restock.reset();
        navigator.reset(); restoreSlot(); session=null; pending=null; prepared=null; current=null; correct.clear(); scanIndex=0; scanned=false;
        cleanup.reset(Minecraft.getInstance()); supportBlocks.clear(); unconfirmed.clear(); predictionLevel=null;
        previewVisible=false;
        loadProgress();
    }
    private void restoreSlot() {
        var player=Minecraft.getInstance().player;
        if (player != null && originalSlot >= 0) player.getInventory().setSelectedSlot(originalSlot);
        originalSlot=-1;
    }

    public void tick(Minecraft client) {
        if (!isRunning()) return;
        tick++;
        if (tick%200==0 && correct.cardinality()!=lastSavedCount) saveProgress();
        for (var key:List.of(client.options.keyAttack,client.options.keyUse,client.options.keyDrop,client.options.keySwapOffhand)) {
            key.setDown(false); while (key.consumeClick()) { }
        }
        if (client.level == null || client.player == null || client.gameMode == null || client.level != session) { disconnect(); return; }
        if (FreecamController.enabled()) { pauseWith("Freecam is active. Disable it, then Resume."); return; }
        if (client.screen != null || client.isPaused()) { pauseWith("An open menu paused construction. Close it, then Resume."); return; }
        if (!client.player.isAlive() || client.player.isRemoved()) { pauseWith("Player unavailable. Resume after respawning."); return; }
        if (client.player.isPassenger()) { pauseWith("Leave the vehicle, then Resume."); return; }
        if (client.player.isUsingItem()) { pauseWith("Finish using the held item, then Resume."); return; }
        trackFall(client);
        if (lavaEscape(client)) return;
        if (restock.active()) { justFell=false; restockTick(client); return; }
        if (justFell && wrap==Wrap.NONE) { justFell=false; if (homeAfterFall(client)) return; }
        if (homeRecovery(client)) return;
        if (watchProgress(client)) return;
        if (scanned) revalidate(client,256);
        if (pending != null) {
            navigator.hold(client,false);
            if (pending.acknowledged || tick-pending.sentTick > 60) finishPending(client);
            return;
        }
        if (prepared != null) {
            navigator.hold(client,shouldSneak(client,prepared.target,prepared.plan));
            aim(client,prepared.plan);
            if (tick>=prepared.readyTick) sendPrepared(client);
            return;
        }
        if (cooldown>0) { cooldown--; return; }
        if (!scanned) {
            int end=Math.min(targets.size(),scanIndex+2048);
            for (;scanIndex<end;scanIndex++) {
                Target target=targets.get(scanIndex);
                if (skipped.get(target.index)) continue;
                if (client.level.isOutsideBuildHeight(target.pos)) { pauseWith("Outside world build height at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (!client.level.getWorldBorder().isWithinBounds(target.pos)) { pauseWith("Outside the world border at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (!unconfirmed.containsKey(target.pos) && MaterialTracker.isLoaded(client.level,target.pos)) {
                    if (MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) markCorrect(target);
                    else if (correct.get(target.index)) { correct.clear(target.index); work.add(target); } // saved as placed, but gone
                }
            }
            if (scanIndex<targets.size()) return;
            scanned=true;
        }
        if (correct.cardinality()>=totalBlocks()) {
            navigator.release(); reason="Verifying the completed structure...";
            int end=Math.min(targets.size(),finalCheckIndex+2048);
            for (;finalCheckIndex<end;finalCheckIndex++) {
                Target target=targets.get(finalCheckIndex);
                if (skipped.get(target.index)) continue;
                if (unconfirmed.containsKey(target.pos) || !MaterialTracker.isLoaded(client.level,target.pos)
                        || !MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) {
                    correct.clear(target.index); work.add(target); finalCheckIndex=0; return;
                }
            }
            if (finalCheckIndex==targets.size()) {
                SupportCleanup.Result result=cleanup.tick(client,navigator,supportBlocks,indices.keySet());
                reason=cleanup.reason();
                if (result==SupportCleanup.Result.DONE) complete();
                else if (result==SupportCleanup.Result.BLOCKED) pauseWith(cleanup.reason());
            }
            return;
        }
        if (wrap != Wrap.NONE && wrapTick(client)) return;
        if (settings.autoSetHome && layerY!=Integer.MIN_VALUE && homeSetLayer!=layerY && setHomeAbove(client)) return;
        cleanup.reset(client);
        finalCheckIndex=0;
        navigator.minStandY=settings.alwaysAbove && !relaxAbove && layerY!=Integer.MIN_VALUE ? layerY+1 : Integer.MIN_VALUE;
        if (pillar != null && pillarTick(client)) return;
        if (current == null) { relaxAbove=false; current=chooseTarget(client); }
        if (wrap != Wrap.NONE) { current=null; return; } // a layer just ended: wrap it up first
        if (current == null) {
            pauseWith(lastFailure.isEmpty() ? "No placeable blocks remain. Check unloaded areas, supports and occupied positions." : lastFailure);
            return;
        }
        if (client.level.isOutsideBuildHeight(current.pos)) { pauseWith("Outside world build height at " + coordinates(current.pos) + ". Reposition the schematic."); return; }
        if (!client.level.getWorldBorder().isWithinBounds(current.pos)) { pauseWith("Outside the world border at " + coordinates(current.pos) + ". Reposition the schematic."); return; }
        if (!MaterialTracker.isLoaded(client.level,current.pos)) { defer("Area is unloaded at " + coordinates(current.pos) + ". Move closer, then Resume."); return; }
        if (unconfirmed.containsKey(current.pos)) { pauseWith("Still waiting for server confirmation at " + coordinates(current.pos) + ". Resume after the connection recovers."); return; }
        BlockState actual=client.level.getBlockState(current.pos);
        if (MaterialTracker.matches(current.state,actual)) {
            // Only a confirmed interaction may register a support as ours.
            if (!current.support) markCorrect(current);
            current=null; return;
        }
        if (MaterialTracker.isSecondary(current.state)) { defer("Paired block is missing at " + coordinates(current.pos) + ". Check its door, bed or plant base."); return; }
        String manual=manualStateReason(current.state,actual);
        if (manual!=null) { defer(manual + " at " + coordinates(current.pos) + ". Complete it manually, then Resume."); return; }
        if (!PlacementPlanner.canAdd(current.state,actual) && !PlacementPlanner.needsConfiguration(current.state,actual)) {
            defer("Occupied or incorrect block at " + coordinates(current.pos) + ". Correct it manually, then Resume."); return;
        }
        var requirement=MaterialTracker.requirement(current.state);
        Item bucket=FluidPlacement.requiredItem(current.state,actual);
        Item requiredItem=bucket==null ? requirement.item() : bucket;
        if (requiredItem==Items.AIR || (!(requiredItem instanceof BlockItem) && bucket==null)) {
            defer("This block requires manual placement: " + current.state.getBlock().getName().getString() + " at " + coordinates(current.pos)); return;
        }
        if (bucket==null && (!current.state.canSurvive(client.level,current.pos) || !hasNeighborSupport(client,current.pos))) {
            Target support=necessarySupport(client,current);
            if (support!=null) { current=support; return; }
            // A support belonging to the schematic may still be awaiting construction.
            defer("Missing support at " + coordinates(current.pos) + ". Provide solid supports or access, then Resume."); return;
        }
        boolean configuring=PlacementPlanner.needsConfiguration(current.state,actual);
        int slot=configuring && bucket==null ? client.player.getInventory().getSelectedSlot() : findSlot(client,requiredItem);
        if (slot<0) {
            if (tryRestock(client,requiredItem)) return;
            pauseWith("Missing " + requiredItem.getName().getString() + ". Add materials, then Resume."); return;
        }
        if (slot>=9) {
            int selected=client.player.getInventory().getSelectedSlot();
            client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId,slot,selected,ClickType.SWAP,client.player);
            cooldown=2; reason="Moving material into the hotbar..."; return;
        }
        client.player.getInventory().setSelectedSlot(slot);
        // "Always stand above the layer": below it, do not place from where the player is; walk or climb up first.
        boolean belowLayer=settings.alwaysAbove && !relaxAbove && !current.support && layerY!=Integer.MIN_VALUE
                && client.player.getY()<layerY+1-.05;
        var plan=belowLayer ? null : PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan != null) {
            navigator.hold(client,shouldSneak(client,current,plan));
            aim(client,plan);
            prepared=new Prepared(current,plan,tick+2);
            reason=(current.support ? "Placing a temporary support" : plan.configure() ? "Setting block state" : "Placing block") + " at " + coordinates(current.pos);
            noProgress=0; return;
        }
        if (!belowLayer) {
            Target lateral=lateralSupport(client,current);
            if (lateral!=null) { current=lateral; return; }
            Target access=accessSupport(client,current);
            if (access!=null) { current=access; return; }
        }
        BuilderNavigator.Result movement=navigator.moveTo(client,current.pos);
        if (movement==BuilderNavigator.Result.ARRIVED) { navigator.rejectCurrent(client); noProgress++; noRouteTicks=0; }
        else if (movement==BuilderNavigator.Result.NO_PATH) {
            if (startPillar(client)) return;
            if (settings.alwaysAbove && !relaxAbove && client.player.getY()<layerY+1-.05) {
                relaxAbove=true; navigator.minStandY=Integer.MIN_VALUE; navigator.replan(); return; // cannot climb: place from below
            }
            // Only a route that stays missing for over a second counts as stuck.
            if (++noRouteTicks<STUCK_HOME_TICKS) { reason="Looking for a route to " + coordinates(current.pos); return; }
            noRouteTicks=0;
            if (strandedHome(client,"No route for over a second")) return;
            defer("No walking route to " + coordinates(current.pos) + ". Provide a path or stairs, then Resume."); return;
        }
        else if (movement==BuilderNavigator.Result.STUCK) {
            // The navigator reports STUCK only after the player has not moved for over a second.
            noProgress+=30; noRouteTicks=0;
            if (strandedHome(client,"Stuck for over a second")) return;
        }
        else { noProgress++; noRouteTicks=0; }
        reason="Walking to " + coordinates(current.pos) + " / " + navigator.routeLength() + " steps";
        if (noProgress>300) {
            if (startPillar(client)) return;
            if (strandedHome(client,"Cannot get back to the build")) return;
            defer("Cannot reach a valid placement face or orientation at " + coordinates(current.pos) + ". Provide access, then Resume.");
        }
    }
    // ---------------------------------------------------------------- layers and /sethome

    private void noteLayer(int minimumY) {
        if (layerY==Integer.MIN_VALUE) { layerY=minimumY; homeLayer=minimumY; return; }
        layerY=minimumY;
        LavaGuard.setPlannedLayer(minimumY);
        if (minimumY>homeLayer) {
            int finished=homeLayer;
            homeLayer=minimumY;
            reason="Layer Y=" + finished + " finished";
            if (settings.autoSetHome && wrap==Wrap.NONE) {
                // The home was normally set the moment the player stood one block above this layer; if so, go straight
                // to breaking the supports. Otherwise walk to a spot above the layer and set it then.
                wrapLayer=finished; wrapCell=null; wrapHomeSet=homeSetLayer==finished; wrapSent=false; wrapStuck=0;
                wrapStart=tick; wrapHadSupports=!supportBlocks.isEmpty();
                wrap=wrapHomeSet ? Wrap.CLEAN : Wrap.WALK;
            }
        }
    }

    /**
     * End of a layer: make sure /sethome was done one block above it, then break the temporary supports. There is no
     * /home here; falling afterwards (or getting stuck) is what sends the player home. Returns true while busy.
     */
    private boolean wrapTick(Minecraft client) {
        var player=client.player;
        switch (wrap) {
            case WALK -> {
                if (wrapCell==null) {
                    wrapCell=homeCell(client);
                    wrapStart=tick;
                    if (wrapCell==null) { reason="No safe spot above layer Y=" + wrapLayer + " for /sethome"; wrap=Wrap.CLEAN; return true; }
                }
                BuilderNavigator.Result result=navigator.walkTo(client,wrapCell);
                reason="Layer Y=" + wrapLayer + " done: going to a spot above it for /sethome";
                if (result==BuilderNavigator.Result.ARRIVED) { navigator.release(); wrap=Wrap.SETHOME; wrapSent=false; return true; }
                if (result==BuilderNavigator.Result.STUCK) wrapStuck++;
                if (result==BuilderNavigator.Result.NO_PATH || wrapStuck>=3 || tick-wrapStart>20*30) {
                    navigator.reset(); reason="Could not reach a spot above layer Y=" + wrapLayer + "; keeping the old home"; wrap=Wrap.CLEAN;
                }
                return true;
            }
            case SETHOME -> {
                navigator.release();
                if (!wrapSent) {
                    if (!player.onGround()) return true;
                    player.connection.sendCommand("sethome " + settings.homeSlot);
                    wrapSent=true; wrapUntil=tick+20; homeSetLayer=wrapLayer;
                    reason="/sethome " + settings.homeSlot + " one block above layer Y=" + wrapLayer;
                } else if (tick>=wrapUntil) { wrapHomeSet=true; wrap=Wrap.CLEAN; }
                return true;
            }
            case CLEAN -> {
                if (supportBlocks.isEmpty()) return endWrap(client,"Layer Y=" + wrapLayer + " finished");
                wrapHadSupports=true;
                SupportCleanup.Result result=cleanup.tick(client,navigator,supportBlocks,indices.keySet());
                reason="Layer Y=" + wrapLayer + " done: " + cleanup.reason();
                // No /home here: it only happens when the player really falls or gets stuck afterwards.
                if (result==SupportCleanup.Result.DONE) return endWrap(client,"Layer Y=" + wrapLayer + " finished, supports removed");
                if (result==SupportCleanup.Result.BLOCKED) { cleanup.reset(client); return endWrap(client,"Layer Y=" + wrapLayer + " finished"); }
                return true;
            }
            default -> { return false; }
        }
    }

    /**
     * The moment the player stands one block above the layer being built, /sethome right away (once per layer).
     * Not on a temporary support (it is broken at the end of the layer) and not next to lava.
     */
    private boolean setHomeAbove(Minecraft client) {
        var player=client.player;
        double above=player.getY()-(layerY+1);
        if (!player.onGround() || above<-.05 || above>.5) return false;
        BlockPos below=player.blockPosition().below();
        if (supportBlocks.contains(below) || !client.level.getBlockState(below).isCollisionShapeFullBlock(client.level,below)) return false;
        if (LavaGuard.near(client.level,player.blockPosition()) || LavaGuard.plannedOnLayer(below.above())) return false;
        player.connection.sendCommand("sethome " + settings.homeSlot);
        homeSetLayer=layerY; cooldown=10;
        navigator.release();
        reason="/sethome " + settings.homeSlot + " one block above layer Y=" + layerY;
        return true;
    }

    private boolean endWrap(Minecraft client, String message) {
        wrap=Wrap.NONE; wrapCell=null;
        cleanup.reset(client);
        resumeAfterTeleport(message);
        return true;
    }

    /** A free spot standing one block above a placed block of the finished layer, nearest to the player. */
    private BlockPos homeCell(Minecraft client) {
        var level=client.level; var player=client.player;
        BlockPos best=null; double bestDistance=32*32;
        for (Target target:targets) {
            if (target.pos.getY()!=wrapLayer || skipped.get(target.index) || !correct.get(target.index)) continue;
            double distance=player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos.above()));
            if (distance>=bestDistance) continue;
            BlockPos feet=target.pos.above();
            if (supportBlocks.contains(target.pos) || indices.containsKey(feet) || indices.containsKey(feet.above())) continue;
            if (!MaterialTracker.isLoaded(level,feet.above()) || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) continue;
            if (!level.getBlockState(feet).getCollisionShape(level,feet).isEmpty() || !level.getBlockState(feet.above()).getCollisionShape(level,feet.above()).isEmpty()) continue;
            if (!level.getBlockState(target.pos).isCollisionShapeFullBlock(level,target.pos)) continue;
            if (LavaGuard.near(level,feet) || LavaGuard.plannedOnLayer(feet) || LavaGuard.plannedOnLayer(feet.above())) continue;
            bestDistance=distance; best=feet.immutable();
        }
        return best;
    }

    // ---------------------------------------------------------------- lava and getting stuck

    private boolean lavaEscape(Minecraft client) {
        if (!settings.lavaGuard || !client.player.isInLava()) return false;
        String where=coordinates(client.player.blockPosition());
        boolean sent=settings.autoHome && sendHome(client);
        pauseWith("Entered lava at " + where + (sent ? "; sent /home " + settings.homeSlot : "") + ". Check the build, then Resume.");
        return true;
    }

    private boolean sendHome(Minecraft client) {
        if (tick-lastHomeTick<HOME_COOLDOWN_TICKS) return false;
        client.player.connection.sendCommand("home " + settings.homeSlot);
        lastHomeTick=tick;
        return true;
    }

    /** No new block and no real movement for 30 s means the builder is stuck. */
    private boolean watchProgress(Minecraft client) {
        var pos=client.player.position();
        int blocks=correct.cardinality(), supports=supportBlocks.size();
        boolean built=blocks!=progressBlocks || supports!=progressSupports;
        if (progressAnchor==null || built || pos.distanceToSqr(progressAnchor)>4.0) {
            if (built) { stuckRecoveries=0; restockFails=0; }
            progressAnchor=pos; progressBlocks=blocks; progressSupports=supports; progressTick=tick;
            return false;
        }
        if (tick-progressTick<STUCK_TICKS) return false;
        progressTick=tick;
        if (!settings.autoHome) { pauseWith("No progress for 30 seconds. Move the player, then Resume."); return true; }
        if (stuckRecoveries>=3) { pauseWith("Still stuck after /home " + settings.homeSlot + " three times. Check the build, then Resume."); return true; }
        stuckRecoveries++;
        beginHome(client,"Stuck");
        return true;
    }

    private void beginHome(Minecraft client, String why) {
        sendHome(client);
        homeFrom=client.player.position(); homeWaitUntil=tick+HOME_WAIT_TICKS;
        pillar=null; current=null; prepared=null; pending=null; unconfirmed.clear();
        reason=why + ", using /home " + settings.homeSlot + " (" + stuckRecoveries + "/3)";
    }

    /** Remembers the last time the player dropped several blocks to a lower floor. */
    private void trackFall(Minecraft client) {
        var player=client.player;
        if (!player.onGround()) return;
        if (!Double.isNaN(groundY) && groundY-player.getY()>=FALL_BLOCKS) { lastFallTick=tick; justFell=true; }
        groundY=player.getY();
    }

    /**
     * The player fell below the build (or is well under the block it needs) and no route, pillar or
     * placement face is available: go to /home right away instead of waiting 30 s or giving up.
     */
    /** No route to walk and no way to climb: /home straight away (limited to three tries without progress). */
    private boolean strandedHome(Minecraft client, String why) {
        if (!settings.autoHome || stuckRecoveries>=3 || current==null) return false;
        stuckRecoveries++;
        beginHome(client,why);
        return true;
    }

    /** The player dropped several blocks and now stands below the layer being built: /home. */
    private boolean homeAfterFall(Minecraft client) {
        if (!settings.autoHome || stuckRecoveries>=3 || layerY==Integer.MIN_VALUE) return false;
        if (client.player.getY()>=layerY) return false;
        stuckRecoveries++;
        beginHome(client,"Fell below the layer");
        return true;
    }

    /** Stands still while /home warms up, then replans from the new position. */
    private boolean homeRecovery(Minecraft client) {
        if (homeWaitUntil<0) return false;
        navigator.hold(client,false);
        if (client.player.position().distanceToSqr(homeFrom)>16) {
            homeWaitUntil=-1;
            resumeAfterTeleport("Back at home " + settings.homeSlot + ", checking the build and continuing...");
            return true;
        }
        if (tick>=homeWaitUntil) {
            homeWaitUntil=-1;
            pauseWith("/home " + settings.homeSlot + " did not move the player. Set that home first, then Resume.");
            return true;
        }
        reason="Waiting for /home " + settings.homeSlot + "...";
        return true;
    }

    // ---------------------------------------------------------------- climbing with temporary blocks

    /** Called when a target is too high to reach and no walking route exists. */
    private boolean startPillar(Minecraft client) {
        if (!settings.pillarUp || current==null || current.support) return false;
        boolean high=settings.alwaysAbove && !relaxAbove
                ? client.player.getY()<current.pos.getY()+1-.05   // always end up one block above the layer
                : current.pos.getY()-client.player.getY()>=1.6;   // only climb to blocks that are out of reach
        if (!high || findSlot(client,SCAFFOLD)<0) return false;
        pillar=new Pillar(current,tick); current=null; navigator.reset(); noProgress=0;
        reason="Climbing to " + coordinates(pillar.goal.pos);
        return true;
    }

    /**
     * Jump, and once the feet are a full block higher, place a block into the cell just left and land on it.
     * Repeats until the feet are one block above the layer being built, and never climbs past that.
     */
    private boolean pillarTick(Minecraft client) {
        Pillar p=pillar; var player=client.player;
        // Keep stacking until the player stands one block ABOVE the layer being built (feet at layer Y+1, resting on a
        // block at layer Y). Only then does construction resume; a jump in mid-air never counts as having arrived.
        if (!settings.pillarUp || (player.onGround() && player.getY()>=p.goal.pos.getY()+1-.05)) { pillar=null; navigator.reset(); return false; }
        if (tick-p.started>PILLAR_TIMEOUT) { giveUpClimbing(p,"Climbing took too long"); return true; }
        navigator.hold(client,false);
        player.setXRot(90f);
        if (player.onGround()) {
            if (p.airborne) { // just landed
                p.airborne=false;
                if (!p.placedThisJump && ++p.failed>=MAX_FAILED_JUMPS) { giveUpClimbing(p,"Could not place a block while climbing"); return true; }
                p.placedThisJump=false;
            }
            BlockPos feet=player.blockPosition();
            if (settings.lavaGuard && LavaGuard.near(client.level,feet)) { giveUpClimbing(p,"Lava next to the climbing spot"); return true; }
            // The column being stacked ends up under the player at the layer being built: not where lava is planned.
            if (LavaGuard.plannedOnLayer(new BlockPos(feet.getX(),p.goal.pos.getY(),feet.getZ()))) { giveUpClimbing(p,"Lava will be placed at the climbing spot"); return true; }
            if (!client.level.noCollision(player,player.getBoundingBox().expandTowards(0,1.3,0))
                    || !client.level.getBlockState(feet).canBeReplaced()) { giveUpClimbing(p,"No room to climb"); return true; }
            int slot=findSlot(client,SCAFFOLD);
            if (slot<0) { pillar=null; pauseWith("Missing " + SCAFFOLD.getName().getString() + " for climbing. Add materials, then Resume."); return true; }
            if (slot>=9) {
                int selected=player.getInventory().getSelectedSlot();
                client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId,slot,selected,ClickType.SWAP,player);
                cooldown=2; reason="Moving material into the hotbar..."; return true;
            }
            player.getInventory().setSelectedSlot(slot);
            // The look-down has to be sent before the jump so the click can follow without another rotation.
            if (Math.abs(((BuilderLocalPlayerAccessor)player).donut$lastSentPitch()-90f)<.5f) {
                p.base=feet.immutable(); p.airborne=true;
                navigator.jump(client);
            }
            reason="Climbing to " + coordinates(p.goal.pos);
            return true;
        }
        p.airborne=true;
        if (p.base==null || p.placedThisJump || player.getY()<p.base.getY()+1.0) return true;
        BlockState scaffold=Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        if (!client.level.getBlockState(p.base).canBeReplaced()) return true;
        var plan=PlacementPlanner.plan(client,p.base,scaffold,player.getMainHandItem());
        if (plan==null) return true;
        current=new Target(p.base,scaffold,-1,true);
        plan=pillarAim(client,plan);
        prepared=new Prepared(current,plan,tick+1);
        p.placedThisJump=true;
        reason="Climbing: placing a block at " + coordinates(p.base);
        return true;
    }

    private void giveUpClimbing(Pillar p,String message) {
        pillar=null; current=p.goal; navigator.reset();
        defer(message + " at " + coordinates(p.goal.pos) + ". Provide a path, then Resume.");
    }

    /** A support under the feet needs no particular look point, so keep the yaw and keep looking straight down. */
    private PlacementPlanner.Plan pillarAim(Minecraft client,PlacementPlanner.Plan plan) {
        if (pillar==null || current==null || !current.support || plan.hit().getDirection()!=Direction.UP) return plan;
        return new PlacementPlanner.Plan(plan.hit(),plan.prediction(),client.player.getYRot(),90f,false);
    }

    /** Y of the lowest layer that still has a block the server has not confirmed, deferred blocks included. */
    private int lowestIncompleteY() {
        for (Target target:targets) if (!correct.get(target.index) && !skipped.get(target.index)) return target.pos.getY();
        return Integer.MIN_VALUE;
    }
    /**
     * A lava source is only poured once every block around it (the four horizontal neighbours; above and below do not
     * count) that the schematic contains is confirmed, so the lava never spills before its container exists.
     * Neighbouring fluids are ignored, otherwise two touching lava blocks would wait for each other forever.
     */
    private boolean lavaWaitsForFrame(Target target) {
        if (target.support || !target.state.is(Blocks.LAVA)) return false;
        for (Direction side:Direction.Plane.HORIZONTAL) {
            Integer index=indices.get(target.pos.relative(side));
            if (index==null || skipped.get(index) || correct.get(index)) continue;
            BlockState neighbor=byIndex[index].state;
            if (neighbor.is(Blocks.LAVA) || neighbor.is(Blocks.WATER)) continue;
            return true;
        }
        return false;
    }
    private Target chooseTarget(Minecraft client) {
        Target best=null; double bestScore=Double.POSITIVE_INFINITY;
        if (work.isEmpty()) return null;
        // The layer is decided by what is still unbuilt, not by what is currently placeable, so a block that was
        // put aside can never let the builder climb to the next layer.
        int layer=lowestIncompleteY(), examined=0;
        if (layer==Integer.MIN_VALUE) return null;
        noteLayer(layer);
        for (Target target:work) {
            if (target.pos.getY()<layer) continue;
            if (target.pos.getY()>layer || ++examined>256) break;
            if (lavaWaitsForFrame(target)) continue;
            double score=client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (MaterialTracker.isSecondary(target.state)?512:0);
            if (score<bestScore) { bestScore=score; best=target; }
        }
        if (best==null) {
            lastFailure="Layer Y=" + layer + " cannot be finished yet. " + (lastFailure.isEmpty()
                    ? "Check unloaded areas, supports and occupied positions." : lastFailure);
        }
        return best;
    }
    private void revalidate(Minecraft client,int budget) {
        for (int checked=0;checked<Math.min(budget,targets.size());checked++) {
            if (revalidateIndex>=targets.size()) revalidateIndex=0;
            Target target=targets.get(revalidateIndex++);
            if (skipped.get(target.index)) continue;
            if (unconfirmed.containsKey(target.pos) || (pending!=null && pending.target.pos.equals(target.pos))) continue;
            // A block that cannot be checked right now keeps its saved state; the final check still verifies it.
            if (!MaterialTracker.isLoaded(client.level,target.pos)) continue;
            boolean matches=MaterialTracker.matches(target.state,client.level.getBlockState(target.pos));
            if (matches) markCorrect(target);
            else if (correct.get(target.index)) { correct.clear(target.index); if (!deferred.contains(target.pos)) work.add(target); }
        }
    }
    private void sendPrepared(Minecraft client) {
        Prepared action=prepared; prepared=null;
        if (!action.target.equals(current)) return;
        // Recompute after walking/input has settled; entities and the world may have moved.
        var plan=PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan==null) {
            // Climbing: the jump window passed, go back to jumping instead of replanning a walk.
            if (pillar!=null && current.support) { current=null; return; }
            navigator.rejectCurrent(client); return;
        }
        plan=pillarAim(client,plan);
        boolean sneak=shouldSneak(client,current,plan);
        navigator.hold(client,sneak);
        aim(client,plan);
        var sent=(BuilderLocalPlayerAccessor)client.player;
        // Let vanilla send rotation/input once per client tick before the interaction. An extra
        // movement packet here duplicates LocalPlayer.sendPosition and violates strict packet ordering.
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(sent.donut$lastSentYaw()-plan.yaw()))>.001f
                || Math.abs(sent.donut$lastSentPitch()-plan.pitch())>.001f
                || sent.donut$lastSentInput()==null || sent.donut$lastSentInput().shift()!=sneak) {
            prepared=new Prepared(current,plan,tick+1);
            reason="Aligning for placement at " + coordinates(current.pos);
            return;
        }
        BlockState before=client.level.getBlockState(current.pos);
        // Buckets use vanilla POV raycasts in useItem, not BlockItem/useItemOn.
        var result=FluidPlacement.isBucket(client.player.getMainHandItem())
                ? client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND)
                : client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,plan.hit());
        int sequence=((BuilderClientLevelAccessor)client.level).donut$predictionHandler().currentSequence();
        unconfirmed.put(current.pos,sequence);
        BlockPos secondary=secondaryPosition(current);
        if (secondary!=null) unconfirmed.put(secondary,sequence);
        pending=new Pending(current,before,plan.prediction(),tick,sequence);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        reason="Waiting for the server at " + coordinates(current.pos);
    }
    private static void aim(Minecraft client,PlacementPlanner.Plan plan) {
        client.player.setYRot(plan.yaw()); client.player.setXRot(plan.pitch());
    }
    public void serverBlockUpdate(BlockPos pos,BlockState state) {
        cleanup.serverBlockUpdate(pos,state,supportBlocks);
        unconfirmed.remove(pos);
        if (session==null) return;
        Integer index=indices.get(pos);
        if (index!=null && !skipped.get(index)) {
            // Index refers to the original list, not its sorted order.
            BlockState desired=transform.apply(schematic.blocks().get(index).state());
            if (MaterialTracker.matches(desired,state)) { markCorrect(byIndex[index]); deferred.remove(pos); }
            else { correct.clear(index); if (!deferred.contains(pos)) work.add(byIndex[index]); finalCheckIndex=0; }
        }
        if (pending!=null && pending.target.pos.equals(pos)) pending.observed=state;
    }
    public void serverAcknowledged(int sequence) {
        unconfirmed.entrySet().removeIf(entry->entry.getValue()<=sequence);
        if (pending!=null && sequence>=pending.sequence) pending.acknowledged=true;
    }
    public void positionCorrected() { if (isRunning()) { cleanup.reset(Minecraft.getInstance()); navigator.replan(); prepared=null; reason="Server corrected the position. Replanning..."; } }
    private void finishPending(Minecraft client) {
        Pending action=pending;
        if (!action.acknowledged && action.observed==null) {
            pauseWith("No server confirmation at " + coordinates(action.target.pos) + ". Check the connection, then Resume.");
            return;
        }
        pending=null;
        BlockState actual=action.observed==null ? client.level.getBlockState(action.target.pos) : action.observed;
        boolean changed=action.acknowledged && !actual.equals(action.before);
        if (action.observed!=null && !action.observed.equals(action.before)) changed=true;
        if (MaterialTracker.matches(action.target.state,actual) && changed) {
            markCorrect(action.target); failures.remove(action.target.pos);
            for (BlockPos pos:deferred) { Integer index=indices.get(pos); if (index!=null && !correct.get(index)) work.add(byIndex[index]); }
            deferred.clear(); current=null; noProgress=0; cooldown=2;
        } else if (changed && actual.getBlock()==action.target.state.getBlock()
                && (PlacementPlanner.canAdd(action.target.state,actual) || PlacementPlanner.needsConfiguration(action.target.state,actual))) {
            // Double slabs, stackable decorations and configurable redstone require further interactions.
            failures.remove(action.target.pos); cooldown=2;
        } else {
            int attempts=failures.merge(action.target.pos,1,Integer::sum); navigator.rejectCurrent(client); cooldown=4;
            reason="Server rejected placement at " + coordinates(action.target.pos) + " (" + attempts + "/3). Replanning...";
            if (attempts>=3) pauseWith("Server rejected or changed the requested state at " + coordinates(action.target.pos)
                    + ". Check build permissions, supports and state compatibility, then Resume.");
        }
    }
    private void markCorrect(Target target) {
        if (target.support) supportBlocks.add(target.pos); else { correct.set(target.index); work.remove(target); }
    }
    private void defer(String message) {
        lastFailure=message;
        if (current.support) { pauseWith(message); current=null; return; }
        deferred.add(current.pos); work.remove(current); current=null; noProgress=0; navigator.reset();
    }
    private void complete() {
        saveProgress();
        status=Status.COMPLETED; navigator.reset(); restoreSlot(); current=null;
        reason="Complete: " + correct.cardinality() + " blocks confirmed." + (skipped.isEmpty()?"":" " + skipped.cardinality() + " flowing, grown or lit blocks were skipped because they cannot be placed.") + (supportBlocks.isEmpty()?"":" " + supportBlocks.size() + " temporary cobbled-deepslate supports were kept where removal was unsafe or unconfirmed.");
        if (schematic.blocks().stream().anyMatch(block->block.blockEntity()!=null)) reason += " Stored block-entity data needs server support.";
    }
    private static String coordinates(BlockPos pos) { return pos.getX()+", "+pos.getY()+", "+pos.getZ(); }
    private static int findSlot(Minecraft client,Item item) {
        for (int i=0;i<36;i++) if (client.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
    private static BlockPos secondaryPosition(Target target) {
        if ((target.state.getBlock() instanceof DoorBlock || target.state.getBlock() instanceof DoublePlantBlock)
                && target.state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER)
            return target.pos.above();
        if (target.state.getBlock() instanceof BedBlock && target.state.getValue(BedBlock.PART)==BedPart.FOOT)
            return target.pos.relative(target.state.getValue(BedBlock.FACING));
        return null;
    }
    private boolean shouldSneak(Minecraft client,Target target,PlacementPlanner.Plan plan) {
        if (pillar!=null && target.support) return false; // in mid-air, and the support is a plain block
        // Sneaking prevents opening a support container, but also prevents slab/stack-item merging.
        return !plan.configure() && client.level.getBlockState(target.pos).getBlock()!=target.state.getBlock();
    }
    private static String manualStateReason(BlockState desired,BlockState actual) {
        if ((desired.is(Blocks.WATER) || desired.is(Blocks.LAVA))
                && desired.getValue(LiquidBlock.LEVEL)!=0)
            return "Flowing fluid states must form naturally from source blocks";
        if (FluidPlacement.requiredItem(desired,actual)==Items.WATER_BUCKET
                && net.minecraft.world.level.Level.NETHER.equals(Minecraft.getInstance().level.dimension()))
            return "Water cannot be placed in this ultrawarm dimension";
        for (var property:desired.getProperties()) {
            if ((property.getName().equals("age") || property.getName().equals("stage"))
                    && desired.getValue(property) instanceof Integer age && age>0)
                return "This grown block state requires growth or server support";
        }
        if (desired.getBlock() instanceof CandleBlock && desired.getValue(BlockStateProperties.LIT))
            return "Lit candles require ignition";
        return null;
    }
    private static boolean hasNeighborSupport(Minecraft client,BlockPos pos) {
        BlockState actual=client.level.getBlockState(pos);
        if (!actual.isAir()) return true;
        for (Direction direction:Direction.values()) {
            BlockPos neighbor=pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) return true;
        }
        return false;
    }
    private Target necessarySupport(Minecraft client,Target target) {
        BlockPos below=target.pos.below();
        if (indices.containsKey(below)) return null;
        Item material=null;
        for (Item candidate:List.of(Items.COBBLED_DEEPSLATE)) if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        // Build a short support column from its existing foundation, never overwrite a schematic cell.
        BlockPos base=below;
        for (int depth=0;depth<12;depth++,base=base.below()) {
            if (!MaterialTracker.isLoaded(client.level,base) || indices.containsKey(base) || !client.level.getBlockState(base).canBeReplaced()) return null;
            BlockPos foundation=base.below();
            if (!MaterialTracker.isLoaded(client.level,foundation)) return null;
            if (client.level.getBlockState(foundation).isCollisionShapeFullBlock(client.level,foundation))
                return new Target(base,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }
    private Target lateralSupport(Minecraft client,Target target) {
        boolean topSlab=target.state.getBlock() instanceof SlabBlock && target.state.getValue(SlabBlock.TYPE)==SlabType.TOP;
        boolean topStair=target.state.getBlock() instanceof StairBlock && target.state.getValue(StairBlock.HALF)==Half.TOP;
        if (target.support || (!topSlab && !topStair)) return null;
        List<Direction> directions=new ArrayList<>();
        if (topStair) {
            Direction facing=target.state.getValue(StairBlock.FACING);
            // A support behind the desired look direction exposes a face with the required stair yaw.
            // The opposite face would force the wrong direction, so it never blocks a suitable new support.
            directions.add(facing); directions.add(facing.getClockWise()); directions.add(facing.getCounterClockWise());
        } else {
            for (Direction direction:Direction.Plane.HORIZONTAL) directions.add(direction);
            // The face of a support beyond the target is visible from the player's current side.
            directions.sort(Comparator.comparingDouble((Direction direction)->
                    -client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos.relative(direction)))));
        }
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            var shape=state.getCollisionShape(client.level,neighbor);
            if (!state.canBeReplaced() && !shape.isEmpty() && shape.bounds().maxY>.65) return null;
        }
        Item material=null;
        for (Item candidate:List.of(Items.COBBLED_DEEPSLATE))
            if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (indices.containsKey(neighbor) || !MaterialTracker.isLoaded(client.level,neighbor)
                    || !MaterialTracker.isLoaded(client.level,neighbor.below())
                    || !client.level.getBlockState(neighbor).canBeReplaced()
                    || !client.level.getBlockState(neighbor.below()).isCollisionShapeFullBlock(client.level,neighbor.below())) continue;
            return new Target(neighbor,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }
    private Target accessSupport(Minecraft client,Target target) {
        if (target.support || target.pos.getY()-client.player.getY()<1.6) return null;
        Item material=null;
        for (Item candidate:List.of(Items.COBBLED_DEEPSLATE))
            if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        Target nearest=null; double distance=Double.POSITIVE_INFINITY;
        // A single adjacent step permits walking onto a two-block support column.
        // Taller access routes remain bounded and must use existing stairs or scaffold terraces.
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=target.pos.below(2).relative(direction);
            if (MaterialTracker.isLoaded(client.level,step)
                    && client.level.getBlockState(step).isCollisionShapeFullBlock(client.level,step)) return null;
        }
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=target.pos.below(2).relative(direction);
            if (indices.containsKey(step) || !MaterialTracker.isLoaded(client.level,step)
                    || !client.level.getBlockState(step).canBeReplaced()
                    || !client.level.getBlockState(step.below()).isCollisionShapeFullBlock(client.level,step.below())) continue;
            double candidate=client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(step));
            if (candidate<distance) { distance=candidate; nearest=new Target(step,item.getBlock().defaultBlockState(),-1,true); }
        }
        return nearest;
    }

    /** Creative inventory packets preserve occupied slots and respect each item's stack limit. */
    public void getMaterials(Minecraft client) {
        if (schematic==null || client.player==null || client.level==null || client.gameMode==null) return;
        if (!client.player.getAbilities().instabuild || !client.gameMode.getPlayerMode().isCreative()) { reason="Get Materials requires Creative access."; return; }
        if (client.player.containerMenu!=client.player.inventoryMenu) { reason="Close the container before getting materials."; return; }
        MaterialTracker.Snapshot snapshot=MaterialTracker.snapshot(client,schematic,transform);
        int added=0, omitted=0;
        for (MaterialTracker.Row row:snapshot.rows()) {
            if (row.item()==Items.AIR) continue;
            int need=Math.max(0,row.remaining()-row.hotbar()-row.inventory());
            for (int slot=0;slot<36 && need>0;slot++) {
                ItemStack existing=client.player.getInventory().getItem(slot);
                if (!existing.isEmpty() && !existing.is(row.item())) continue;
                int room=row.item().getDefaultInstance().getMaxStackSize()-(existing.isEmpty()?0:existing.getCount());
                if (room<=0) continue;
                int count=Math.min(room,need); ItemStack stack=existing.isEmpty()?new ItemStack(row.item(),count):existing.copyWithCount(existing.getCount()+count);
                client.player.getInventory().setItem(slot,stack);
                client.gameMode.handleCreativeModeItemAdd(stack,slot<9?slot+36:slot);
                need-=count; added+=count;
            }
            omitted+=need;
        }
        reason="Added " + added + " items." + (omitted>0?" Inventory full: " + omitted + " items still missing.":"");
    }
}
