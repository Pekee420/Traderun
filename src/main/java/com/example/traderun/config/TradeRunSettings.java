package com.example.traderun.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TradeRunSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static TradeRunSettings INSTANCE;

    // Tunables
    public int inputMin = 32;    // half stack
    public int outputMin = 672;  // 10.5 stacks
    public int clickRateMs = 170;
    public int cooldownSec = 600; // 10 minutes default cooldown
    public boolean nightCooldownEnabled = true; // Extend cooldown through night

    // Optional tunables (safe defaults)
    public float yawPerTick = 8.0f;
    public boolean floorLock = true;

    public static TradeRunSettings get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    private static Path path() {
        Path dir = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("config")
                .resolve("traderun");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("settings.json");
    }

    private static TradeRunSettings load() {
        Path p = path();
        if (!Files.exists(p)) return new TradeRunSettings();

        try (Reader r = Files.newBufferedReader(p)) {
            TradeRunSettings s = GSON.fromJson(r, TradeRunSettings.class);
            return (s == null) ? new TradeRunSettings() : s;
        } catch (Throwable ignored) {
            return new TradeRunSettings();
        }
    }

    public static void saveQuiet() {
        try {
            save();
        } catch (Throwable ignored) {}
    }

    public static void save() throws IOException {
        TradeRunSettings s = get();
        Path p = path();
        try (Writer w = Files.newBufferedWriter(p)) {
            GSON.toJson(s, w);
        }
    }

    public long getCooldownMs() {
        return cooldownSec * 1000L;
    }
}

