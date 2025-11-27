package com.example.traderun;

import com.example.traderun.command.TradeRunCommands;
import com.example.traderun.cooldown.CooldownRegistry;
import com.example.traderun.cooldown.RestockWatcher;
import com.example.traderun.runtime.TradeRunRuntime;
import com.example.traderun.storage.StorageLearner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ChatScreen;

public final class TradeRunClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            TradeRunCommands.register(dispatcher);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Release forced keys when chat is open
            if (client.currentScreen instanceof ChatScreen) {
                TradeRunRuntime.get().releaseAllKeys(client);
            }
            
            TradeRunRuntime.get().tick(client);
            CooldownRegistry.tick(client);
            RestockWatcher.tick(client);
            
            // Learn items from storage containers even when bot is not running
            StorageLearner.tick(client);
        });
    }
}

