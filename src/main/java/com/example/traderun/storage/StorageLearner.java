package com.example.traderun.storage;

import com.example.traderun.util.DebugLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;

import java.util.Optional;

/**
 * Automatically learns input/output items when the player opens registered storage containers.
 * Works even when the trading bot is not running.
 * 
 * Uses the CONTAINER's position to determine which storage (INPUT or OUTPUT) was opened,
 * then uses PLAYER's Y position to determine which floor to save the item for.
 */
public final class StorageLearner {
    
    private static int capturedPlayerY = 0;
    private static BlockPos capturedContainerPos = null;
    private static boolean wasScreenOpen = false;
    private static long lastCheckMs = 0;
    private static boolean alreadyLearned = false;
    
    private StorageLearner() {}
    
    /**
     * Call this every tick to check if a storage container is open and learn its item.
     */
    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        
        boolean isContainerScreen = (client.currentScreen instanceof GenericContainerScreen) || 
                                    (client.currentScreen instanceof ShulkerBoxScreen);
        
        // Detect screen open transition - capture position at this moment
        if (isContainerScreen && !wasScreenOpen) {
            // Screen just opened - capture player's Y and the block we're looking at
            capturedPlayerY = client.player.getBlockPos().getY();
            capturedContainerPos = getCrosshairBlockPos(client);
            alreadyLearned = false;
            DebugLogger.log("StorageLearner: screen opened, playerY=" + capturedPlayerY + 
                ", containerPos=" + (capturedContainerPos != null ? capturedContainerPos.toShortString() : "null"));
        }
        
        wasScreenOpen = isContainerScreen;
        
        if (!isContainerScreen) {
            capturedContainerPos = null;
            capturedPlayerY = 0;
            alreadyLearned = false;
            return;
        }
        
        // Rate limit checks
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < 500) return;
        lastCheckMs = now;
        
        // Don't re-learn the same container
        if (alreadyLearned) return;
        
        // Must have captured the container position
        if (capturedContainerPos == null) return;
        
        // Use player's Y to find registered storage for this floor
        int floorY = capturedPlayerY;
        
        // Check if this SPECIFIC container matches input or output storage for player's floor
        // We match by container position, not just proximity
        StorageRegistry.Role foundRole = null;
        
        // Check INPUT storage - does the container position match?
        Optional<StorageRegistry.StoredLocation> inputLoc = StorageRegistry.getForY(StorageRegistry.Role.INPUT, floorY);
        if (inputLoc.isPresent() && containerMatchesStorage(capturedContainerPos, inputLoc.get())) {
            foundRole = StorageRegistry.Role.INPUT;
            DebugLogger.log("StorageLearner: container matches INPUT storage");
        }
        
        // Check OUTPUT storage - does the container position match?
        if (foundRole == null) {
            Optional<StorageRegistry.StoredLocation> outputLoc = StorageRegistry.getForY(StorageRegistry.Role.OUTPUT, floorY);
            if (outputLoc.isPresent() && containerMatchesStorage(capturedContainerPos, outputLoc.get())) {
                foundRole = StorageRegistry.Role.OUTPUT;
                DebugLogger.log("StorageLearner: container matches OUTPUT storage");
            }
        }
        
        if (foundRole == null) {
            // Container doesn't match any registered storage for this floor
            return;
        }
        
        // Get the handler to check slots
        var handler = client.player.currentScreenHandler;
        if (handler == null) return;
        
        // Check first slot for item
        if (handler.slots.isEmpty()) return;
        Slot firstSlot = handler.slots.get(0);
        ItemStack stack = firstSlot.getStack();
        
        if (stack.isEmpty()) {
            // Slot is empty - stay silent, no need to spam
            alreadyLearned = true;
            return;
        }
        
        // Learn this item for player's floor Y
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        Optional<Identifier> existing = StorageRegistry.getRememberedItem(foundRole, floorY);
        String roleName = (foundRole == StorageRegistry.Role.INPUT) ? "INPUT" : "OUTPUT";
        
        // Only update if different from what we already have
        if (existing.isEmpty() || !existing.get().equals(itemId)) {
            StorageRegistry.updateRememberedItem(foundRole, floorY, itemId);
            String msg = "§a[traderun] Learned " + roleName + " item: " + itemId.getPath() + " (Y=" + floorY + ")";
            client.player.sendMessage(Text.literal(msg), false);
            DebugLogger.log("StorageLearner: learned " + roleName + " item " + itemId + " for Y=" + floorY);
        }
        // If item already matches, stay silent - no need to spam
        
        alreadyLearned = true;
    }
    
    private static BlockPos getCrosshairBlockPos(MinecraftClient client) {
        if (client.player == null) return null;
        HitResult hit = client.crosshairTarget;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }
    
    /**
     * Check if the container position matches the registered storage location.
     * Allows 1 block tolerance in Y since chests can be at different heights than the floor.
     */
    private static boolean containerMatchesStorage(BlockPos containerPos, StorageRegistry.StoredLocation storageLoc) {
        if (containerPos == null || storageLoc == null) return false;
        
        // X and Z must match exactly
        if (containerPos.getX() != storageLoc.x || containerPos.getZ() != storageLoc.z) {
            return false;
        }
        
        // Y can be within 1 block (chests might be at floor+1)
        return Math.abs(containerPos.getY() - storageLoc.y) <= 1;
    }
}
