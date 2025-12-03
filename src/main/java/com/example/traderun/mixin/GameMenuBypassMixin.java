package com.example.traderun.mixin;

import com.example.traderun.runtime.EscPauseManager;
import com.example.traderun.runtime.TradeRunRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Background operation for TradeRun:
 * - Prevent game from pausing when TradeRun is active (even when tabbed out)
 * - Block ESC menu from appearing when window loses focus
 * - Allow manual ESC with 10s countdown
 */
@Mixin(MinecraftClient.class)
public abstract class GameMenuBypassMixin {
    
    @Shadow
    public Screen currentScreen;
    
    @Shadow
    public abstract void setScreen(Screen screen);
    
    @Unique
    private boolean traderun_userPressedEsc = false;
    
    /**
     * Keep game running (not paused) when TradeRun is active - even when tabbed out!
     */
    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void traderun_notPaused(CallbackInfoReturnable<Boolean> cir) {
        if (TradeRunRuntime.get().isActive()) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * Block automatic ESC menu when window loses focus.
     * Only allow ESC if user manually pressed it.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void traderun_blockAutoEsc(Screen screen, CallbackInfo ci) {
        if (!TradeRunRuntime.get().isActive()) {
            return;
        }
        
        // If trying to open ESC menu
        if (screen instanceof GameMenuScreen) {
            MinecraftClient client = (MinecraftClient)(Object)this;
            
            // Check if this is from window losing focus (automatic) vs user pressing ESC
            // Window focus loss happens when isWindowFocused() is false
            if (client.isWindowFocused()) {
                // User manually pressed ESC - allow it with countdown
                if (!(currentScreen instanceof GameMenuScreen)) {
                    traderun_userPressedEsc = true;
                    EscPauseManager.startPause();
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§a§l⏸ TRADERUN: 10s to tab out, sneak to stop"), false);
                    }
                }
                // Allow the ESC menu to open
            } else {
                // Window not focused - this is auto-pause from tabbing out
                // BLOCK IT - don't let ESC menu open
                ci.cancel();
            }
        }
    }
    
    /**
     * On each tick, close ESC menu when countdown expires
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void traderun_autoCloseEsc(CallbackInfo ci) {
        if (!TradeRunRuntime.get().isActive()) {
            traderun_userPressedEsc = false;
            return;
        }
        
        // If ESC is open and pause countdown has expired, close it
        if (currentScreen instanceof GameMenuScreen && traderun_userPressedEsc && !EscPauseManager.isInPause()) {
            MinecraftClient client = (MinecraftClient)(Object)this;
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§a▶ Resuming TradeRun..."), true);
            }
            traderun_userPressedEsc = false;
            setScreen(null);
        }
    }
}
