package com.example.traderun.runtime;

import com.example.traderun.fsm.TradeRunStateMachine;
import net.minecraft.client.MinecraftClient;

public final class TradeRunRuntime {

    private static final TradeRunRuntime INSTANCE = new TradeRunRuntime();

    public static TradeRunRuntime get() {
        return INSTANCE;
    }

    private final TradeRunStateMachine fsm = new TradeRunStateMachine();

    private TradeRunRuntime() {}

    public void start(String professionKey) {
        fsm.startForProfession(professionKey);
    }
    
    public void startMultiple(String professionsString) {
        if (professionsString == null || professionsString.isBlank()) {
            return;
        }
        // Allow comma or space separated lists (e.g. "cleric,toolsmith" or "cleric toolsmith")
        String[] profs = professionsString.trim().split("[,\\s]+");
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String p : profs) {
            if (p == null) continue;
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            list.add(trimmed);
        }
        if (list.isEmpty()) return;
        fsm.startForProfessions(list);
    }
    
    public String startByFloorNames(String floorNamesString) {
        // Parse comma or space-separated floor names
        String[] names = floorNamesString.trim().split("[,\\s]+");
        return fsm.startForFloorNames(java.util.Arrays.asList(names));
    }

    public void stop() {
        fsm.stop();
    }

    public void abort() {
        fsm.abortHard(MinecraftClient.getInstance());
    }

    public void tick(MinecraftClient client) {
        fsm.tick(client);
    }

    public void releaseAllKeys(MinecraftClient client) {
        fsm.releaseAllForcedKeys(client);
    }

    public boolean isActive() {
        return fsm.isActive();
    }

    public TradeRunStateMachine.State getState() {
        return fsm.getState();
    }
    
    public java.util.List<String> getDebugLines(int count) {
        return fsm.getDebugLines(count);
    }
}

