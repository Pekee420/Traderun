package com.example.traderun.mixin;

import com.example.traderun.cooldown.CooldownRegistry;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.state.VillagerEntityRenderState;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to replace villager texture with red when on cooldown.
 */
@Mixin(VillagerEntityRenderer.class)
public class VillagerRendererMixin {

    @Unique
    private static final Identifier RED_TEXTURE = Identifier.of("traderun", "textures/entity/villager_cooldown.png");
    
    @Unique
    private static VillagerEntity currentVillager = null;

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/passive/VillagerEntity;Lnet/minecraft/client/render/entity/state/VillagerEntityRenderState;F)V",
            at = @At("HEAD"))
    private void traderun_captureVillager(VillagerEntity villager, VillagerEntityRenderState state, float tickDelta, 
                                           org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        currentVillager = villager;
    }

    @Inject(method = "getTexture(Lnet/minecraft/client/render/entity/state/VillagerEntityRenderState;)Lnet/minecraft/util/Identifier;",
            at = @At("HEAD"), cancellable = true)
    private void traderun_getTexture(VillagerEntityRenderState state, CallbackInfoReturnable<Identifier> cir) {
        // Show red texture for cooldown villagers even when mod isn't running
        if (currentVillager == null) return;
        if (!CooldownRegistry.isOnCooldown(currentVillager)) return;
        
        cir.setReturnValue(RED_TEXTURE);
    }
}
