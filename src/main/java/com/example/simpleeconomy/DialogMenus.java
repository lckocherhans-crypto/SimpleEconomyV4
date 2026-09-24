package com.example.simpleeconomy;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * The Java-player menu system: everything here is a Paper dialog. Bedrock players use the plain
 * chest/anvil menus in Gui.java instead, since dialogs don't render reliably for them through
 * Geyser. Which one a given player sees is decided by PlayerSettings.useBedrockUi(player).
 */
public class DialogMenus {
    private static final int PAGE_SIZE = 18;

    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final AuctionManager auction;
    private final OrderManager orders;
    private final PriceManager prices;
    private final PlayerSettings settings;
    private final Dialogs dialogs;
    private final List<Material> allItems;

    public DialogMenus(SimpleEconomyPlugin plugin, EconomyManager eco, AuctionManager auction, OrderManager orders,
                        PriceManager prices, PlayerSettings settings, Dialogs dialogs) {
        this.plugin = plugin;
        this.eco = eco;
        this.auction = auction;
        this.orders = orders;
        this.prices = prices;
        this.settings = settings;
        this.dialogs = dialogs;

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

    // ------------------------------------------------------------ small helpers

    private void notice(Player p, String title, String... lines) {
        dialogs.notice(p, title, Arrays.stream(lines).map(Msg::c).toList());
    }

    private static boolean matchesAll(String haystack, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = haystack.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    private static Component itemLabel(ItemStack it) {
        return Component.text(it.getAmount() + "x ").append(Component.translatable(it.getType().translationKey()));
    }

    // ------------------------------------------------------------ balance hub

    public void balance(Player p) {
        UUID id = p.getUniqueId();
        List<Component> lines = List.of(
                Msg.c("&7Balance: &a" + MoneyUtil.format(eco.get(id))),
                Msg.c("&7Auction listings: &f" + auction.countBy(id)),
                Msg.c("&7Active orders: &f" + orders.countBy(id)));
        List<ActionButton> actions = List.of(
                dialogs.button(Component.text("Leaderboard"), null, 140, dialogs.click(this::baltop)),
                dialogs.button(Component.text("Pay a player"), null, 140, dialogs.click(pl -> pay(pl, "", "", null))),
                dialogs.button(Component.text("Auction House"), null, 140, dialogs.click(pl -> openAh(pl, null, 0, false))),
                dialogs.button(Component.text("Orders"), null, 140, dialogs.click(pl -> openOrders(pl, null, 0, false))),
                dialogs.button(Component.text("Quick Sell"), null, 140, dialogs.click(this::openQuickSell)),
                dialogs.button(Component.text("Settings"), null, 140, dialogs.click(this::openSettings)));
        dialogs.menu(p, "Your Balance", lines, List.of(), actions,
                dialogs.button("Close", NamedTextColor.RED, null), 2);
    }

    public void balanceOf(Player viewer, OfflinePlayer target) {
        notice(viewer, "Balance", "&7" + target.getName() + "'s balance: &a" + MoneyUtil.format(eco.get(target.getUniqueId())));
    }

    public void baltop(Player p) {
        List<Component> lines = new ArrayList<>();
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco.top(10)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
            lines.add(Msg.c("&e" + i++ + ". &f" + name + " &7- &a" + MoneyUtil.format(e.getValue())));
        }
        if (lines.isEmpty()) lines.add(Msg.c("&7Nobody has any money yet."));
        lines.add(Component.empty());
        lines.add(Msg.c("&7You: &a" + MoneyUtil.format(eco.get(p.getUniqueId())) + " &7(rank #" + eco.rank(p.getUniqueId()) + ")"));
        dialogs.notice(p, "Top Balances", lines);
    }

    public void pay(Player p, String name, String amount, String error) {
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(Msg.c("&7Your balance: &a" + MoneyUtil.format(eco.get(p.getUniqueId()))));
        lines.add(Msg.c("&7Amounts accept 1k, 2.5m, 1b..."));
        List<DialogInput> inputs = List.of(
                dialogs.textInput("player", "Player (online)", name, 16),
                dialogs.textInput("amount", "Amount", amount, 16));
        ActionButton yes = dialogs.button("Send", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("player", "amount"), (pl, m) -> {
                    String err = tryPay(pl, m.get("player"), m.get("amount"));
                    if (err != null) pay(pl, m.get("player"), m.get("amount"), err);
                }));
        dialogs.confirm(p, "Pay a Player", lines, inputs, yes, dialogs.button("Cancel", NamedTextColor.RED, null));
    }

    public String tryPay(Player p, String targetName, String amountText) {
        Player t = Bukkit.getPlayerExact(targetName == null ? "" : targetName.trim());
        if (t == null) return "&cThat player is not online.";
        if (t.equals(p)) return "&cYou can't pay yourself.";
        OptionalDouble amt = MoneyUtil.parse(amountText);
        if (amt.isEmpty() || amt.getAsDouble() < 0.01) return "&cInvalid amount.";
        if (!eco.withdraw(p.getUniqueId(), amt.getAsDouble())) return "&cYou can't afford that.";
        eco.deposit(t.getUniqueId(), amt.getAsDouble());
        p.sendMessage(Msg.c("&aYou paid &f" + t.getName() + " &2" + MoneyUtil.format(amt.getAsDouble()) + "&a."));
        t.sendMessage(Msg.c("&aYou received &2" + MoneyUtil.format(amt.getAsDouble()) + " &afrom &f" + p.getName() + "&a."));
        return null;
    }

    // ------------------------------------------------------------ settings

    public void openSettings(Player p) {
        UUID id = p.getUniqueId();
        List<Component> lines = List.of(Msg.c("&7Turn confirmation screens on or off, per action."));
        List<ActionButton> actions = List.of(
                dialogs.button(Component.text("AH buy confirm: " + (settings.confirmAh(id) ? "ON" : "OFF")), null, 200,
                        dialogs.click(pl -> { settings.setConfirmAh(id, !settings.confirmAh(id)); openSettings(pl); })),
                dialogs.button(Component.text("AH listing confirm: " + (settings.confirmListing(id) ? "ON" : "OFF")), null, 200,
                        dialogs.click(pl -> { settings.setConfirmListing(id, !settings.confirmListing(id)); openSettings(pl); })),
                dialogs.button(Component.text("Order confirm: " + (settings.confirmOrders(id) ? "ON" : "OFF")), null, 200,
                        dialogs.click(pl -> { settings.setConfirmOrders(id, !settings.confirmOrders(id)); openSettings(pl); })),
                dialogs.button(Component.text("Menu style: " + settings.ui(id)), null, 200,
                        dialogs.click(pl -> {
                            PlayerSettings.UiMode next = switch (settings.ui(id)) {
                                case AUTO -> PlayerSettings.UiMode.JAVA;
                                case JAVA -> PlayerSettings.UiMode.BEDROCK;
                                case BEDROCK -> PlayerSettings.UiMode.AUTO;
                            };
                            settings.setUi(id, next);
                            pl.sendMessage(Msg.c("&7Menu style set to &f" + next + "&7. This takes effect the next time you open a menu."));
                            openSettings(pl);
                        })));
        dialogs.menu(p, "Settings", lines, List.of(), actions, dialogs.button("Back", NamedTextColor.RED, dialogs.click(this::balance)), 1);
    }

    // ------------------------------------------------------------ auction house

    public void openAh(Player p, String query, int page, boolean mine) {
        List<Listing> list = auction.all().stream()
                .filter(l -> !mine || l.seller().equals(p.getUniqueId()))
                .filter(l -> matchesListing(l, query))
                .sorted(Comparator.comparingLong(Listing::expiresAt).reversed())
                .toList();
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        int from = pg * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);

        List<ActionButton> actions = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Listing l = list.get(i);
            Component label = itemLabel(l.item()).append(Component.text(" - " + MoneyUtil.format(l.price())));
            actions.add(dialogs.button(label, null, 220, dialogs.click(pl -> {
                if (mine) cancelListing(pl, l); else maybeConfirmBuy(pl, l);
            })));
        }
        final String q = query;
        actions.add(dialogs.button(Component.text("< Prev"), null, 100, dialogs.click(pl -> openAh(pl, q, pg - 1, mine))));
        actions.add(dialogs.button(Component.text("Next >"), null, 100, dialogs.click(pl -> openAh(pl, q, pg + 1, mine))));
        actions.add(dialogs.button(Component.text(mine ? "Back to Auction House" : "Your listings"), null, 200,
                dialogs.click(pl -> openAh(pl, null, 0, !mine))));
        actions.add(dialogs.button(Component.text("Search"), null, 140, dialogs.click(pl ->
                dialogs.search(pl, "Search Auction House", q, (p2, text) -> openAh(p2, text, 0, mine)))));
        int expiredCount = auction.expiredCount(p.getUniqueId());
        actions.add(dialogs.button(Component.text("Claim expired (" + expiredCount + ")"), null, 200,
                dialogs.click(this::claimExpired)));
        actions.add(dialogs.button(Component.text("Sell an item"), null, 160, dialogs.click(this::openSellPicker)));
        actions.add(dialogs.button(Component.text("Quick Sell"), null, 140, dialogs.click(this::openQuickSell)));

        List<Component> lines = List.of(Msg.c("&7" + (mine ? "Your listings" : "Browsing the auction house")
                + (q != null && !q.isBlank() ? " - \"" + q + "\"" : "") + " - page " + (pg + 1) + "/" + pages));
        dialogs.menu(p, mine ? "Your Listings" : "Auction House", lines, List.of(), actions,
                dialogs.button("Close", NamedTextColor.RED, null), 1);
    }

    private boolean matchesListing(Listing l, String query) {
        return matchesAll(l.item().getType().name().replace('_', ' ') + " " + l.sellerName(), query);
    }

    private void cancelListing(Player p, Listing l) {
        if (auction.cancel(l)) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(l.item().clone());
            left.values().forEach(x -> auction.addExpired(p.getUniqueId(), x));
            p.sendMessage(Msg.c("&aListing cancelled." + (left.isEmpty() ? "" : " &eInventory full - item moved to expired items.")));
        }
        openAh(p, null, 0, true);
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

    private void buyConfirm(Player p, Listing l) {
        if (!auction.all().contains(l)) {
            notice(p, "Auction House", "&cThat listing is no longer available.");
            return;
        }
        List<Component> lines = List.of(
                itemLabel(l.item()),
                Msg.c("&7Price: &a" + MoneyUtil.format(l.price())),
                Msg.c("&7Seller: &f" + l.sellerName()));
        ActionButton yes = dialogs.button("Buy", NamedTextColor.GREEN, dialogs.click(pl -> {
            String err = auction.buy(pl, l);
            pl.sendMessage(Msg.c(err != null ? err : "&aPurchased for &2" + MoneyUtil.format(l.price()) + "&a."));
            openAh(pl, null, 0, false);
        }));
        ActionButton no = dialogs.button("Cancel", NamedTextColor.RED, dialogs.click(pl -> openAh(pl, null, 0, false)));
        dialogs.confirm(p, "Confirm Purchase", lines, List.of(), yes, no);
    }

    private void maybeConfirmBuy(Player p, Listing l) {
        if (!settings.confirmAh(p.getUniqueId())) {
            String err = auction.buy(p, l);
            p.sendMessage(Msg.c(err != null ? err : "&aPurchased for &2" + MoneyUtil.format(l.price()) + "&a."));
            openAh(p, null, 0, false);
            return;
        }
        buyConfirm(p, l);
    }

    // ------------------------------------------------------------ sell-an-item flow

    public void openSellPicker(Player p) {
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            notice(p, "Auction House", "&cYou can only have " + max + " active listings.");
            return;
        }
        List<ActionButton> actions = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            ItemStack snap = it.clone();
            actions.add(dialogs.button(itemLabel(it), null, 180, dialogs.click(pl -> promptSellPrice(pl, snap, "", null))));
        }
        if (actions.isEmpty()) {
            notice(p, "Auction House", "&cYour inventory is empty. Nothing to list.");
            return;
        }
        dialogs.menu(p, "Pick an Item to Sell", List.of(Msg.c("&7Pick the whole stack you want to list.")),
                List.of(), actions, dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> openAh(pl, null, 0, false))), 2);
    }

    private void promptSellPrice(Player p, ItemStack snapshot, String prefill, String error) {
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(itemLabel(snapshot));
        double min = plugin.getConfig().getDouble("auction.min-price", 1);
        lines.add(Msg.c("&7Set the total price (minimum " + MoneyUtil.format(min) + ")."));
        ActionButton yes = dialogs.button("Continue", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("price"), (pl, m) -> {
                    OptionalDouble price = MoneyUtil.parse(m.get("price"));
                    if (price.isEmpty() || price.getAsDouble() < min) {
                        promptSellPrice(pl, snapshot, m.get("price"), "&cInvalid price (minimum " + MoneyUtil.format(min) + ").");
                        return;
                    }
                    maybeConfirmSell(pl, snapshot, price.getAsDouble());
                }));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(this::openSellPicker));
        dialogs.confirm(p, "Sell Item", lines, List.of(dialogs.textInput("price", "Price", prefill, 16)), yes, no);
    }

    public void maybeConfirmSell(Player p, ItemStack snapshot, double price) {
        if (!settings.confirmListing(p.getUniqueId())) {
            finalizeSellListing(p, snapshot, price);
            return;
        }
        double tax = plugin.getConfig().getDouble("auction.tax-percent", 0);
        List<Component> lines = new ArrayList<>();
        lines.add(itemLabel(snapshot));
        lines.add(Msg.c("&7Price: &a" + MoneyUtil.format(price)));
        if (tax > 0) lines.add(Msg.c("&7You receive after tax: &e" + MoneyUtil.format(price - price * tax / 100.0)));
        lines.add(Msg.c("&7Lasts &f" + plugin.getConfig().getLong("auction.duration-hours", 48) + " hours&7."));
        ActionButton yes = dialogs.button("List it", NamedTextColor.GREEN, dialogs.click(pl -> finalizeSellListing(pl, snapshot, price)));
        ActionButton no = dialogs.button("Cancel", NamedTextColor.RED, dialogs.click(pl -> openAh(pl, null, 0, false)));
        dialogs.confirm(p, "Confirm Listing", lines, List.of(), yes, no);
    }

    private void finalizeSellListing(Player p, ItemStack snapshot, double price) {
        ItemStack hand = InvUtil.findMatching(p, snapshot);
        if (hand == null) {
            p.sendMessage(Msg.c("&cThat item is no longer in your inventory, so the listing was cancelled."));
            openAh(p, null, 0, false);
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            openAh(p, null, 0, false);
            return;
        }
        InvUtil.removeOne(p, hand, snapshot.getAmount());
        auction.create(p, snapshot, price);
        p.sendMessage(Msg.c("&aListed &f" + snapshot.getAmount() + "x " + ItemNames.pretty(snapshot.getType())
                + " &afor &2" + MoneyUtil.format(price) + "&a."));
        openAh(p, null, 0, true);
    }

    // ------------------------------------------------------------ orders

    public void openOrders(Player p, String query, int page, boolean mine) {
        List<Order> list = orders.all().stream()
                .filter(o -> mine ? o.owner.equals(p.getUniqueId()) : o.remaining() > 0)
                .filter(o -> matchesOrder(o, query))
                .sorted(Comparator.<Order>comparingDouble(o -> o.priceEach).reversed())
                .toList();
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        int from = pg * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);

        List<ActionButton> actions = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Order o = list.get(i);
            Component label = Component.translatable(o.material.translationKey())
                    .append(Component.text(" - " + MoneyUtil.format(o.priceEach) + " each"
                            + (mine ? " (" + o.filled + "/" + o.total + ", " + o.pending + " to collect)" : "")));
            actions.add(dialogs.button(label, null, 260, dialogs.click(pl -> {
                if (mine) openOrderManage(pl, o); else fillOrder(pl, o);
            })));
        }
        final String q = query;
        actions.add(dialogs.button(Component.text("< Prev"), null, 100, dialogs.click(pl -> openOrders(pl, q, pg - 1, mine))));
        actions.add(dialogs.button(Component.text("Next >"), null, 100, dialogs.click(pl -> openOrders(pl, q, pg + 1, mine))));
        actions.add(dialogs.button(Component.text(mine ? "Back to all orders" : "Your orders"), null, 200,
                dialogs.click(pl -> openOrders(pl, null, 0, !mine))));
        actions.add(dialogs.button(Component.text("Search"), null, 140, dialogs.click(pl ->
                dialogs.search(pl, "Search Orders", q, (p2, text) -> openOrders(p2, text, 0, mine)))));
        actions.add(dialogs.button(Component.text("Create an order"), null, 200, dialogs.click(pl -> openMaterialPicker(pl, "", 0))));

        List<Component> lines = List.of(Msg.c("&7" + (mine ? "Your orders" : "Browsing buy orders")
                + (q != null && !q.isBlank() ? " - \"" + q + "\"" : "") + " - page " + (pg + 1) + "/" + pages));
        dialogs.menu(p, mine ? "Your Orders" : "Orders", lines, List.of(), actions,
                dialogs.button("Close", NamedTextColor.RED, null), 1);
    }

    private boolean matchesOrder(Order o, String query) {
        return matchesAll(o.material.name().replace('_', ' ') + " " + o.ownerName, query);
    }

    private void fillOrder(Player p, Order o) {
        p.sendMessage(Msg.c(orders.fill(p, o)));
        openOrders(p, null, 0, false);
    }

    private void openOrderManage(Player p, Order o) {
        List<Component> lines = List.of(
                Component.translatable(o.material.translationKey()),
                Msg.c("&7Price each: &a" + MoneyUtil.format(o.priceEach)),
                Msg.c("&7Filled: &f" + o.filled + "/" + o.total),
                Msg.c("&7Waiting to collect: &f" + o.pending));
        List<ActionButton> actions = List.of(
                dialogs.button(Component.text("Collect items", NamedTextColor.GREEN), null, 180, dialogs.click(pl -> {
                    pl.sendMessage(Msg.c(orders.collect(pl, o)));
                    openOrders(pl, null, 0, true);
                })),
                dialogs.button(Component.text("Cancel & refund", NamedTextColor.RED), null, 180, dialogs.click(pl -> {
                    pl.sendMessage(Msg.c(orders.cancel(pl, o)));
                    openOrders(pl, null, 0, true);
                })));
        dialogs.menu(p, "Manage Order", lines, List.of(), actions,
                dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> openOrders(pl, null, 0, true))), 2);
    }

    // ------------------------------------------------------------ create-order flow

    public void openMaterialPicker(Player p, String query, int page) {
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        if (orders.countBy(p.getUniqueId()) >= maxOrders) {
            notice(p, "Orders", "&cYou can only have " + maxOrders + " active orders.");
            return;
        }
        String q = query == null ? "" : query.trim();
        List<Material> matches = allItems.stream().filter(m -> matchesAll(m.name().replace('_', ' '), q)).toList();
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        int from = pg * PAGE_SIZE;
        int to = Math.min(matches.size(), from + PAGE_SIZE);

        List<ActionButton> actions = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Material m = matches.get(i);
            actions.add(dialogs.button(Component.translatable(m.translationKey()), null, 160,
                    dialogs.click(pl -> promptOrderDetails(pl, m, "", "", null))));
        }
        actions.add(dialogs.button(Component.text("< Prev"), null, 100, dialogs.click(pl -> openMaterialPicker(pl, q, pg - 1))));
        actions.add(dialogs.button(Component.text("Next >"), null, 100, dialogs.click(pl -> openMaterialPicker(pl, q, pg + 1))));
        actions.add(dialogs.button(Component.text("Search"), null, 140, dialogs.click(pl ->
                dialogs.search(pl, "Search Items", q, (p2, text) -> openMaterialPicker(p2, text, 0)))));
        actions.add(dialogs.button(Component.text("Back"), null, 120, dialogs.click(pl -> openOrders(pl, null, 0, false))));

        List<Component> lines = List.of(Msg.c("&7" + matches.size() + " items - page " + (pg + 1) + "/" + pages));
        dialogs.menu(p, "Pick an Item", lines, List.of(), actions, dialogs.button("Close", NamedTextColor.RED, null), 2);
    }

    private void promptOrderDetails(Player p, Material mat, String amount, String price, String error) {
        List<Component> lines = new ArrayList<>();
        if (error != null) lines.add(Msg.c(error));
        lines.add(Component.translatable(mat.translationKey()));
        lines.add(Msg.c("&7How many do you want, and how much will you pay for each one?"));
        List<DialogInput> inputs = List.of(
                dialogs.textInput("amount", "Amount", amount, 8),
                dialogs.textInput("price", "Price each", price, 16));
        ActionButton yes = dialogs.button("Review", NamedTextColor.GREEN,
                dialogs.clickInputs(List.of("amount", "price"), (pl, m) -> reviewOrder(pl, mat, m.get("amount"), m.get("price"))));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> openMaterialPicker(pl, "", 0)));
        dialogs.confirm(p, "Create Order", lines, inputs, yes, no);
    }

    private void reviewOrder(Player p, Material mat, String amountText, String priceText) {
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        int amount;
        try {
            amount = Integer.parseInt(amountText.trim());
        } catch (NumberFormatException e) {
            promptOrderDetails(p, mat, amountText, priceText, "&cAmount must be a whole number.");
            return;
        }
        if (amount < 1 || amount > maxAmount) {
            promptOrderDetails(p, mat, amountText, priceText, "&cAmount must be between 1 and " + maxAmount + ".");
            return;
        }
        OptionalDouble price = MoneyUtil.parse(priceText);
        if (price.isEmpty() || price.getAsDouble() < 0.01) {
            promptOrderDetails(p, mat, amountText, priceText, "&cPrice each must be at least $0.01.");
            return;
        }
        double each = price.getAsDouble();
        double total = MoneyUtil.round(amount * each);
        if (eco.get(p.getUniqueId()) + 0.001 < total) {
            promptOrderDetails(p, mat, amountText, priceText, "&cYou need " + MoneyUtil.format(total) + " for that order.");
            return;
        }
        maybeConfirmOrder(p, mat, amount, each, amountText, priceText);
    }

    private void maybeConfirmOrder(Player p, Material mat, int amount, double each, String amountText, String priceText) {
        if (!settings.confirmOrders(p.getUniqueId())) {
            finalizeOrder(p, mat, amount, each);
            return;
        }
        List<Component> lines = List.of(
                Component.text(amount + "x ").append(Component.translatable(mat.translationKey())),
                Msg.c("&7Price each: &a" + MoneyUtil.format(each)),
                Msg.c("&7Total held now: &e" + MoneyUtil.format(MoneyUtil.round(amount * each))),
                Msg.c("&7Refunded if you cancel before it's filled."));
        ActionButton yes = dialogs.button("Place order", NamedTextColor.GREEN, dialogs.click(pl -> finalizeOrder(pl, mat, amount, each)));
        ActionButton no = dialogs.button("Back", NamedTextColor.RED, dialogs.click(pl -> promptOrderDetails(pl, mat, amountText, priceText, null)));
        dialogs.confirm(p, "Confirm Order", lines, List.of(), yes, no);
    }

    public void finalizeOrder(Player p, Material mat, int amount, double each) {
        String err = orders.create(p, mat, amount, each);
        if (err != null) {
            p.sendMessage(Msg.c(err));
            openOrders(p, null, 0, false);
            return;
        }
        p.sendMessage(Msg.c("&aOrder placed for &f" + amount + "x " + ItemNames.pretty(mat) + " &aat &2"
                + MoneyUtil.format(each) + " &aeach."));
        openOrders(p, null, 0, true);
    }

    /** Used by /orders create <item> <amount> <price> when it's already fully specified. */
    public void reviewOrderFromCommand(Player p, Material mat, String amountText, String priceText) {
        reviewOrder(p, mat, amountText, priceText);
    }

    // ------------------------------------------------------------ quick sell (fixed base prices)

    public void openQuickSell(Player p) {
        List<ActionButton> actions = new ArrayList<>();
        double total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (!InvUtil.isPlain(it)) continue;
            OptionalDouble each = prices.sellPrice(it.getType());
            if (each.isEmpty()) continue;
            double value = MoneyUtil.round(each.getAsDouble() * it.getAmount());
            total += value;
            Component label = itemLabel(it).append(Component.text(" - " + MoneyUtil.format(value)));
            actions.add(dialogs.button(label, null, 220, dialogs.click(pl -> sellOneStack(pl, it.getType(), it.getAmount()))));
        }
        actions.add(dialogs.button(Component.text("Sell everything shown here", NamedTextColor.GREEN), null, 220,
                dialogs.click(this::sellAll)));
        List<Component> lines = List.of(Msg.c("&7Total value shown: &a" + MoneyUtil.format(MoneyUtil.round(total))));
        dialogs.menu(p, "Quick Sell", lines, List.of(), actions, dialogs.button("Back", NamedTextColor.RED, dialogs.click(this::balance)), 1);
    }

    private void sellOneStack(Player p, Material type, int expectedAmount) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (!InvUtil.isPlain(it) || it.getType() != type) continue;
            OptionalDouble each = prices.sellPrice(type);
            if (each.isEmpty()) continue;
            double total = MoneyUtil.round(each.getAsDouble() * it.getAmount());
            int amount = it.getAmount();
            p.getInventory().setItem(slot, null);
            eco.deposit(p.getUniqueId(), total);
            p.sendMessage(Msg.c("&aSold &f" + amount + "x " + ItemNames.pretty(type) + " &afor &2" + MoneyUtil.format(total) + "&a."));
            break;
        }
        openQuickSell(p);
    }

    public void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!InvUtil.isPlain(hand)) { p.sendMessage(Msg.c("&cYou aren't holding a plain, sellable item.")); return; }
        OptionalDouble each = prices.sellPrice(hand.getType());
        if (each.isEmpty()) { p.sendMessage(Msg.c("&cThat item can't be sold here.")); return; }
        double total = MoneyUtil.round(each.getAsDouble() * hand.getAmount());
        String name = ItemNames.pretty(hand.getType());
        int amount = hand.getAmount();
        p.getInventory().setItemInMainHand(null);
        eco.deposit(p.getUniqueId(), total);
        p.sendMessage(Msg.c("&aSold &f" + amount + "x " + name + " &afor &2" + MoneyUtil.format(total) + "&a."));
    }

    public void sellAll(Player p) {
        double total = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!InvUtil.isPlain(it)) continue;
            OptionalDouble each = prices.sellPrice(it.getType());
            if (each.isEmpty()) continue;
            total += each.getAsDouble() * it.getAmount();
            contents[i] = null;
        }
        if (total <= 0) { p.sendMessage(Msg.c("&cNothing sellable in your inventory.")); return; }
        p.getInventory().setStorageContents(contents);
        total = MoneyUtil.round(total);
        eco.deposit(p.getUniqueId(), total);
        p.sendMessage(Msg.c("&aSold everything sellable for &2" + MoneyUtil.format(total) + "&a."));
    }
}
