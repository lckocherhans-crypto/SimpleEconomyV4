# Simple Economy Plugin

A simple economy for Paper: balances, an auction house, buy orders, and a fixed-price /sell.

**Java players** get a full dialog-based menu system (Paper's native pop-up screens).
**Bedrock players** (via Geyser) get the same functionality through plain chest menus and anvil
text boxes instead, since Paper's dialog screens don't render reliably through Geyser yet. No
decorative glass panes are used anywhere in the chest menus - just the actual buttons - since
filler panes tend to look bad on Bedrock clients.

Which UI a player sees is decided automatically: the plugin checks Floodgate's or Geyser's API (if
either plugin is installed) to detect Bedrock connections. Every player can also override this - see
Settings below.

## Build
**GitHub:** push this project to a repo. The Build action runs automatically; download the jar from
Actions > latest run > Artifacts.

**Locally:** `gradle build`, jar ends up in build/libs/. Drop it into your server's `plugins/` folder.

If dependency resolution or plugin loading complains about a version, edit:
- `build.gradle.kts` -> paper-api, geyser api, or floodgate api versions, and the Java toolchain
- `src/main/resources/plugin.yml` -> api-version

The Geyser/Floodgate dependencies are optional at runtime (`compileOnly` + `softdepend`) - the
plugin works fine without either installed, it just can't tell Bedrock players apart from Java ones
and defaults everyone to the dialog UI in that case.

## Commands
| Command | What it does |
|---|---|
| /bal [player] | Balance (dialog menu on Java, plain text on Bedrock) |
| /pay [player] [amount] | Pay dialog, or pay directly with both arguments |
| /baltop | Leaderboard menu |
| /eco give/take/set <player> <amount> | Admin (simpleeconomy.admin) |
| /eco sellprice <item> <price\|remove> | Set (or remove) an item's base /sell price |
| /eco sellmultiplier [value] | View or set the global sell multiplier |
| /ah | Auction house menu |
| /ah sell [price] | List the item in your hand. No price: opens the "pick an item" flow instead |
| /ah search <text> | Search the auction house |
| /orders | Buy orders menu - click one to fill it |
| /orders create [item] [amount] [price each] | Create an order. Missing arguments open the pick flow instead |
| /orders search <text> | Search orders (Java only for now; Bedrock uses the in-menu search button) |
| /sell | Opens the Quick Sell menu |
| /sell hand | Sell the item in your hand at its fixed base price |
| /sell all | Sell every plain, sellable item in your inventory |
| /esettings | View or change your personal settings |
| /esettings confirmah/confirmlisting/confirmorders <on\|off> | Toggle confirm screens |
| /esettings ui <auto\|java\|bedrock> | Force a menu style, or let the plugin auto-detect |

## Settings (per player)
- **confirmah** - show a confirm screen before buying from the auction house. Default on.
- **confirmlisting** - show a confirm screen before listing an item on the auction house. Default on.
- **confirmorders** - show a confirm screen before placing a buy order (which holds your money).
  Default on.
- **ui** - `auto` (detect Bedrock automatically), `java` (always show dialogs), or `bedrock`
  (always show chest/anvil menus). Useful if detection is ever wrong, or if a Java player just
  prefers the chest-menu style.

Turning a confirm setting off doesn't skip validation (price, funds, inventory space, listing
limits) - it just skips the extra screen once everything already checks out.

## How /sell pricing works
`prices.yml` holds a fixed base price per item, seeded with a starter list on first run. Players
never set these - only an admin can, via `/eco sellprice` or by editing the file - so nobody can
"sell" a dirt block for a fortune. `sell.multiplier` in `config.yml` is a single global knob an
admin can tune to adjust the whole economy without touching every item. Only plain items (no
rename, no enchants) can be sold or listed.
