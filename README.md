# Build1

Baritone-style **auto schematic builder** for Litematica, plus an **auto area miner**.
Client-side Fabric mod for Minecraft **1.21.11**.

## Download
Every push builds automatically. The newest jar is on the [`builds` branch](../../tree/builds),
and it's also attached to each run under **Actions**.

Needs **Fabric API**. **Litematica** (+ MaLiLib) is needed for building; the miner works without it.

## Builder
1. Load a schematic in Litematica and place it. If you have several, select the one you want in Litematica's placement menu.
2. Press **B** (or `/build1`) → **Build schematic**.

It goes bottom-up, one layer at a time:
- places everything in reach, picking the right item from your hotbar/inventory (and swapping it into the *swap slot*)
- works out the correct facing for stairs, logs, slabs, doors, chests, observers and so on by testing which look direction and click face gives the schematic's exact block state
- walks to the next unfinished block with its own pathfinder (step up, drop down, diagonals) and can pillar up with dirt/cobble/netherrack to reach high layers
- creative mode: it gives itself the items
- the HUD shows layer progress, wrong blocks and missing materials
- at the end it goes back over anything that couldn't be placed (up to 3 passes)

Options: **Replace wrong** (break blocks that don't match), **Clear extra blocks** (break blocks where the schematic has air), **Pillar up**, **Blocks/tick**, **Delay**.

## Area miner
Set the area by either:
- looking at a block and pressing **Corner 1** / **Corner 2** in the menu (or binding the keys, or `/build1 pos1`, `/build1 pos2`, with optional `x y z`), or
- making an area selection in Litematica (used when no corners are set).

Then **Mine area**. It clears top-down, layer by layer, with the best tool in your inventory, and won't break tools that are about to snap.
Safety: it skips blocks next to lava, won't break the block under you if that would drop you more than 3 blocks or into lava, and pauses at low health.

## Controls
| Key | Action |
|---|---|
| B | Menu |
| X | Stop |
| (unbound) | Pause / resume, miner corner 1, corner 2 |

Commands: `/build1 build | mine | stop | pause | pos1 [x y z] | pos2 [x y z] | clear`

Settings are saved to `config/build1.properties`.

## Notes
- Litematica only keeps schematic blocks loaded within your render distance. For very large builds, keep the area loaded or walk closer.
- Automation like this is against the rules on many servers. Check before you use it on a server.
