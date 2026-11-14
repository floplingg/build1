# Build1

Baritone-style **auto schematic builder** for Litematica, plus an **auto area miner**.
Client-side Fabric mod for Minecraft **1.21.11**.

## Download
Every push builds automatically. The newest jar is on the [`builds` branch](../../tree/builds),
and it's also attached to each run under **Actions**.

Needs **Fabric API**. **Litematica** (+ MaLiLib) is needed for building; the miner works without it.

## Modes
- **Closet** (default): looks like a player. Smooth, mouse-like turning (moves every frame and snaps to your sensitivity's real mouse steps), only clicks what the crosshair actually sees, human-ish random delays, one block at a time, stands still before inventory clicks, never air-places.
- **Blatant**: instant snaps, packet rotations, any click angle, air-place, up to 4 blocks per tick.

Switch in the menu or with `/build1 mode closet|blatant`.

## Builder
1. Load a schematic in Litematica and place it. If you have several, select the one you want in Litematica's placement menu.
2. Press **B** (or `/build1`) → **Build schematic**.

It goes bottom-up, one layer at a time:
- places everything in reach, picking the right item from your hotbar/inventory (and swapping it into the *swap slot*)
- asks the item itself what it would place (the same logic the server runs), so wall signs, standing signs at all 16 angles, wall torches, banners, skulls, stairs, slabs, doors, observers etc. come out facing the right way
- crouches when it has to click a block with a GUI (e.g. a sign on a chest)
- fills sign text from the schematic and closes the sign editor
- stacks double slabs, candles, sea pickles, snow layers
- places water sources and waterlogs blocks with a water bucket, refilling empty buckets from nearby water (or the materials chests)
- builds in serpentine strips: up one 4-wide strip, over, back down the next, over...
- follows Litematica's render layers (Layer mode), so you can build one layer range at a time
- walks to the next unfinished block with its own pathfinder (step up, drop down, diagonals) and can pillar up with dirt/cobble/netherrack to reach high layers
- creative mode: it gives itself the items
- the HUD shows layer progress, wrong blocks and missing materials
- at the end it goes back over anything that couldn't be placed (up to 3 passes)

### Survival restock
Put your materials in a chest (or a few). Mark them by either putting a sign that says **materials** on or next to each one, or looking at it and pressing **Add/remove chest** in the menu (or `/build1 chest`).
When it runs out of something it walks to the chests, opens them and shift-clicks out what the rest of the build still needs, then carries on. If the chests are out too, it waits and checks again every couple of minutes (configurable), so you can just refill them.

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

Commands: `/build1 build | mine | stop | pause | pos1 [x y z] | pos2 [x y z] | clear | chest [clear|scan] | mode closet|blatant`

Settings are saved to `config/build1.properties`.

## Notes
- Litematica only keeps schematic blocks loaded within your render distance. For very large builds, keep the area loaded or walk closer.
- Automation like this is against the rules on many servers. Check before you use it on a server.
