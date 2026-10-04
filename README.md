# Table Game (桌游模拟器)

Build your own tabletop games inside Minecraft — a NeoForge mod with an in-game editor for cards, pieces, boards and rules, plus blocks that run the game with your friends.

在 Minecraft 里搭建属于你自己的桌游：游戏内编辑器做卡牌、棋子与规则，游戏台上开局对战。

## Features

| | |
|---|---|
| **Card Table** `tablegame:game_table` | Tables connect automatically when placed side by side (connected textures, corner pieces) |
| **Chairs** `tablegame:chair` | Sittable — seat entity, four-way collision, boat-style dismount |
| **Game Table** `tablegame:dealer` | Right-click to pick a game and start a match; the round is driven by your game definition |
| **In-game editor** | `/tablegame games` — scripts (visual node graph **and** text views), stage/UI canvas, cards, pieces, asset library, values, world areas, and vanilla data (loot tables / recipes / villager trades / tags) |
| **Drawing board** | `/tablegame drawboard` — real-time collaborative pixel art with named projects and JSON import/export |

## Requirements

- Minecraft **26.1.2**
- NeoForge **26.1.2.97** (or a compatible 26.1.2 build)
- Java 21

## Installation

1. Install NeoForge for Minecraft 26.1.2.
2. Put `tablegame-<version>.jar` into the `mods/` folder — **on both the client and the server**.
3. Launch. Everything appears in the creative tab **Table Game** (right after vanilla *Combat*). No survival recipes yet.

## Getting started

1. Place a **Card Table** and a few **Chairs** around it — or skip straight to the editor.
2. Run `/tablegame games` to open the **editor**: create a game, design its cards and pieces, lay out its UI on the stage canvas, and write its rules in the script view.
3. Place a **Game Table**, right-click it, choose your game and start a match. Players who join the table take part; the script decides what happens.
4. Artwork, loot tables, recipes, villager trades and tags are edited from the same editor.

Game data is stored as plain JSON + text under `<world or game folder>/tablegame/` — hand-editable, and games can be exported and imported.

### Commands

| Command | What it does |
|---|---|
| `/tablegame games` | Open the editor's game list (main entry point) |
| `/tablegame drawboard` | Open the multiplayer drawing board |
| `/tablegame group …` | Drawing-board groups (create / invite / join / leave / rule / readonly …) |

## Building from source

```bash
./gradlew build        # → build/libs/tablegame-<version>.jar
./gradlew runClient    # dev client
./gradlew runServer    # dev dedicated server (console accepts commands)
```

## License

MIT — see [LICENSE](LICENSE).

## 中文速览

- **要求**：Minecraft 26.1.2 + NeoForge 26.1.2.97（Java 21）
- **安装**：jar 放进 `mods/`，**客户端与服务端都要装**；物品在创造栏「Table Game」
- **上手**：`/tablegame games` 开编辑器做游戏 → 摆**游戏台**右键选游戏开一桌；`/tablegame drawboard` 开多人画板
- **数据**：`<世界或游戏目录>/tablegame/`（纯 JSON / 文本，可手改、可导入导出）
- **许可**：MIT
