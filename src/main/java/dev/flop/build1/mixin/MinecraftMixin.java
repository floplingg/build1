package dev.flop.build1.mixin;

import dev.flop.build1.bot.Breaker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla calls stopDestroyBlock() every tick the attack key isn't held, which would
 * reset our mining progress. While Build1 is mining we drive the destroy loop ourselves.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void build1$skipVanillaAttack(boolean down, CallbackInfo ci) {
        if (Breaker.active) ci.cancel();
    }
}
