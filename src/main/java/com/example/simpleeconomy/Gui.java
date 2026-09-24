package com.example.simpleeconomy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Consumer;

/**
 * Every menu is a plain chest inventory, plus anvils for the handful of places that need typed
 * text (a search term, a price, an amount). No custom Paper dialogs anywhere - Geyser translates
 * chests and anvils reliably for Bedrock players; the newer Dialog packet does not.
 */
public class Gui implements Listener {

    private static final int PICKER_PAGE = 45;

    private static abstract class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class AhHolder extends Holder {
        int page;
        boolean mine;
        final List<Listing> shown = new ArrayList<>();
    }

    private static class BuyConfirmHolder extends Holder {
        Listing listing;
    }

    /** Confirms listing an item on the auction house. */
    private static class SellConfirmHolder extends Holder {
        ItemStack snapshot;
        double price;
    }

    private static class OrdersHolder extends Holder {
        int page;
        boolean mine;
        final List<Order> shown = new ArrayList<>();
    }

    /** Confirms creating a buy order. */
    private static class OrderConfirmHolder extends Holder {
        Material material;
        int amount;
        double priceEach;
    }

    /** Picks a stack from the player's own inventory (e.g. what to list on the AH). */
    private static class InvPickerHolder extends Holder {
        final List<Integer> slotsShown = new ArrayList<>(); // inventory slot each button represents
    }

    /** Picks any material in the game (e.g. what to request in a buy order). */
    private static class MaterialPickerHolder extends Holder {
        int page;
        String query;
        final List<Material> shown = new ArrayList<>();
    }

    /** Lets the player click their own sellable items to sell them instantly at the fixed base price. */
    private static class QuickSellHolder extends Holder {
        final List<Integer> slotsShown = new ArrayList<>();
    }

    /** A one-field anvil text prompt. Typed text is read live from the anvil's rename field. */
    private static class AnvilInputHolder extends Holder {
        Consumer<String> onSubmit;
    }

    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final AuctionManager auction;
    private final OrderManager orders;
    private final PriceManager prices;
    private final PlayerSettings settings;
    private final SignInput signInput;
    private final List<Material> allItems;

    public Gui(SimpleEconomyPlugin plugin, EconomyManager eco, AuctionManager auction, OrderManager orders,
               PriceManager prices, PlayerSettings settings, SignInput signInput) {
        this.plugin = plugin;
        this.eco = eco;
        this.auction = auction;
        this.orders = orders;
        this.prices = prices;
        this.settings = settings;
        this.signInput = signInput;

        Set<Material> blocked = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("orders.blocked-items")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blocked.add(m);
        }
        this.allItems = Arrays.stream(Material.values())
                .filter(m -> !m.name().startsWith("LEGACY_") && m.isItem() && !m.isAir() && !blocked.contains(m))
                .sorted(Comparator.comparing(Material::name))
                .toList();
    }

    // ------------------------------------------------------------ helpers

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.displayName(Msg.lore(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Msg::lore).toList());
        i.setItemMeta(meta);
        return i;
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private static boolean matchesAll(String haystack, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = haystack.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ anvil text input

    /**
     * Opens a one-field anvil prompt. The player types into the anvil's own rename box; clicking
     * the result slot reads that text and calls onSubmit. Closing without clicking the result does
     * nothing. The item placed in the anvil is always a plain placeholder, never a real item of
     * the player's, so there's no item-loss risk if they close it.
     */
    public void openAnvilInput(Player p, String promptItemName, String initialText, String confirmLabel, Consumer<String> onSubmit) {
        AnvilInputHolder h = new AnvilInputHolder();
        h.onSubmit = onSubmit;
        Inventory inv = Bukkit.createInventory(h, InventoryType.ANVIL, Component.text(promptItemName));
        h.inv = inv;
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.displayName(Msg.lore(initialText == null || initialText.isEmpty() ? " " : initialText));
        paper.setItemMeta(meta);
        inv.setItem(0, paper);
        inv.setItem(2, button(Material.LIME_DYE, confirmLabel, "&7Type in the box above, then click here"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (!(e.getInventory().getHolder() instanceof AnvilInputHolder)) return;
        AnvilInventory inv = e.getInventory();
        inv.setRepairCost(0);
        // Always keep a clickable confirm item in the result slot, regardless of what a normal
        // anvil combine would produce - we only care about the rename text, not a crafted result.
        e.setResult(button(Material.LIME_DYE, "&aConfirm"));
    }

    // ------------------------------------------------------------ auction house

    public void openAh(Player p, int page, boolean mine) {
        AhHolder h = new AhHolder();
        h.mine = mine;
        List<Listing> list = auction.all().stream()
                .filter(l -> !mine || l.seller().equals(p.getUniqueId()))
                .sorted(Comparator.comparingLong(Listing::expiresAt).reversed())
                .toList();

        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        h.page = page;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text(mine ? "Your Listings" : "Auction House (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            h.shown.add(l);
            inv.setItem(i, listingDisplay(l, mine));
        }

        if (page > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(46, mine
                ? button(Material.CHEST, "&eBack to Auction House")
                : button(Material.PLAYER_HEAD, "&eYour listings", "&7View or cancel your listings"));
        inv.setItem(47, button(Material.OAK_SIGN, "&bSearch", "&eClick to search", "&eRight-click to clear"));
        inv.setItem(48, button(Material.ENDER_CHEST, "&6Expired items",
                "&7Items waiting: &f" + auction.expiredCount(p.getUniqueId()), "&eClick to claim"));
        inv.setItem(49, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Click to refresh"));
        inv.setItem(51, button(Material.EMERALD, "&aSell an item", "&7List something from your inventory"));
        inv.setItem(52, button(Material.HOPPER, "&bQuick Sell", "&7Sell items for their base value"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    public void openAhSearch(Player p, String query) {
        if (query == null || query.isBlank()) {
            openAh(p, 0, false);
            return;
        }
        AhHolder h = new AhHolder();
        List<Listing> list = auction.all().stream()
                .filter(l -> matchesListing(l, query))
                .sorted(Comparator.comparingLong(Listing::expiresAt).reversed())
                .toList();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Search: \"" + query + "\""));
        h.inv = inv;
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Listing l = list.get(i);
            h.shown.add(l);
            inv.setItem(i, listingDisplay(l, l.seller().equals(p.getUniqueId())));
        }
        inv.setItem(46, button(Material.CHEST, "&eBack to Auction House"));
        p.openInventory(inv);
    }

    private boolean matchesListing(Listing l, String query) {
        return matchesAll(l.item().getType().name().replace('_', ' ') + " " + l.sellerName(), query);
    }

    private ItemStack listingDisplay(Listing l, boolean mine) {
        ItemStack it = l.item().clone();
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Msg.lore("&7Price: &a" + MoneyUtil.format(l.price())));
        lore.add(Msg.lore("&7Seller: &f" + l.sellerName()));
        lore.add(Msg.lore("&7Expires in: &f" + Msg.time(l.expiresAt() - System.currentTimeMillis())));
        lore.add(Component.empty());
        lore.add(Msg.lore(mine ? "&cClick to cancel and get the item back" : "&eClick to buy"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void openBuyConfirm(Player p, Listing l) {
        BuyConfirmHolder h = new BuyConfirmHolder();
        h.listing = l;
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Purchase"));
        h.inv = inv;
        inv.setItem(13, listingDisplay(l, false));
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aConfirm", "&7Pay &a" + MoneyUtil.format(l.price())));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    /** Opens the buy confirm screen, unless the player has turned AH confirmations off. */
    public void maybeConfirmBuy(Player p, Listing l) {
        if (!settings.confirmAh(p.getUniqueId())) {
            String err = auction.buy(p, l);
            p.sendMessage(Msg.c(err != null ? err : "&aPurchased for &2" + MoneyUtil.format(l.price()) + "&a."));
            return;
        }
        openBuyConfirm(p, l);
    }

    public void openSellConfirm(Player p, ItemStack snapshot, double price) {
        SellConfirmHolder h = new SellConfirmHolder();
        h.snapshot = snapshot;
        h.price = price;
        double tax = plugin.getConfig().getDouble("auction.tax-percent", 0);
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Listing"));
        h.inv = inv;
        ItemStack display = snapshot.clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.lore("&7Price: &a" + MoneyUtil.format(price)));
        if (tax > 0) lore.add(Msg.lore("&7You receive after tax: &e" + MoneyUtil.format(price - price * tax / 100.0)));
        lore.add(Msg.lore("&7Lasts &f" + plugin.getConfig().getLong("auction.duration-hours", 48) + " hours&7."));
        meta.lore(lore);
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aList it"));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    private void claimExpired(Player p) {
        List<ItemStack> items = auction.takeExpired(p.getUniqueId());
        if (items.isEmpty()) {
            p.sendMessage(Msg.c("&cYou have no expired items."));
            return;
        }
        int given = 0;
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (left.isEmpty()) given++;
            else left.values().forEach(x -> auction.addExpired(p.getUniqueId(), x));
        }
        p.sendMessage(Msg.c("&aClaimed &f" + given + "&a expired item stack(s)."
                + (auction.expiredCount(p.getUniqueId()) > 0 ? " &eInventory full - claim the rest later." : "")));
    }

    // ------------------------------------------------------------ sell-an-item flow (no commands)

    /** Step 1: pick a stack from the player's inventory. */
    public void openSellPicker(Player p) {
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            return;
        }
        InvPickerHolder h = new InvPickerHolder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Pick an Item to Sell"));
        h.inv = inv;
        PlayerInventory pinv = p.getInventory();
        int slot = 0;
        for (int i = 0; i < 36 && slot < 45; i++) {
            ItemStack it = pinv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            h.slotsShown.add(i);
            inv.setItem(slot++, sellPickerDisplay(it));
        }
        inv.setItem(49, button(Material.ARROW, "&eBack to Auction House"));
        p.openInventory(inv);
    }

    private ItemStack sellPickerDisplay(ItemStack it) {
        ItemStack display = it.clone();
        display.setAmount(it.getAmount());
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Msg.lore("&eClick to list this whole stack"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /** Step 2: ask for a price with an anvil, then hand off to the confirm screen (or skip it, per settings). */
    public void promptSellPrice(Player p, ItemStack snapshot) {
        openAnvilInput(p, "Type a price (e.g. 500)", "", "&aContinue", text -> {
            OptionalDouble price = MoneyUtil.parse(text);
            double min = plugin.getConfig().getDouble("auction.min-price", 1);
            if (price.isEmpty() || price.getAsDouble() < min) {
                p.sendMessage(Msg.c("&cInvalid price (minimum " + MoneyUtil.format(min) + "). Try again."));
                later(() -> promptSellPrice(p, snapshot));
                return;
            }
            later(() -> maybeConfirmSell(p, snapshot, price.getAsDouble()));
        });
    }

    // ------------------------------------------------------------ quick sell (fixed base prices, no commands)

    public void openQuickSell(Player p) {
        QuickSellHolder h = new QuickSellHolder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Quick Sell"));
        h.inv = inv;
        PlayerInventory pinv = p.getInventory();
        int slot = 0;
        double total = 0;
        for (int i = 0; i < 36 && slot < 45; i++) {
            ItemStack it = pinv.getItem(i);
            if (!InvUtil.isPlain(it)) continue;
            OptionalDouble each = prices.sellPrice(it.getType());
            if (each.isEmpty()) continue;
            double value = MoneyUtil.round(each.getAsDouble() * it.getAmount());
            total += value;
            h.slotsShown.add(i);
            ItemStack display = it.clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Msg.lore("&7Price each: &a" + MoneyUtil.format(each.getAsDouble())));
            lore.add(Msg.lore("&7Stack value: &a" + MoneyUtil.format(value)));
            lore.add(Component.empty());
            lore.add(Msg.lore("&eClick to sell this stack"));
            meta.lore(lore);
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }
        inv.setItem(45, button(Material.ARROW, "&eBack"));
        inv.setItem(49, button(Material.GOLD_INGOT, "&aSell everything shown here",
                "&7Total: &2" + MoneyUtil.format(MoneyUtil.round(total))));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ orders

    public void openOrders(Player p, int page, boolean mine) {
        OrdersHolder h = new OrdersHolder();
        h.mine = mine;
        List<Order> list = orders.all().stream()
                .filter(o -> mine ? o.owner.equals(p.getUniqueId()) : o.remaining() > 0)
                .sorted(Comparator.<Order>comparingDouble(o -> o.priceEach).reversed())
                .toList();

        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        h.page = page;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text(mine ? "Your Orders" : "Orders (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Order o = list.get(idx);
            h.shown.add(o);
            inv.setItem(i, orderDisplay(o, mine));
        }

        if (page > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(46, mine
                ? button(Material.CHEST, "&eBack to all orders")
                : button(Material.PLAYER_HEAD, "&eYour orders", "&7Collect items or cancel orders"));
        inv.setItem(48, button(Material.BOOK, "&fCreate an order", "&7Pick an item, amount and price"));
        inv.setItem(49, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Click to refresh"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    private ItemStack orderDisplay(Order o, boolean mine) {
        ItemStack it = new ItemStack(o.material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.lore("&f" + ItemNames.pretty(o.material)));
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.lore("&7Price each: &a" + MoneyUtil.format(o.priceEach)));
        if (mine) {
            lore.add(Msg.lore("&7Filled: &f" + o.filled + "/" + o.total));
            lore.add(Msg.lore("&7Waiting to collect: &f" + o.pending));
            lore.add(Component.empty());
            lore.add(Msg.lore("&aLeft-click: collect items"));
            lore.add(Msg.lore("&cRight-click: cancel & refund"));
        } else {
            lore.add(Msg.lore("&7Buyer: &f" + o.ownerName));
            lore.add(Msg.lore("&7Still wanted: &f" + o.remaining() + "/" + o.total));
            lore.add(Msg.lore("&7Total payout: &a" + MoneyUtil.format(o.remaining() * o.priceEach)));
            lore.add(Component.empty());
            lore.add(Msg.lore("&eClick to fill with items from your inventory"));
            lore.add(Msg.lore("&8Only plain items (no custom name/enchants)"));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public void openOrderConfirm(Player p, Material mat, int amount, double priceEach) {
        OrderConfirmHolder h = new OrderConfirmHolder();
        h.material = mat;
        h.amount = amount;
        h.priceEach = priceEach;
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Order"));
        h.inv = inv;
        ItemStack display = new ItemStack(mat);
        ItemMeta meta = display.getItemMeta();
        meta.displayName(Msg.lore("&f" + amount + "x " + ItemNames.pretty(mat)));
        meta.lore(List.of(
                Msg.lore("&7Price each: &a" + MoneyUtil.format(priceEach)),
                Msg.lore("&7Total held now: &e" + MoneyUtil.format(MoneyUtil.round(amount * priceEach))),
                Msg.lore("&7Refunded if you cancel before it's filled.")));
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aPlace order"));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ create-order flow (no commands)

    /** Step 1: pick any material. */
    public void openMaterialPicker(Player p, String query, int page) {
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        if (orders.countBy(p.getUniqueId()) >= maxOrders) {
            p.sendMessage(Msg.c("&cYou can only have " + maxOrders + " active orders."));
            return;
        }
        MaterialPickerHolder h = new MaterialPickerHolder();
        h.query = query;
        List<Material> matches = allItems.stream()
                .filter(m -> matchesAll(m.name().replace('_', ' '), query))
                .toList();
        int pages = Math.max(1, (matches.size() + PICKER_PAGE - 1) / PICKER_PAGE);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        h.page = pg;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text("Pick an Item" + (query != null && !query.isBlank() ? " - \"" + query + "\"" : "")
                        + " (" + (pg + 1) + "/" + pages + ")"));
        h.inv = inv;
        int from = pg * PICKER_PAGE;
        int to = Math.min(matches.size(), from + PICKER_PAGE);
        for (int i = from; i < to; i++) {
            Material m = matches.get(i);
            h.shown.add(m);
            inv.setItem(i - from, button(m, "&f" + ItemNames.pretty(m), "&eClick to request this item"));
        }
        if (pg > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(47, button(Material.OAK_SIGN, "&bSearch", "&eClick to search", "&eRight-click to clear"));
        inv.setItem(49, button(Material.CHEST, "&eBack to Orders"));
        if (pg < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    /** Step 2: amount, via anvil. */
    private void promptOrderAmount(Player p, Material mat) {
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        openAnvilInput(p, "Type an amount (max " + maxAmount + ")", "", "&aContinue", text -> {
            int amount;
            try {
                amount = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                p.sendMessage(Msg.c("&cThat's not a whole number. Try again."));
                later(() -> promptOrderAmount(p, mat));
                return;
            }
            if (amount < 1 || amount > maxAmount) {
                p.sendMessage(Msg.c("&cAmount must be between 1 and " + maxAmount + ". Try again."));
                later(() -> promptOrderAmount(p, mat));
                return;
            }
            later(() -> promptOrderPrice(p, mat, amount));
        });
    }

    /** Step 3: price each, via anvil, then hand off to the confirm screen (or skip it, per settings). */
    private void promptOrderPrice(Player p, Material mat, int amount) {
        openAnvilInput(p, "Type a price each (e.g. 5)", "", "&aReview order", text -> {
            OptionalDouble price = MoneyUtil.parse(text);
            if (price.isEmpty() || price.getAsDouble() < 0.01) {
                p.sendMessage(Msg.c("&cPrice must be at least $0.01. Try again."));
                later(() -> promptOrderPrice(p, mat, amount));
                return;
            }
            double cost = MoneyUtil.round(amount * price.getAsDouble());
            if (eco.get(p.getUniqueId()) + 0.001 < cost) {
                p.sendMessage(Msg.c("&cYou need " + MoneyUtil.format(cost) + " for that order."));
                return;
            }
            later(() -> maybeConfirmOrder(p, mat, amount, price.getAsDouble()));
        });
    }

    // ------------------------------------------------------------ leaderboard

    public void openLeaderboard(Player p) {
        Holder h = new Holder() {};
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Top Balances"));
        h.inv = inv;

        int[] slots = {11, 10, 12, 19, 20, 21, 22, 23, 24, 25};
        List<Map.Entry<UUID, Double>> top = eco.top(10);
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            UUID id = top.get(i).getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(op);
            meta.displayName(Msg.lore("&e#" + (i + 1) + " &f" + name));
            meta.lore(List.of(Msg.lore("&a" + MoneyUtil.format(top.get(i).getValue()))));
            head.setItemMeta(meta);
            inv.setItem(slots[i], head);
        }
        inv.setItem(4, button(Material.SUNFLOWER, "&aYour balance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Rank: &f#" + eco.rank(p.getUniqueId())));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (holder instanceof AnvilInputHolder h) {
            handleAnvilInput(p, h, e.getRawSlot());
            return;
        }
        if (e.getClickedInventory() != top) return;
        int slot = e.getRawSlot();

        if (holder instanceof AhHolder h) handleAh(p, h, slot);
        else if (holder instanceof BuyConfirmHolder h) handleBuyConfirm(p, h, slot);
        else if (holder instanceof SellConfirmHolder h) handleSellConfirm(p, h, slot);
        else if (holder instanceof OrdersHolder h) handleOrders(p, h, slot, e.isRightClick());
        else if (holder instanceof OrderConfirmHolder h) handleOrderConfirm(p, h, slot);
        else if (holder instanceof InvPickerHolder h) handleInvPicker(p, h, slot);
        else if (holder instanceof QuickSellHolder h) handleQuickSell(p, h, slot);
        else if (holder instanceof MaterialPickerHolder h) handleMaterialPicker(p, h, slot, e.isRightClick());
    }

    private void handleAnvilInput(Player p, AnvilInputHolder h, int slot) {
        if (slot != 2) return; // only the result slot does anything; typing itself isn't a click
        String text = ((AnvilInventory) h.inv).getRenameText();
        Consumer<String> onSubmit = h.onSubmit;
        p.closeInventory();
        later(() -> onSubmit.accept(text == null ? "" : text));
    }

    private void handleAh(Player p, AhHolder h, int slot) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            Listing l = h.shown.get(slot);
            if (h.mine) {
                if (auction.cancel(l)) {
                    Map<Integer, ItemStack> left = p.getInventory().addItem(l.item().clone());
                    left.values().forEach(x -> auction.addExpired(p.getUniqueId(), x));
                    p.sendMessage(Msg.c("&aListing cancelled."
                            + (left.isEmpty() ? "" : " &eInventory full - item moved to expired items.")));
                }
                later(() -> openAh(p, h.page, true));
            } else {
                later(() -> maybeConfirmBuy(p, l));
            }
            return;
        }
        switch (slot) {
            case 45 -> later(() -> openAh(p, h.page - 1, h.mine));
            case 53 -> later(() -> openAh(p, h.page + 1, h.mine));
            case 46 -> later(() -> openAh(p, 0, !h.mine));
            case 47 -> later(() -> signInput.open(p,
                    new String[]{"&bSearch Auction House", "&7Item name or seller", "&7Blank = show all"},
                    text -> openAhSearch(p, text)));
            case 48 -> {
                claimExpired(p);
                later(() -> openAh(p, h.page, h.mine));
            }
            case 49 -> later(() -> openAh(p, h.page, h.mine));
            case 51 -> later(() -> openSellPicker(p));
            case 52 -> later(() -> openQuickSell(p));
            default -> {}
        }
    }

    private void handleBuyConfirm(Player p, BuyConfirmHolder h, int slot) {
        if (slot == 11) {
            String err = auction.buy(p, h.listing);
            if (err != null) {
                p.sendMessage(Msg.c(err));
            } else {
                p.sendMessage(Msg.c("&aPurchased for &2" + MoneyUtil.format(h.listing.price()) + "&a."));
            }
            later(() -> openAh(p, 0, false));
        } else if (slot == 15) {
            later(() -> openAh(p, 0, false));
        }
    }

    private void handleSellConfirm(Player p, SellConfirmHolder h, int slot) {
        if (slot == 11) {
            finalizeSellListing(p, h.snapshot, h.price);
        } else if (slot == 15) {
            p.sendMessage(Msg.c("&7Listing cancelled."));
            later(() -> openAh(p, 0, false));
        }
    }

    /** Opens the sell confirm screen, unless the player has turned AH confirmations off. */
    public void maybeConfirmSell(Player p, ItemStack snapshot, double price) {
        if (!settings.confirmListing(p.getUniqueId())) {
            finalizeSellListing(p, snapshot, price);
            return;
        }
        openSellConfirm(p, snapshot, price);
    }

    /** Actually takes the item and creates the listing. Re-validates everything first. */
    private void finalizeSellListing(Player p, ItemStack snapshot, double price) {
        ItemStack hand = InvUtil.findMatching(p, snapshot);
        if (hand == null) {
            p.sendMessage(Msg.c("&cThat item is no longer in your inventory, so the listing was cancelled."));
            later(() -> openAh(p, 0, false));
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            later(() -> openAh(p, 0, false));
            return;
        }
        InvUtil.removeOne(p, hand, snapshot.getAmount());
        auction.create(p, snapshot, price);
        p.sendMessage(Msg.c("&aListed &f" + snapshot.getAmount() + "x " + ItemNames.pretty(snapshot.getType())
                + " &afor &2" + MoneyUtil.format(price) + "&a."));
        later(() -> openAh(p, 0, true));
    }

    private void handleOrders(Player p, OrdersHolder h, int slot, boolean right) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            Order o = h.shown.get(slot);
            String result;
            if (h.mine) result = right ? orders.cancel(p, o) : orders.collect(p, o);
            else result = orders.fill(p, o);
            p.sendMessage(Msg.c(result));
            later(() -> openOrders(p, h.page, h.mine));
            return;
        }
        switch (slot) {
            case 45 -> later(() -> openOrders(p, h.page - 1, h.mine));
            case 53 -> later(() -> openOrders(p, h.page + 1, h.mine));
            case 46 -> later(() -> openOrders(p, 0, !h.mine));
            case 48 -> later(() -> openMaterialPicker(p, "", 0));
            case 49 -> later(() -> openOrders(p, h.page, h.mine));
            default -> {}
        }
    }

    private void handleOrderConfirm(Player p, OrderConfirmHolder h, int slot) {
        if (slot == 11) {
            finalizeOrder(p, h.material, h.amount, h.priceEach);
        } else if (slot == 15) {
            p.sendMessage(Msg.c("&7Order cancelled."));
            later(() -> openOrders(p, 0, false));
        }
    }

    /** Opens the order confirm screen, unless the player has turned order confirmations off. */
    public void maybeConfirmOrder(Player p, Material mat, int amount, double priceEach) {
        if (!settings.confirmOrders(p.getUniqueId())) {
            finalizeOrder(p, mat, amount, priceEach);
            return;
        }
        openOrderConfirm(p, mat, amount, priceEach);
    }

    private void finalizeOrder(Player p, Material mat, int amount, double priceEach) {
        String err = orders.create(p, mat, amount, priceEach);
        if (err != null) {
            p.sendMessage(Msg.c(err));
            later(() -> openOrders(p, 0, false));
            return;
        }
        p.sendMessage(Msg.c("&aOrder placed for &f" + amount + "x " + ItemNames.pretty(mat)
                + " &aat &2" + MoneyUtil.format(priceEach) + " &aeach."));
        later(() -> openOrders(p, 0, true));
    }

    private void handleInvPicker(Player p, InvPickerHolder h, int slot) {
        if (slot >= h.slotsShown.size()) {
            if (slot == 49) later(() -> openAh(p, 0, false));
            return;
        }
        int realSlot = h.slotsShown.get(slot);
        ItemStack it = p.getInventory().getItem(realSlot);
        if (it == null || it.getType().isAir()) {
            p.sendMessage(Msg.c("&cThat item is gone now."));
            later(() -> openSellPicker(p));
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            later(() -> openAh(p, 0, false));
            return;
        }
        ItemStack snapshot = it.clone();
        later(() -> promptSellPrice(p, snapshot));
    }

    private void handleQuickSell(Player p, QuickSellHolder h, int slot) {
        if (slot == 45) {
            later(() -> openAh(p, 0, false));
            return;
        }
        if (slot == 49) {
            double total = 0;
            for (int realSlot : h.slotsShown) {
                ItemStack it = p.getInventory().getItem(realSlot);
                if (!InvUtil.isPlain(it)) continue;
                OptionalDouble each = prices.sellPrice(it.getType());
                if (each.isEmpty()) continue;
                total += each.getAsDouble() * it.getAmount();
                p.getInventory().setItem(realSlot, null);
            }
            total = MoneyUtil.round(total);
            if (total > 0) {
                eco.deposit(p.getUniqueId(), total);
                p.sendMessage(Msg.c("&aSold everything for &2" + MoneyUtil.format(total) + "&a."));
            }
            later(() -> openQuickSell(p));
            return;
        }
        if (slot >= h.slotsShown.size()) return;
        int realSlot = h.slotsShown.get(slot);
        ItemStack it = p.getInventory().getItem(realSlot);
        if (!InvUtil.isPlain(it)) {
            p.sendMessage(Msg.c("&cThat item is gone now."));
            later(() -> openQuickSell(p));
            return;
        }
        OptionalDouble each = prices.sellPrice(it.getType());
        if (each.isEmpty()) return;
        double total = MoneyUtil.round(each.getAsDouble() * it.getAmount());
        String name = ItemNames.pretty(it.getType());
        int amount = it.getAmount();
        p.getInventory().setItem(realSlot, null);
        eco.deposit(p.getUniqueId(), total);
        p.sendMessage(Msg.c("&aSold &f" + amount + "x " + name + " &afor &2" + MoneyUtil.format(total) + "&a."));
        later(() -> openQuickSell(p));
    }

    private void handleMaterialPicker(Player p, MaterialPickerHolder h, int slot, boolean right) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            Material mat = h.shown.get(slot);
            later(() -> promptOrderAmount(p, mat));
            return;
        }
        switch (slot) {
            case 45 -> later(() -> openMaterialPicker(p, h.query, h.page - 1));
            case 47 -> {
                if (right) later(() -> openMaterialPicker(p, "", 0));
                else later(() -> signInput.open(p,
                        new String[]{"&bSearch Items", "&7Item name to request", "&7Blank = show all"},
                        text -> openMaterialPicker(p, text, 0)));
            }
            case 49 -> later(() -> openOrders(p, 0, false));
            case 53 -> later(() -> openMaterialPicker(p, h.query, h.page + 1));
            default -> {}
        }
    }
}
