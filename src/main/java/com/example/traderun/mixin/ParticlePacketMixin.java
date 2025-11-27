package com.example.traderun.mixin;

import com.example.traderun.cooldown.RestockWatcher;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts particle packets to detect villager restock events.
 * Works even with particles disabled (norender).
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ParticlePacketMixin {
    
    @Inject(method = "onParticle", at = @At("HEAD"))
    private void onParticleReceived(ParticleS2CPacket packet, CallbackInfo ci) {
        RestockWatcher.onParticlePacket(packet);
    }
}


