package dev.donut.client.autobuild;

import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;

/** Cheap lava checks shared by the pathfinder and the builder's safety logic. */
final class LavaGuard {
    /** Positions where the loaded schematic wants lava, and the layer (Y) being built right now. */
    private static volatile Set<BlockPos> planned = Set.of();
    private static volatile int plannedLayer = Integer.MIN_VALUE;

    private LavaGuard() { }

    static void setPlanned(Set<BlockPos> positions) { planned = positions == null ? Set.of() : Set.copyOf(positions); }

    static void setPlannedLayer(int layerY) { plannedLayer = layerY; }

    /**
     * True for a cell the schematic will fill with lava on the layer being built, even if it is still empty.
     * The player must not stand there to place blocks. Other layers are not affected.
     */
    static boolean plannedOnLayer(BlockPos pos) { return pos.getY() == plannedLayer && planned.contains(pos); }

    static boolean lava(ClientLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA);
    }

    /** Same as {@link #near(ClientLevel, BlockPos)}, but lava in the two given columns is ignored (diagonal corner cells). */
    static boolean nearIgnoring(ClientLevel level, BlockPos feet, BlockPos ignoreA, BlockPos ignoreB) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos at = feet.above(dy);
            if (lava(level, at) && !column(at, ignoreA, ignoreB)) return true;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = at.relative(side);
                if (lava(level, beside) && !column(beside, ignoreA, ignoreB)) return true;
            }
        }
        return lava(level, feet.below());
    }

    private static boolean column(BlockPos at, BlockPos a, BlockPos b) {
        return (at.getX() == a.getX() && at.getZ() == a.getZ()) || (at.getX() == b.getX() && at.getZ() == b.getZ());
    }

    /** Lava in the feet or head cell, in the four cells beside them, or right under the feet. */
    static boolean near(ClientLevel level, BlockPos feet) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos at = feet.above(dy);
            if (lava(level, at)) return true;
            for (Direction side : Direction.Plane.HORIZONTAL) if (lava(level, at.relative(side))) return true;
        }
        return lava(level, feet.below());
    }
}
