# Traderun Mod v0.9.5

**Pekee's Traderun Mod** — Automatic villager trading hall automation for Minecraft 1.21.4.

## Features

- 🏃 **Auto-navigation** to villagers using Baritone (with fallback direct walk)
- 🔄 **Multi-floor support** — trade across multiple levels automatically
- 📦 **Automatic restocking** — fetches input items from storage when low
- 📤 **Automatic dumping** — deposits output items when inventory is full
- ⏰ **Smart cooldowns** — day/night aware villager cooldown tracking
- 🎯 **Profession filtering** — trade only with specific villager types
- 💾 **Persistent settings** — all configurations saved between sessions
- 🧠 **Auto item learning** — opens a storage chest and it remembers the trade item!

## Requirements

### Required
- **Minecraft 1.21.4**
- **Fabric Loader 0.15.0+**
- **Fabric API**
- **Baritone** (for pathfinding) — Must be the Fabric version for 1.21.4

### Optional (Recommended)
- **Mod Menu** — for easy access to mod settings
- **Cloth Config** — for settings UI

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.4
2. Download and install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download and install [Baritone](https://github.com/cabaletta/baritone) (Fabric version)
4. Place `traderun-0.9.5.jar` in your `.minecraft/mods/` folder
5. Launch Minecraft!

## Quick Start

### 1. Set Up Storage (Required First!)
Look at your **input chest** (items you trade TO villagers) and run:
```
/traderun storage set input
```

Look at your **output chest** (items you get FROM villagers) and run:
```
/traderun storage set output
```

**💡 Tip:** After setting storage, just open the chest and the mod automatically learns what item to look for from the first slot!

### 2. Register Your Floors
Stand on a floor with villagers and run:
```
/traderun floor add <name> <profession>
```
Example: `/traderun floor add clerics cleric`

### 3. Start Trading!
```
/traderun start <floor_name>
```
Example: `/traderun start clerics`

Or trade multiple floors:
```
/traderun start clerics toolsmiths
```

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
| `/traderun floor del` | Delete current floor |
| `/traderun floor list` | List all registered floors |

### Settings Commands
| Command | Description |
|---------|-------------|
| `/traderun set inputmin <count>` | Minimum input items before restocking |
| `/traderun set outputmin <count>` | Output count to trigger dumping |
| `/traderun set cooldown <seconds>` | Villager trade cooldown time |
| `/traderun cooldown reset` | Clear all villager cooldowns |

### Debug Commands
| Command | Description |
|---------|-------------|
| `/traderun debug` | Show debug information |
| `/traderun debug t` | Test pathfinding to nearby villager |

## How It Works

1. **SEEK** — Finds nearest eligible villager on the current floor
2. **APPROACH** — Navigates to a position where you can interact
3. **TRADE** — Opens trade GUI (uses your AutoTrade mod settings)
4. **WAIT** — Waits for trade cooldown
5. **RESTOCK/DUMP** — Goes to storage when needed
6. **FLOOR SWITCH** — Changes floors when all villagers are on cooldown

## Tips

- **AutoTrade Setup**: Configure your trade whitelist in your mod client's AutoTrade feature BEFORE running Traderun
- **Item Learning**: Just open your input/output chests once — the mod learns what items to look for from slot 1
- **Storage Per Floor**: Each Y-level can have its own input/output chests with different items
- **Don't Stand on Trading Blocks**: The bot avoids stepping on blocks taller than carpet
- **Villager Cooldowns**: Villagers need ~2 minutes between trades to restock
- **Floor Navigation**: No need to set transition points — the bot uses Baritone to navigate between floors

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
- Make sure Baritone is installed
- Try `/traderun debug t` to test pathfinding

### Fell off platform
The bot will automatically navigate back to the floor using your storage chest location as a waypoint.

## Config Files

All settings are saved in `.minecraft/config/traderun/`:
- `settings.json` — General settings
- `storages.json` — Storage chest locations & remembered items per floor
- `floors.json` — Registered floor data
- `cooldowns.json` — Villager cooldown timers

## What's New in 0.9.5

- ✨ **Auto item learning** — Open a registered storage chest and it remembers the item!
- 🚀 **No transition points needed** — Bot navigates between floors automatically
- 🛡️ **Better fall recovery** — Automatically returns to floor if you fall off
- 📋 **Enhanced storage list** — Shows remembered items per floor
- 🧹 **Code cleanup** — Removed unused code, fixed warnings

## License

AGPL-3.0-or-later

---

Made with ❤️ by Pekee
