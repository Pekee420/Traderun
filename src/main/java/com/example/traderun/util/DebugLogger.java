package com.example.traderun.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Simple debug logger that saves to a file automatically on errors
 */
public final class DebugLogger {
    private static final int MAX_LINES = 100;
    private static final Deque<String> lines = new ArrayDeque<>();
    private static Path LOG_DIR = null;
    private static Path LOG_FILE = null;
    
    private DebugLogger() {}
    
    private static Path getLogDir() {
        if (LOG_DIR == null) {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            LOG_DIR = configDir.resolve("traderun");
            try {
                Files.createDirectories(LOG_DIR);
            } catch (IOException ignored) {}
        }
        return LOG_DIR;
    }
    
    private static Path getLogFile() {
        if (LOG_FILE == null) {
            LOG_FILE = getLogDir().resolve("debug_latest.log");
        }
        return LOG_FILE;
    }
    
    public static synchronized void log(String msg) {
        if (msg == null) return;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String line = "[" + timestamp + "] " + msg;
        if (lines.size() >= MAX_LINES) lines.removeFirst();
        lines.addLast(line);
        
        // Also append to file immediately
        try {
            Files.writeString(getLogFile(), line + "\n", 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[traderun] Failed to write log: " + e.getMessage());
        }
    }
    
    public static synchronized void error(String msg) {
        log("ERROR: " + msg);
        saveNow("error");
    }
    
    public static synchronized void saveNow(String reason) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path debugFile = getLogDir().resolve("debug_" + reason + "_" + timestamp + ".txt");
            
            StringBuilder sb = new StringBuilder();
            sb.append("TradeRun Debug - ").append(reason).append(" - ").append(LocalDateTime.now()).append("\n");
            sb.append("Log file: ").append(debugFile.toAbsolutePath()).append("\n");
            sb.append("---\n");
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            
            Files.writeString(debugFile, sb.toString());
            System.out.println("[traderun] Debug saved to: " + debugFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[traderun] Failed to save debug: " + e.getMessage());
        }
    }
    
    public static synchronized void clear() {
        lines.clear();
        try {
            Files.deleteIfExists(getLogFile());
        } catch (IOException ignored) {}
    }
}

