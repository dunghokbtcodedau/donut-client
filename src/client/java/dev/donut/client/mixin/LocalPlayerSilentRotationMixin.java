package dev.donut.client.mixin;

import dev.donut.client.autobuild.SilentRotation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The builder's rotation only exists inside vanilla's own movement packet; see SilentRotation. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerSilentRotationMixin {
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void donut$silentBefore(CallbackInfo ci) {
        SilentRotation.before((LocalPlayer) (Object) this);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void donut$silentAfter(CallbackInfo ci) {
        SilentRotation.after((LocalPlayer) (Object) this);
    }
}
