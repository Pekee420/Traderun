package com.example.traderun.mixin;

import com.example.traderun.runtime.EscPauseManager;
import com.example.traderun.runtime.TradeRunRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders TradeRun countdown on ESC menu
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }
    
    @Inject(method = "render", at = @At("TAIL"))
    private void traderun_renderCountdown(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!TradeRunRuntime.get().isActive()) {
            return;
        }
        
        if (!EscPauseManager.isInPause()) {
            return;
        }
        
        int seconds = EscPauseManager.getRemainingSeconds();
        
        // Draw background box at BOTTOM of screen to avoid menu interference
        int boxWidth = 240;
        int boxHeight = 44;
        int boxX = (this.width - boxWidth) / 2;
        int boxY = this.height - boxHeight - 10;  // Bottom of screen
        
        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xDD000000);
        context.drawBorder(boxX, boxY, boxWidth, boxHeight, 0xFF00FF00);
        
        // Draw text
        String line1 = "§a§l⚡ TRADERUN ACTIVE";
        String line2 = "§e" + seconds + " seconds to tab out";
        String line3 = "§7Sneak to stop mod";
        
        int textX1 = boxX + (boxWidth - this.textRenderer.getWidth(line1.replaceAll("§.", ""))) / 2;
        int textX2 = boxX + (boxWidth - this.textRenderer.getWidth(line2.replaceAll("§.", ""))) / 2;
        int textX3 = boxX + (boxWidth - this.textRenderer.getWidth(line3.replaceAll("§.", ""))) / 2;
        
        context.drawText(this.textRenderer, Text.literal(line1), textX1, boxY + 5, 0x00FF00, true);
        context.drawText(this.textRenderer, Text.literal(line2), textX2, boxY + 17, 0xFFFF00, true);
        context.drawText(this.textRenderer, Text.literal(line3), textX3, boxY + 29, 0xAAAAAA, true);
    }
}

