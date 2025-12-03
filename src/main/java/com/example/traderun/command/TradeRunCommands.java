package com.example.traderun.command;

import com.example.traderun.config.TradeRunSettings;
import com.example.traderun.cooldown.CooldownRegistry;
import com.example.traderun.floor.FloorRegistry;
import com.example.traderun.runtime.TradeRunRuntime;
import com.example.traderun.storage.StorageRegistry;
import com.example.traderun.villager.VillagerFinder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class TradeRunCommands {
    private TradeRunCommands() {}
    
    // All Minecraft villager professions for tab completion
    private static final String[] PROFESSIONS = {
        "armorer", "butcher", "cartographer", "cleric", "farmer",
        "fisherman", "fletcher", "leatherworker", "librarian", "mason",
        "nitwit", "none", "shepherd", "toolsmith", "weaponsmith"
    };
    
    private static final SuggestionProvider<FabricClientCommandSource> PROFESSION_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        // Get what's already typed (to support multiple professions)
        String[] parts = remaining.split("\\s+");
        String currentWord = parts.length > 0 ? parts[parts.length - 1] : "";
        
        for (String prof : PROFESSIONS) {
            if (prof.startsWith(currentWord)) {
                builder.suggest(remaining.isEmpty() ? prof : 
                    remaining.substring(0, remaining.length() - currentWord.length()) + prof);
            }
        }
        return builder.buildFuture();
    };
    
    // Suggestions for both professions AND registered floor names
    private static final SuggestionProvider<FabricClientCommandSource> PROFESSION_AND_FLOOR_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        String[] parts = remaining.split("\\s+");
        String currentWord = parts.length > 0 ? parts[parts.length - 1] : "";
        
        // Suggest professions
        for (String prof : PROFESSIONS) {
            if (prof.startsWith(currentWord)) {
                builder.suggest(remaining.isEmpty() ? prof : 
                    remaining.substring(0, remaining.length() - currentWord.length()) + prof);
            }
        }
        
        // Also suggest registered floor names
        for (String floorName : FloorRegistry.getAllFloorNames()) {
            String lower = floorName.toLowerCase();
            if (lower.startsWith(currentWord)) {
                builder.suggest(remaining.isEmpty() ? floorName : 
                    remaining.substring(0, remaining.length() - currentWord.length()) + floorName);
            }
        }
        
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        register(dispatcher, null);
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(
                literal("traderun")
                        // /traderun start <profession|floorname> [profession2|floorname2] ...
                        // Auto-detects if arguments are floor names or professions
                        .then(literal("start")
                                .then(argument("targets", StringArgumentType.greedyString())
                                        .suggests(PROFESSION_AND_FLOOR_SUGGESTIONS)
                                        .executes(ctx -> {
                                            String input = StringArgumentType.getString(ctx, "targets");
                                            String result = TradeRunRuntime.get().startSmartDetect(input);
                                            msg(result);
                                            return 1;
                                        })))

                        // /traderun stop
                        .then(literal("stop").executes(ctx -> {
                            TradeRunRuntime.get().stop();
                            msg("stopped");
                            return 1;
                        }))

                        // /traderun abort
                        .then(literal("abort").executes(ctx -> {
                            TradeRunRuntime.get().abort();
                            msg("aborted");
                            return 1;
                        }))

                        // /traderun status
                        .then(literal("status").executes(ctx -> {
                            MinecraftClient c = MinecraftClient.getInstance();
                            boolean active = TradeRunRuntime.get().isActive();
                            String state = TradeRunRuntime.get().getState().name();
                            VillagerFinder finder = new VillagerFinder();
                            String counts = finder.debugCounts(c);
                            msg("active=" + active + " state=" + state);
                            msg(counts);
                            return 1;
                        }))

                        // /traderun cooldown
                        .then(literal("cooldown")
                                .executes(ctx -> {
                                    int count = CooldownRegistry.count();
                                    int sec = TradeRunSettings.get().cooldownSec;
                                    msg("Cooldown: " + sec + "s (" + (sec/60) + "m" + (sec%60) + "s), " + count + " villagers on cooldown");
                                    msg("Use: /traderun cooldown clearall | /traderun set cooldownSec <seconds>");
                                    return 1;
                                })
                                .then(literal("reset").executes(ctx -> {
                                    CooldownRegistry.resetAll();
                                    msg("All cooldowns cleared");
                                    return 1;
                                }))
                                .then(literal("clearall").executes(ctx -> {
                                    CooldownRegistry.resetAll();
                                    msg("All villager cooldowns cleared - villagers ready to trade");
                                    return 1;
                                })))

                        // /traderun storage ...
                        .then(literal("storage")
                                .then(literal("set")
                                        .then(literal("input").executes(ctx -> {
                                            BlockPos pos = lookedAtBlockPos();
                                            if (pos == null) return 0;
                                            StorageRegistry.setForPlayerFloor(StorageRegistry.Role.INPUT, pos);
                                            msg("INPUT storage set (this floor)");
                                            return 1;
                                        }))
                                        .then(literal("output").executes(ctx -> {
                                            BlockPos pos = lookedAtBlockPos();
                                            if (pos == null) return 0;
                                            StorageRegistry.setForPlayerFloor(StorageRegistry.Role.OUTPUT, pos);
                                            msg("OUTPUT storage set (this floor)");
                                            return 1;
                                        })))
                                .then(literal("del")
                                        .then(literal("input").executes(ctx -> {
                                            StorageRegistry.deleteForPlayerFloor(StorageRegistry.Role.INPUT);
                                            msg("INPUT storage deleted (this floor)");
                                            return 1;
                                        }))
                                        .then(literal("output").executes(ctx -> {
                                            StorageRegistry.deleteForPlayerFloor(StorageRegistry.Role.OUTPUT);
                                            msg("OUTPUT storage deleted (this floor)");
                                            return 1;
                                        })))
                                .then(literal("clear")
                                        .then(literal("input").executes(ctx -> {
                                            int y = playerFloorY();
                                            StorageRegistry.clearRememberedItem(StorageRegistry.Role.INPUT, y);
                                            msg("Cleared remembered INPUT item for Y=" + y);
                                            return 1;
                                        }))
                                        .then(literal("output").executes(ctx -> {
                                            int y = playerFloorY();
                                            StorageRegistry.clearRememberedItem(StorageRegistry.Role.OUTPUT, y);
                                            msg("Cleared remembered OUTPUT item for Y=" + y);
                                            return 1;
                                        }))
                                        .then(literal("all").executes(ctx -> {
                                            StorageRegistry.clearAllRememberedItems();
                                            msg("Cleared ALL remembered items for all floors");
                                            return 1;
                                        })))
                                .then(literal("list").executes(ctx -> {
                                    var details = StorageRegistry.getDetailedList();
                                    if (details.isEmpty()) {
                                        msg("No storage set. Use /traderun storage set input/output");
                                    } else {
                                        msg("§6Storage locations:");
                                        for (String line : details) {
                                            msg("  " + line);
                                        }
                                    }
                                    return 1;
                                })))

                        // /traderun floor ...
                        .then(literal("floor")
                                .then(literal("add")
                                        // /traderun floor add <name> <profession>
                                        .then(argument("floorName", StringArgumentType.word())
                                                .then(argument("profession", StringArgumentType.word())
                                                        .suggests(PROFESSION_SUGGESTIONS)
                                                        .executes(ctx -> {
                                                            String floorName = StringArgumentType.getString(ctx, "floorName");
                                                            String prof = StringArgumentType.getString(ctx, "profession");
                                                            MinecraftClient c = MinecraftClient.getInstance();
                                                            FloorRegistry.ScanResult result = FloorRegistry.scanFloor(c, prof);
                                                            msg(result.message);
                                                            if (result.floorY != null) {
                                                                FloorRegistry.setFloorName(result.floorY, floorName);
                                                                msg("Floor named: " + floorName);
                                                            }
                                                            if (result.clusterCenter != null) {
                                                                msg("Cluster center: " + result.clusterCenter.getX() + ", " + result.clusterCenter.getZ());
                                                            }
                                                            return 1;
                                                        }))))
                                // /traderun floor name <name> - set name for current floor
                                .then(literal("name")
                                        .then(argument("floorName", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String floorName = StringArgumentType.getString(ctx, "floorName");
                                                    MinecraftClient c = MinecraftClient.getInstance();
                                                    if (c == null || c.player == null) return 0;
                                                    int y = c.player.getBlockPos().getY();
                                                    if (FloorRegistry.setFloorName(y, floorName)) {
                                                        msg("Floor Y=" + y + " named: " + floorName);
                                                    } else {
                                                        msg("No floor registered at Y=" + y);
                                                    }
                                                    return 1;
                                                })))
                                .then(literal("list").executes(ctx -> {
                                    var floors = FloorRegistry.getAllFloors();
                                    if (floors.isEmpty()) {
                                        msg("No floors registered. Use /traderun floor add <name> <profession>");
                                    } else {
                                        msg("Registered floors:");
                                        for (var f : floors) {
                                            String nameStr = (f.name != null && !f.name.isEmpty()) ? " [" + f.name + "]" : "";
                                            msg("  Y=" + f.y + nameStr + ": " + String.join(", ", f.professions) + 
                                                " (" + f.villagerCount + " villagers)");
                                        }
                                    }
                                    return 1;
                                }))
                                .then(literal("clear").executes(ctx -> {
                                    FloorRegistry.clear();
                                    msg("All floors cleared");
                                    return 1;
                                }))
                                .then(literal("del")
                                        .then(argument("floorName", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String floorName = StringArgumentType.getString(ctx, "floorName");
                                                    // Try to find floor by name
                                                    var floors = FloorRegistry.getAllFloors();
                                                    boolean found = false;
                                                    for (var f : floors) {
                                                        if (floorName.equalsIgnoreCase(f.name)) {
                                                            FloorRegistry.removeFloor(f.y);
                                                            msg("Deleted floor: " + floorName + " (Y=" + f.y + ")");
                                                            found = true;
                                                            break;
                                                        }
                                                    }
                                                    // Try by Y level
                                                    if (!found) {
                                                        try {
                                                            int y = Integer.parseInt(floorName);
                                                            FloorRegistry.removeFloor(y);
                                                            msg("Deleted floor at Y=" + y);
                                                            found = true;
                                                        } catch (NumberFormatException ignored) {}
                                                    }
                                                    if (!found) {
                                                        msg("Floor not found: " + floorName);
                                                        msg("Use floor name or Y level (e.g., /traderun floor del clerics OR /traderun floor del 30)");
                                                    }
                                                    return 1;
                                                })))
                                .then(literal("rescan").executes(ctx -> {
                                    MinecraftClient c = MinecraftClient.getInstance();
                                    if (c == null || c.player == null) return 0;
                                    int y = c.player.getBlockPos().getY();
                                    var floors = FloorRegistry.getAllFloors();
                                    FloorRegistry.FloorInfo currentFloor = null;
                                    for (var f : floors) {
                                        if (f.y == y) {
                                            currentFloor = f;
                                            break;
                                        }
                                    }
                                    if (currentFloor == null) {
                                        msg("No floor registered at Y=" + y);
                                        msg("Use /traderun floor add <name> <profession> first");
                                        return 0;
                                    }
                                    // Rescan with the first profession
                                    String prof = currentFloor.professions.isEmpty() ? "" : 
                                        currentFloor.professions.iterator().next();
                                    FloorRegistry.ScanResult result = FloorRegistry.scanFloor(c, prof);
                                    msg("Rescanned floor " + currentFloor.getDisplayName() + ": " + result.message);
                                    return 1;
                                }))
                                .then(literal("transition")
                                        .then(argument("targetY", IntegerArgumentType.integer(-64, 320))
                                                .executes(ctx -> {
                                                    int targetY = IntegerArgumentType.getInteger(ctx, "targetY");
                                                    MinecraftClient c = MinecraftClient.getInstance();
                                                    String result = FloorRegistry.addTransition(c, targetY);
                                                    msg(result);
                                                    return 1;
                                                })))
                                .then(literal("transitions").executes(ctx -> {
                                    var trans = FloorRegistry.getAllTransitions();
                                    if (trans.isEmpty()) {
                                        msg("No transitions. Stand on stairs and use /traderun floor transition <targetY>");
                                    } else {
                                        msg("Transitions:");
                                        for (var t : trans) {
                                            msg("  Y=" + t.fromY + " -> Y=" + t.toY + " at " + t.x + ", " + t.z);
                                        }
                                    }
                                    return 1;
                                })))

                        // /traderun cooldown ...
                        .then(literal("cooldown")
                                .then(literal("current").executes(ctx -> {
                                    int sec = TradeRunSettings.get().cooldownSec;
                                    int min = sec / 60;
                                    int s = sec % 60;
                                    msg("Current cooldown: " + sec + "s (" + min + "m" + s + "s)");
                                    msg("Night extension: " + (TradeRunSettings.get().nightCooldownEnabled ? "ON" : "OFF"));
                                    msg("Active cooldowns: " + CooldownRegistry.count());
                                    return 1;
                                }))
                                .then(literal("set")
                                        .then(argument("seconds", IntegerArgumentType.integer(0, 3600))
                                                .executes(ctx -> {
                                                    int sec = IntegerArgumentType.getInteger(ctx, "seconds");
                                                    TradeRunSettings.get().cooldownSec = sec;
                                                    TradeRunSettings.saveQuiet();
                                                    int min = sec / 60;
                                                    int s = sec % 60;
                                                    msg("Cooldown set to " + sec + "s (" + min + "m" + s + "s)");
                                                    return 1;
                                                })))
                                .then(literal("night")
                                        .then(literal("true").executes(ctx -> {
                                            TradeRunSettings.get().nightCooldownEnabled = true;
                                            TradeRunSettings.saveQuiet();
                                            msg("Night cooldown extension: ON");
                                            return 1;
                                        }))
                                        .then(literal("false").executes(ctx -> {
                                            TradeRunSettings.get().nightCooldownEnabled = false;
                                            TradeRunSettings.saveQuiet();
                                            msg("Night cooldown extension: OFF");
                                            return 1;
                                        })))
                                .then(literal("reset").executes(ctx -> {
                                    CooldownRegistry.resetAll();
                                    msg("All cooldowns cleared");
                                    return 1;
                                })))

                        // /traderun set ...
                        .then(literal("set")
                                .then(literal("inputMin")
                                        .then(argument("n", IntegerArgumentType.integer(0, 9999))
                                                .executes(ctx -> {
                                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                                    TradeRunSettings.get().inputMin = n;
                                                    TradeRunSettings.saveQuiet();
                                                    msg("inputMin=" + n);
                                                    return 1;
                                                })))
                                .then(literal("outputMin")
                                        .then(argument("n", IntegerArgumentType.integer(0, 999999))
                                                .executes(ctx -> {
                                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                                    TradeRunSettings.get().outputMin = n;
                                                    TradeRunSettings.saveQuiet();
                                                    msg("outputMin=" + n);
                                                    return 1;
                                                })))
                                .then(literal("clickRate")
                                        .then(argument("ms", IntegerArgumentType.integer(80, 1000))
                                                .executes(ctx -> {
                                                    int ms = IntegerArgumentType.getInteger(ctx, "ms");
                                                    TradeRunSettings.get().clickRateMs = ms;
                                                    TradeRunSettings.saveQuiet();
                                                    msg("clickRateMs=" + ms);
                                                    return 1;
                                                })))
                                .then(literal("cooldownSec")
                                        .then(argument("sec", IntegerArgumentType.integer(0, 3600))
                                                .executes(ctx -> {
                                                    int sec = IntegerArgumentType.getInteger(ctx, "sec");
                                                    TradeRunSettings.get().cooldownSec = sec;
                                                    TradeRunSettings.saveQuiet();
                                                    msg("cooldownSec=" + sec);
                                                    return 1;
                                                }))))

                        // /traderun debug - saves last 30 debug lines to file
                        .then(literal("debug").executes(ctx -> {
                            List<String> lines = TradeRunRuntime.get().getDebugLines(30);
                            if (lines.isEmpty()) {
                                msg("No debug lines recorded yet");
                                return 0;
                            }
                            try {
                                Path configDir = FabricLoader.getInstance().getConfigDir();
                                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                                Path debugFile = configDir.resolve("traderun_debug_" + timestamp + ".txt");
                                
                                StringBuilder sb = new StringBuilder();
                                sb.append("TradeRun Debug Log - ").append(LocalDateTime.now()).append("\n");
                                sb.append("State: ").append(TradeRunRuntime.get().getState()).append("\n");
                                sb.append("Active: ").append(TradeRunRuntime.get().isActive()).append("\n");
                                sb.append("---\n");
                                for (String line : lines) {
                                    sb.append(line).append("\n");
                                }
                                
                                Files.writeString(debugFile, sb.toString());
                                msg("Debug saved to: " + debugFile.getFileName());
                                msg("Path: " + debugFile.toAbsolutePath());
                            } catch (IOException e) {
                                msg("Failed to save debug: " + e.getMessage());
                                return 0;
                            }
                            return 1;
                        }))
                        
                        // /traderun help
                        .then(literal("help")
                                .executes(ctx -> {
                                    showHelpOverview();
                                    return 1;
                                })
                                .then(literal("setup").executes(ctx -> {
                                    showHelpSetup();
                                    return 1;
                                }))
                                .then(literal("floor").executes(ctx -> {
                                    showHelpFloor();
                                    return 1;
                                }))
                                .then(literal("storage").executes(ctx -> {
                                    showHelpStorage();
                                    return 1;
                                }))
                                .then(literal("trading").executes(ctx -> {
                                    showHelpTrading();
                                    return 1;
                                }))
                                .then(literal("settings").executes(ctx -> {
                                    showHelpSettings();
                                    return 1;
                                }))
                                .then(literal("cooldown").executes(ctx -> {
                                    showHelpCooldown();
                                    return 1;
                                })))
                                
                        // /traderun (no args) - show quick help
                        .executes(ctx -> {
                            showQuickHelp();
                            return 1;
                        })
        );
    }

    private static void msg(String text) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) return;
        c.player.sendMessage(Text.literal("[traderun] " + text), false);
    }
    
    private static void helpMsg(String text) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) return;
        c.player.sendMessage(Text.literal(text), false);
    }
    
    private static void showQuickHelp() {
        helpMsg("§6§l=== TRADERUN ===");
        helpMsg("§eAutomatic villager trading hall automation");
        helpMsg("");
        helpMsg("§f/traderun help §7- Full help");
        helpMsg("§f/traderun help setup §7- Quick setup guide");
        helpMsg("§f/traderun start <floor1> [floor2] §7- Start trading");
        helpMsg("§f/traderun stop §7- Stop trading");
        helpMsg("§f/traderun status §7- Show status");
    }
    
    private static void showHelpOverview() {
        helpMsg("§6§l=== TRADERUN HELP ===");
        helpMsg("");
        helpMsg("§e§lTopics:§r (use /traderun help <topic>)");
        helpMsg("§f  setup §7- Quick setup guide (START HERE!)");
        helpMsg("§f  floor §7- Floor registration commands");
        helpMsg("§f  storage §7- Input/output chest commands");
        helpMsg("§f  trading §7- Start/stop trading commands");
        helpMsg("§f  settings §7- Configure thresholds");
        helpMsg("§f  cooldown §7- Villager cooldown settings");
        helpMsg("");
        helpMsg("§e§lQuick Commands:§r");
        helpMsg("§f/traderun start <floor> §7- Start trading");
        helpMsg("§f/traderun stop §7- Stop trading");
        helpMsg("§f/traderun status §7- Show status");
        helpMsg("§f/traderun debug §7- Save debug log");
    }
    
    private static void showHelpSetup() {
        helpMsg("§6§l=== SETUP GUIDE ===");
        helpMsg("");
        helpMsg("§e§lStep 1: Register a floor");
        helpMsg("§7Stand on a floor with villagers, run:");
        helpMsg("§f  /traderun floor add <name> <profession>");
        helpMsg("§7Example: §f/traderun floor add clerics cleric");
        helpMsg("");
        helpMsg("§e§lStep 2: Set input storage");
        helpMsg("§7Look at your INPUT chest (items you trade TO villagers):");
        helpMsg("§f  /traderun storage set input");
        helpMsg("");
        helpMsg("§e§lStep 3: Set output storage");
        helpMsg("§7Look at your OUTPUT chest (items you get FROM villagers):");
        helpMsg("§f  /traderun storage set output");
        helpMsg("");
        helpMsg("§e§lStep 4: Start trading!");
        helpMsg("§f  /traderun start clerics");
        helpMsg("");
        helpMsg("§7Repeat steps 1-3 for each floor, then:");
        helpMsg("§f  /traderun start clerics toolsmiths");
    }
    
    private static void showHelpFloor() {
        helpMsg("§6§l=== FLOOR COMMANDS ===");
        helpMsg("");
        helpMsg("§e§lRegister floor:§r");
        helpMsg("§f/traderun floor add <name> <profession>");
        helpMsg("§7Scans for villagers and registers floor with name");
        helpMsg("§7Example: /traderun floor add tools toolsmith");
        helpMsg("");
        helpMsg("§e§lRename floor:§r");
        helpMsg("§f/traderun floor name <name>");
        helpMsg("§7Renames the floor at your Y level");
        helpMsg("");
        helpMsg("§e§lList floors:§r");
        helpMsg("§f/traderun floor list");
        helpMsg("§7Shows all registered floors");
        helpMsg("");
        helpMsg("§e§lDelete floor:§r");
        helpMsg("§f/traderun floor del <name|Y>");
        helpMsg("§7Delete by name or Y level");
        helpMsg("");
        helpMsg("§e§lRescan floor:§r");
        helpMsg("§f/traderun floor rescan");
        helpMsg("§7Re-counts villagers on current floor");
        helpMsg("");
        helpMsg("§e§lClear all floors:§r");
        helpMsg("§f/traderun floor clear");
        helpMsg("§7Removes all floor data");
        helpMsg("");
        helpMsg("§e§lTransitions (for multi-floor):§r");
        helpMsg("§f/traderun floor transition <targetY>");
        helpMsg("§7Stand on stairs, set transition to target Y");
        helpMsg("§f/traderun floor transitions");
        helpMsg("§7List all transitions");
    }
    
    private static void showHelpStorage() {
        helpMsg("§6§l=== STORAGE COMMANDS ===");
        helpMsg("");
        helpMsg("§7Storage is set §lper floor§r§7 (by Y level)");
        helpMsg("");
        helpMsg("§e§lSet input chest:§r");
        helpMsg("§f/traderun storage set input");
        helpMsg("§7Look at chest, run command. Items you GIVE to villagers.");
        helpMsg("");
        helpMsg("§e§lSet output chest:§r");
        helpMsg("§f/traderun storage set output");
        helpMsg("§7Look at chest, run command. Items you GET from villagers.");
        helpMsg("");
        helpMsg("§e§lDelete storage:§r");
        helpMsg("§f/traderun storage del input|output");
        helpMsg("");
        helpMsg("§e§lClear learned items:§r");
        helpMsg("§f/traderun storage clear input|output|all");
        helpMsg("§7Clears remembered items (re-learn from chest)");
        helpMsg("");
        helpMsg("§e§lList storage:§r");
        helpMsg("§f/traderun storage list");
        helpMsg("");
        helpMsg("§c§lNote:§r Items auto-learned from first slot when you open chest!");
    }
    
    private static void showHelpTrading() {
        helpMsg("§6§l=== TRADING COMMANDS ===");
        helpMsg("");
        helpMsg("§e§lStart trading:§r");
        helpMsg("§f/traderun start <floor1> [floor2] ...");
        helpMsg("§7Examples:");
        helpMsg("§f  /traderun start clerics");
        helpMsg("§f  /traderun start clerics toolsmiths");
        helpMsg("§f  /traderun start cleric,toolsmith §7(by profession)");
        helpMsg("");
        helpMsg("§e§lStop trading:§r");
        helpMsg("§f/traderun stop §7- Graceful stop");
        helpMsg("§f/traderun abort §7- Immediate stop");
        helpMsg("");
        helpMsg("§e§lCheck status:§r");
        helpMsg("§f/traderun status");
        helpMsg("§7Shows state, villager counts, cooldowns");
        helpMsg("");
        helpMsg("§e§lHow it works:§r");
        helpMsg("§71. Finds villagers on current floor");
        helpMsg("§72. Navigates and opens trade (uses AutoTrade)");
        helpMsg("§73. Restocks from input chest when low");
        helpMsg("§74. Dumps to output chest when full");
        helpMsg("§75. Switches floors when all villagers on cooldown");
    }
    
    private static void showHelpSettings() {
        helpMsg("§6§l=== SETTINGS ===");
        helpMsg("");
        helpMsg("§e§lInput threshold:§r");
        helpMsg("§f/traderun set inputMin <count>");
        helpMsg("§7Restock when input items fall below this (default: 32)");
        helpMsg("");
        helpMsg("§e§lOutput threshold:§r");
        helpMsg("§f/traderun set outputMin <count>");
        helpMsg("§7Dump when output items exceed this (default: 672)");
        helpMsg("");
        helpMsg("§e§lClick rate:§r");
        helpMsg("§f/traderun set clickRate <ms>");
        helpMsg("§7Delay between container clicks (default: 170ms)");
        helpMsg("");
        helpMsg("§e§lCooldown:§r");
        helpMsg("§f/traderun set cooldownSec <seconds>");
        helpMsg("§7Max time before re-trading (default: 840s/14min)");
        helpMsg("§7Note: Villagers are cleared early when restock particles detected");
        helpMsg("");
        helpMsg("§7Settings saved to config/traderun/settings.json");
    }
    
    private static void showHelpCooldown() {
        helpMsg("§6§l=== COOLDOWN SETTINGS ===");
        helpMsg("");
        helpMsg("§7Villagers need time to restock at their workstation.");
        helpMsg("§7Timer-based cooldown (default 14 min) tracks this.");
        helpMsg("");
        helpMsg("§e§lView cooldown:§r");
        helpMsg("§f/traderun cooldown current");
        helpMsg("");
        helpMsg("§e§lSet cooldown time:§r");
        helpMsg("§f/traderun cooldown set <seconds>");
        helpMsg("§7Default: 840s (14min). Villagers restock 2x per day.");
        helpMsg("");
        helpMsg("§e§lNight extension:§r");
        helpMsg("§f/traderun cooldown night true|false");
        helpMsg("§7If ON, cooldowns extend through night (villagers sleep)");
        helpMsg("§7Villagers only restock during day in Minecraft.");
        helpMsg("");
        helpMsg("§e§lClear all cooldowns:§r");
        helpMsg("§f/traderun cooldown clearall");
        helpMsg("§7Clears all villager cooldowns immediately");
        helpMsg("");
        helpMsg("§e§lVisual indicators:§r");
        helpMsg("§7Orange particles = on cooldown, §agreen§7 = INPUT, §cred§7 = OUTPUT");
    }

    private static BlockPos lookedAtBlockPos() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) return null;

        HitResult hr = c.player.raycast(6.0, 0.0f, false);
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) {
            msg("look at the storage block (<=6 blocks)");
            return null;
        }
        return bhr.getBlockPos().toImmutable();
    }
    
    private static int playerFloorY() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) return 0;
        return c.player.getBlockPos().getY();
    }
}

