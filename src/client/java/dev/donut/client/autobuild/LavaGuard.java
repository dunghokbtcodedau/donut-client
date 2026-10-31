package dev.donut.client.autobuild;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;

/** Cheap lava checks shared by the pathfinder and the builder's safety logic. */
final class LavaGuard {
    private LavaGuard() { }

    static boolean lava(ClientLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA);
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
