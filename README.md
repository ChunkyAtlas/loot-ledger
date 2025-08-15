# Loot Ledger

## Overview

**Loot Ledger** is a plugin that shows NPC drop tables directly inside the **Music** tab and lets you track which items you’ve already obtained. It supports per‑account or per‑NPC tracking, a compact progress bar, powerful search, hover tooltips, and quick toggles to hide/only‑show obtained drops. Data is stored locally per character and updates live as you loot.

## Features

- **Music Tab Drop Viewer**
  - Right‑click an NPC and choose **Show Drops** to load its table from the wiki.
  - Drop icons render in a grid with a progress bar (obtained/total).
  - Hover an icon to see a tooltip with **item name** and **rarity**.

- **Obtained Tracking**
  - Toggle **Track obtained items** in config to enable persistence.
  - Choose scope: **Per Account** or **Per NPC** (e.g., Man, Skeleton, Goblin).
  - Obtained items are **dimmed** (when tracking is enabled). If tracking is **off**, **nothing is dimmed**.
  - Left‑click any icon to **mark obtained / un‑obtain** manually.
  - The viewer **updates live** while open whenever you pick up items or receive loot (inventory/ground).

- **Visibility Controls**
  - Eye button cycles through: **Show All** → **Hide Obtained** → **Only Obtained**.
  - Search button lets you quickly **find and load** any Droptable or NPC by name/ID.

- **Drop Table Options**
  - **Sort drops by rarity** (common to rare) or leave natural order.
  - **Show Rare Drop Table** and **Show Gem Drop Table** toggles.

- **Caching & Resolution**
  - Drop tables are cached per NPC to disk and auto‑refreshed weekly.
  - A bundled **Items.json** (item name to item IDs) is used to resolve **all items**, including untradeables.

## Configuration

Open **Loot Ledger** settings:

- **Track obtained items** – Enable/disable persistence & dimming. When off, items are **never dimmed** and are treated as obtained for display (no saves).
- **Tracking scope** – **Per Account** or **Per NPC**.
- **Obtained visibility** – Default live view for the eye toggle (All / Hide Obtained / Only Obtained).
- **Show Rare Drop Table** – Include RDT items in lists.
- **Show Gem Drop Table** – Include gem table items in lists.
- **Sort by rarity** – Order icons from common -> rare.

Changing visibility/sort/gem/RDT options will re‑render the viewer; stale caches are pruned automatically.

## Usage

1. **Show an NPC’s drops**
   - Right‑click the NPC -> **Show Drops**. Or click the **search** icon in the Music tab and enter a name/ID.
2. **Review progress**
   - The progress bar shows **obtained/total** (or **all/total** if tracking is off).
3. **Toggle visibility**
   - Click the **eye** icon to cycle All -> Hide Obtained -> Only Obtained.
4. **Mark items manually**
   - **Left‑click** any icon to **obtain/un‑obtain** it. (Respects the configured scope.)
5. **Automatic updates**
   - When **Track obtained items** is enabled, picking up loot or receiving items will update the viewer **live** if it’s open.

## File Locations

- **Obtained items (per account)**  
  `~/.runelite/lootledger/<player_name>/obtained.json`  
  Backups (rotating, up to 10):  
  `~/.runelite/lootledger/<player_name>/backups/obtained.json.<timestamp>.bak`

- **Drop‑table cache (per account)**  
  `~/.runelite/lootledger/<player_name>/drops/<npcId>_<Name>_<Level>.json`  
  Old cache files are pruned after ~7 days.

## Notes

- When **Track obtained items** is **disabled**, the viewer treats all items as obtained **for display** (no dimming), and nothing is written to disk.
- Tracking scope affects both automatic detection and manual clicks.
- Caching reduces wiki requests; stale or invalid files are cleaned up automatically.

## Contribution

Issues and PRs are welcome! If you have ideas or fixes please open an issue on GitHub!

## Contact

Questions or support? Open an issue on GitHub.