# TradeRun Mod v1.0.7

**Pekee's TradeRun Mod** — Professional automated villager trading hall bot for Minecraft 1.21.4.

## Features

- 🏃 **Advanced Navigation** — Baritone-powered pathfinding with intelligent fallback to direct walk
- 🔄 **Multi-Floor Automation** — Seamless trading across multiple Y-levels with automatic transitions
- 📦 **Smart Inventory Management** — Automatic restocking/dumping with optimized item handling
- 📤 **Output Optimization** — Skips unnecessary dumps when output matches next floor's input
- ⏰ **Intelligent Cooldowns** — Day/night-aware villager tracking with particle detection fallback
- 🎯 **Profession Targeting** — Trade only with specific villager professions per floor
- 👁️ **Visual Indicators** — Red pumpkin heads on cooldown villagers, colored storage markers
- 🕒 **Background Operation** — Continues trading even when Minecraft window is unfocused
- 🛡️ **Anti-Fall Protection** — Edge detection prevents walking off platforms
- 📊 **Queue Detection** — Pauses trading during server queues with automatic resume
- 💾 **Persistent Configuration** — All settings saved between Minecraft sessions
- 🧠 **Auto Item Learning** — Opens a storage chest once and remembers the trade items forever

## Requirements

### Required
- **Minecraft 1.21.4**
- **Fabric Loader 0.15.0+**
- **Fabric API**
- **Baritone** (for pathfinding) — Must be the Fabric version for 1.21.4
- **AutoTrade** (for trade whitelist) — Required for the bot to actually trade with villagers

### Optional (Recommended)
- **Mod Menu** — for easy access to mod settings
- **Cloth Config** — for settings UI

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.4
2. Download and install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download and install [Baritone](https://github.com/cabaletta/baritone) (Fabric version)
4. Download and install [AutoTrade](https://modrinth.com/mod/autotrade) (for trade whitelisting)
5. Place `traderun-1.0.7.jar` in your `.minecraft/mods/` folder
6. Launch Minecraft!

## Quick Start

### 1. Configure AutoTrade (Required!)
Set up your trade whitelist in AutoTrade first:
- Configure which trades you want to do automatically
- This ensures the bot only trades the items you want

### 2. Set Up Storage (Required!)
Look at your **input chest** (items you trade TO villagers) and run:
```
/traderun storage set input
```

Look at your **output chest** (items you get FROM villagers) and run:
```
/traderun storage set output
```

**💡 IMPORTANT:** After setting storage, just open the chest and the mod automatically learns what item to look for from the first slot!

### 3. Register Your Floors
Stand on a floor with villagers and run:
```
/traderun floor add <name> <profession>
```
Example: `/traderun floor add clerics cleric`

**💡 Tab Completion:** Press Tab after `<profession>` to see all available villager professions!

### 4. Start Trading!
```
/traderun start <floor_name>
```
Example: `/traderun start clerics`

Or trade multiple floors:
```
/traderun start clerics toolsmiths
```

**🎯 Background Mode:** Once started, you can tab out to other applications - the bot keeps trading!

**⏸️ Manual Pause:** Press ESC while running for a 10-second countdown to tab out, then auto-resumes.

## Commands Reference

### Core Commands
| Command | Description |
|---------|-------------|
| `/traderun start <floors...>` | Start trading on named floor(s) |
| `/traderun stop` | Stop trading |
| `/traderun status` | Show current status |
| `/traderun help` | Show help (use `/traderun help <topic>` for details) |

### Storage Commands
| Command | Description |
|---------|-------------|
| `/traderun storage set input` | Set input chest (look at chest) |
| `/traderun storage set output` | Set output chest (look at chest) |
| `/traderun storage del input/output` | Delete storage location |
| `/traderun storage clear input/output/all` | Clear remembered items |
| `/traderun storage list` | List all storage with remembered items |

### Floor Commands
| Command | Description |
|---------|-------------|
| `/traderun floor add <name> <profession>` | Register floor with name & profession |
| `/traderun floor del <name>` | Delete floor by name |
| `/traderun floor rescan` | Rescan current floor for villagers |
| `/traderun floor list` | List all registered floors |

### Settings Commands
| Command | Description |
|---------|-------------|
| `/traderun set inputmin <count>` | Minimum input items before restocking (default: 32) |
| `/traderun set outputmin <count>` | Output count to trigger dumping (default: 672) |
| `/traderun set cooldown <seconds>` | Villager trade cooldown time (default: 600) |
| `/traderun set storageTimeout <seconds>` | Storage navigation timeout (default: 60) |
| `/traderun set noTradeTimer <minutes>` | Max wait time when all villagers on cooldown (default: 20) |
| `/traderun cooldown reset` | Clear all villager cooldowns |
| `/traderun cooldown clearall` | Force clear ALL cooldowns (emergency) |

### Debug Commands
| Command | Description |
|---------|-------------|
| `/traderun debug` | Show debug information |
| `/traderun debug t` | Test pathfinding to nearby villager |

## How It Works

The bot operates in a sophisticated state machine:

1. **SEEK** — Finds nearest eligible villager on the current floor
2. **APPROACH** — Uses Baritone for advanced pathfinding to reach villager interaction points
3. **TRADE** — Opens trade GUI (requires AutoTrade mod for whitelisted trades)
4. **COOLDOWN** — Waits for villager restock (detects happy villager particles + timer fallback)
5. **RESTOCK/DUMP** — Intelligently manages inventory, skips unnecessary dumps for multi-floor setups
6. **FLOOR TRANSITION** — Seamlessly navigates between Y-levels with edge detection
7. **BACKGROUND MODE** — Continues operation even when Minecraft window is unfocused
8. **QUEUE DETECTION** — Automatically pauses during server queues and resumes when clear

**Visual Indicators:**
- 🔴 **Red Pumpkin Heads** — Villagers currently on cooldown
- 🟢 **Green Markers** — Input storage chests
- 🔴 **Red Markers** — Output storage chests

## Tips

- **AutoTrade Setup**: Configure your trade whitelist in AutoTrade BEFORE running TradeRun - required for trading!
- **Background Operation**: Tab out to other apps while trading - the bot continues automatically
- **ESC Pause**: Press ESC for 10-second countdown to safely tab out, then auto-resumes
- **Item Learning**: Open input/output chests once — the mod remembers items forever
- **Multi-Floor Optimization**: Output items skip dumping if they're the input for the next floor
- **Visual Indicators**: Look for red pumpkin heads on cooldown villagers
- **Queue Detection**: Bot automatically pauses during server queues (0,0 coordinate detection)
- **Edge Protection**: Bot won't walk off platforms thanks to advanced edge detection
- **Night Cooldowns**: During night time, villagers take longer to restock (configurable)
- **Storage Per Floor**: Each Y-level can have different input/output items and locations

## Troubleshooting

### "No storage configured"
Run `/traderun storage set input` and `/traderun storage set output` while looking at your chests.

### "No valid approach for villager"
The villager may be surrounded by obstacles. Make sure there's at least one clear path to stand next to them.

### Bot trading wrong items / not restocking
Clear the remembered item: `/traderun storage clear input`
Then open your input chest to re-learn the correct item.

### Bot keeps stalling
- Check if you have the correct input items
- Make sure storage chests have items/space
- Verify floor is registered with `/traderun floor list`

### Navigation not working
- Make sure Baritone is installed and configured
- Try `/traderun debug t` to test pathfinding
- Check for obstacles blocking villager access

### Fell off platform
The bot has advanced edge detection and won't walk off platforms. If it does fall, it will automatically navigate back using Baritone.

### ESC menu keeps opening
This is normal when tabbing out - the bot blocks auto-pause but allows manual ESC with countdown.

### Bot not trading in background
Make sure you're running on a server that allows background operation. Single-player should work fine.

### Queue detection not working
The bot detects queues at coordinates 0,0 with a 10-block radius tolerance.

### Red pumpkin heads not showing
Cooldown villagers show red pumpkin textures client-side only (doesn't affect server).

## Config Files

All settings are saved in `.minecraft/config/traderun/`:
- `settings.json` — General settings
- `storages.json` — Storage chest locations & remembered items per floor
- `floors.json` — Registered floor data
- `cooldowns.json` — Villager cooldown timers

## What's New in v1.0.7

- 🎯 **Background Operation** — Trade while tabbed out to other applications
- ⏸️ **ESC Pause System** — 10-second countdown when manually pressing ESC
- 👁️ **Visual Indicators** — Red pumpkin heads on cooldown villagers, colored storage markers
- 🛡️ **Edge Detection** — Prevents walking off platforms with hole detection
- 📊 **Queue Detection** — Automatic pause/resume during server queues
- 🌙 **Night Cooldowns** — Villagers restock slower at night (configurable)
- 🔄 **Smart Multi-Floor** — Skips unnecessary dumps when output matches next floor input
- 📋 **Enhanced Commands** — Tab completion, floor rescanning, emergency cooldown clearing
- 🐛 **Navigation Fixes** — Improved pathfinding, stuck detection, and recovery
- ⚙️ **Better Defaults** — Optimized inventory thresholds and cooldown settings

## License

AGPL-3.0-or-later

---

Made with ❤️ by Pekee
