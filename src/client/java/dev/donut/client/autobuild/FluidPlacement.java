package dev.donut.client.autobuild;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/** Uses BucketItem's source-only POV raycast; never writes world state or sends custom packets. */
public final class FluidPlacement {
    private FluidPlacement() { }

    public static boolean needsWater(BlockState desired,BlockState actual) {
        return desired.getBlock()==actual.getBlock()
                && desired.hasProperty(BlockStateProperties.WATERLOGGED)
                && desired.getValue(BlockStateProperties.WATERLOGGED)
                && !actual.getValue(BlockStateProperties.WATERLOGGED);
    }
    public static Item requiredItem(BlockState desired,BlockState actual) {
        if (desired.is(Blocks.WATER) && desired.getValue(LiquidBlock.LEVEL)==0) return Items.WATER_BUCKET;
        if (desired.is(Blocks.LAVA) && desired.getValue(LiquidBlock.LEVEL)==0) return Items.LAVA_BUCKET;
        return needsWater(desired,actual) ? Items.WATER_BUCKET : null;
    }
    public static boolean isBucket(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }
    public static PlacementPlanner.Plan plan(Minecraft client,BlockPos target,BlockState desired,ItemStack stack) {
        if (client.player==null || client.level==null || !MaterialTracker.isLoaded(client.level,target)) return null;
        BlockState actual=client.level.getBlockState(target);
        Item item=requiredItem(desired,actual);
        if (item==null || !stack.is(item)) return null;
        boolean water=item==Items.WATER_BUCKET;
        if (water && net.minecraft.world.level.Level.NETHER.equals(client.level.dimension())) return null;
        boolean logging=needsWater(desired,actual);
        if (logging && (!(actual.getBlock() instanceof LiquidBlockContainer container)
                || !container.canPlaceLiquid(client.player,client.level,target,actual,Fluids.WATER))) return null;
        if (!logging && !actual.canBeReplaced()) return null;
        BlockState prediction=logging ? actual.setValue(BlockStateProperties.WATERLOGGED,true) : desired;
        List<BlockHitResult> hits=new ArrayList<>();
        if (logging) PlacementPlanner.addFaces(hits,target,actual,client);
        else for (Direction face:Direction.values()) {
            BlockPos neighbor=target.relative(face.getOpposite());
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            BlockState support=client.level.getBlockState(neighbor);
            if (support.isAir() || support.canBeReplaced()) continue;
            // Vanilla targets a liquid container itself rather than the adjacent cell.
            if (water && support.getBlock() instanceof LiquidBlockContainer container
                    && container.canPlaceLiquid(client.player,client.level,neighbor,support,Fluids.WATER)) continue;
            PlacementPlanner.addFace(hits,neighbor,face,support,client);
        }
        for (BlockHitResult hit:hits) {
            PlacementPlanner.Plan plan=PlacementPlanner.interactionPlan(client,hit,prediction,ClipContext.Fluid.SOURCE_ONLY);
            if (plan!=null) return plan;
        }
        return null;
    }
}
