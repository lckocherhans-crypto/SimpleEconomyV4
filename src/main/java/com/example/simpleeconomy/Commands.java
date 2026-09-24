package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Every command that opens a menu checks PlayerSettings.useBedrockUi(player) and routes to either
 * Gui (chest/anvil, for Bedrock) or DialogMenus (Paper dialogs, for Java). Commands that just do
 * something directly (/pay with both args, /sell hand, /sell all, /eco ...) don't need routing,
 * since they have no menu to show.
 */
public class Commands implements CommandExecutor, TabCompleter {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final AuctionManager auction;
    private final OrderManager orders;
    private final PriceManager prices;
    private final PlayerSettings settings;
    private final Gui gui;
    private final DialogMenus dialogs;

    public Commands(SimpleEconomyPlugin plugin, EconomyManager eco, AuctionManager auction, OrderManager orders,
                     PriceManager prices, PlayerSettings settings, Gui gui, DialogMenus dialogs) {
        this.plugin = plugin;
        this.eco = eco;
        this.auction = auction;
        this.orders = orders;
        this.prices = prices;
        this.settings = settings;
        this.gui = gui;
        this.dialogs = dialogs;
    }

    private boolean bedrock(Player p) {
        return settings.useBedrockUi(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> ecoAdmin(sender, args);
            case "ah" -> ah(sender, args);
            case "sell" -> sellCmd(sender, args);
            case "orders" -> ordersCmd(sender, args);
            case "esettings" -> settingsCmd(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private boolean playersOnly(CommandSender s) {
        s.sendMessage(Msg.c("&cOnly players can use this."));
        return true;
    }

    // ---------------------------------------------------------- economy

    private void balance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { playersOnly(sender); return; }
            if (bedrock(p)) {
                p.sendMessage(Msg.c("&7Balance: &a" + MoneyUtil.format(eco.get(p.getUniqueId()))));
            } else {
                dialogs.balance(p);
            }
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
        if (sender instanceof Player p && !bedrock(p)) {
            dialogs.balanceOf(p, t);
        } else {
            sender.sendMessage(Msg.c("&7" + t.getName() + "'s balance: &a" + MoneyUtil.format(eco.get(t.getUniqueId()))));
        }
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length < 2) {
            if (bedrock(p)) {
                p.sendMessage(Msg.c("&cUsage: /pay <player> <amount>"));
            } else {
                dialogs.pay(p, args.length > 0 ? args[0] : "", "", null);
            }
            return;
        }
        String err = dialogs.tryPay(p, args[0], args[1]);
        if (err != null) p.sendMessage(Msg.c(err));
    }

    private void baltop(CommandSender sender) {
        if (sender instanceof Player p) {
            if (bedrock(p)) gui.openLeaderboard(p);
            else dialogs.baltop(p);
            return;
        }
        sender.sendMessage(Msg.c("&6&lTop Balances"));
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco.top(10)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
            sender.sendMessage(Msg.c("&e" + i++ + ". &f" + name + " &7- &a" + MoneyUtil.format(e.getValue())));
        }
    }

    private void ecoAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleeconomy.admin")) { sender.sendMessage(Msg.c("&cNo permission.")); return; }
        if (args.length == 0) {
            sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set> <player> <amount>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellmultiplier <value>"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "take", "set" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco " + sub + " <player> <amount>")); return; }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
                OptionalDouble amt = MoneyUtil.parse(args[2]);
                if (amt.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid amount.")); return; }
                UUID id = t.getUniqueId();
                switch (sub) {
                    case "give" -> eco.deposit(id, amt.getAsDouble());
                    case "take" -> eco.set(id, eco.get(id) - amt.getAsDouble());
                    case "set" -> eco.set(id, amt.getAsDouble());
                }
                sender.sendMessage(Msg.c("&a" + t.getName() + " now has &2" + MoneyUtil.format(eco.get(id)) + "&a."));
            }
            case "sellprice" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>")); return; }
                Material mat = Material.matchMaterial(args[1]);
                if (mat == null || mat.isAir() || !mat.isItem()) { sender.sendMessage(Msg.c("&cUnknown item.")); return; }
                if (args[2].equalsIgnoreCase("remove")) {
                    prices.removeBase(mat);
                    sender.sendMessage(Msg.c("&a" + ItemNames.pretty(mat) + " is no longer sellable via /sell."));
                    return;
                }
                OptionalDouble price = MoneyUtil.parse(args[2]);
                if (price.isEmpty() || price.getAsDouble() < 0) { sender.sendMessage(Msg.c("&cInvalid price.")); return; }
                prices.setBase(mat, price.getAsDouble());
                sender.sendMessage(Msg.c("&aBase sell price for " + ItemNames.pretty(mat) + " set to &2"
                        + MoneyUtil.format(price.getAsDouble()) + " &aeach."));
            }
            case "sellmultiplier" -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.c("&7Current multiplier: &f" + prices.multiplier()));
                    return;
                }
                OptionalDouble v = MoneyUtil.parse(args[1]);
                if (v.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid value.")); return; }
                prices.setMultiplier(v.getAsDouble());
                sender.sendMessage(Msg.c("&aSell multiplier set to &f" + prices.multiplier() + "&a."));
            }
            default -> sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set|sellprice|sellmultiplier> ..."));
        }
    }

    // ---------------------------------------------------------- settings

    private void settingsCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        UUID id = p.getUniqueId();
        if (args.length == 0) {
            if (bedrock(p)) {
                p.sendMessage(Msg.c("&7AH buy confirm: &f" + onOff(settings.confirmAh(id))));
                p.sendMessage(Msg.c("&7AH listing confirm: &f" + onOff(settings.confirmListing(id))));
                p.sendMessage(Msg.c("&7Order confirm: &f" + onOff(settings.confirmOrders(id))));
                p.sendMessage(Msg.c("&7Menu style: &f" + settings.ui(id)));
                p.sendMessage(Msg.c("&7Usage: /esettings <confirmah|confirmlisting|confirmorders> <on|off>"));
                p.sendMessage(Msg.c("&7Usage: /esettings ui <auto|java|bedrock>"));
            } else {
                dialogs.openSettings(p);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("ui")) {
            if (args.length < 2) { p.sendMessage(Msg.c("&cUsage: /esettings ui <auto|java|bedrock>")); return; }
            try {
                PlayerSettings.UiMode mode = PlayerSettings.UiMode.valueOf(args[1].toUpperCase(Locale.ROOT));
                settings.setUi(id, mode);
                p.sendMessage(Msg.c("&aMenu style set to &f" + mode + "&a."));
            } catch (IllegalArgumentException e) {
                p.sendMessage(Msg.c("&cUsage: /esettings ui <auto|java|bedrock>"));
            }
            return;
        }
        if (args.length < 2) { p.sendMessage(Msg.c("&cUsage: /esettings " + sub + " <on|off>")); return; }
        boolean on = args[1].equalsIgnoreCase("on");
        boolean off = args[1].equalsIgnoreCase("off");
        if (!on && !off) { p.sendMessage(Msg.c("&cUsage: /esettings " + sub + " <on|off>")); return; }
        switch (sub) {
            case "confirmah" -> { settings.setConfirmAh(id, on); p.sendMessage(Msg.c("&aAH buy confirm: &f" + onOff(on))); }
            case "confirmlisting" -> { settings.setConfirmListing(id, on); p.sendMessage(Msg.c("&aAH listing confirm: &f" + onOff(on))); }
            case "confirmorders" -> { settings.setConfirmOrders(id, on); p.sendMessage(Msg.c("&aOrder confirm: &f" + onOff(on))); }
            default -> p.sendMessage(Msg.c("&cUsage: /esettings <confirmah|confirmlisting|confirmorders> <on|off>, or /esettings ui <auto|java|bedrock>"));
        }
    }

    private String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    // ---------------------------------------------------------- auction house

    private void ah(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        boolean b = bedrock(p);
        if (args.length == 0) {
            if (b) gui.openAh(p, 0, false); else dialogs.openAh(p, null, 0, false);
            return;
        }
        if (args[0].equalsIgnoreCase("sell")) {
            sellOnAh(p, args.length < 2 ? null : args[1]);
        } else if (args[0].equalsIgnoreCase("search")) {
            String q = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            if (b) gui.openAhSearch(p, q); else dialogs.openAh(p, q, 0, false);
        } else {
            p.sendMessage(Msg.c("&cUsage: /ah, /ah sell <price>, /ah search <text>"));
        }
    }

    private void sellOnAh(Player p, String priceArg) {
        if (priceArg == null) {
            // No price given: open the pick-an-item flow instead of failing.
            if (bedrock(p)) gui.openSellPicker(p); else dialogs.openSellPicker(p);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { p.sendMessage(Msg.c("&cHold the item you want to sell.")); return; }
        OptionalDouble price = MoneyUtil.parse(priceArg);
        double min = plugin.getConfig().getDouble("auction.min-price", 1);
        if (price.isEmpty() || price.getAsDouble() < min) {
            p.sendMessage(Msg.c("&cInvalid price (minimum " + MoneyUtil.format(min) + ")."));
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            return;
        }
        if (bedrock(p)) gui.maybeConfirmSell(p, hand.clone(), price.getAsDouble());
        else dialogs.maybeConfirmSell(p, hand.clone(), price.getAsDouble());
    }

    // ---------------------------------------------------------- /sell (fixed base prices)

    private void sellCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) {
            if (bedrock(p)) gui.openQuickSell(p); else dialogs.openQuickSell(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> { if (bedrock(p)) sellHand(p); else dialogs.sellHand(p); }
            case "all" -> { if (bedrock(p)) sellAll(p); else dialogs.sellAll(p); }
            default -> p.sendMessage(Msg.c("&cUsage: /sell, /sell hand, /sell all"));
        }
    }

    private void sellHand(Player p) {
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

    private void sellAll(Player p) {
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

    // ---------------------------------------------------------- orders

    private void ordersCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        boolean b = bedrock(p);
        if (args.length == 0) {
            if (b) gui.openOrders(p, 0, false); else dialogs.openOrders(p, null, 0, false);
            return;
        }
        if (args[0].equalsIgnoreCase("search")) {
            String q = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            if (b) { /* orders browse has no dedicated search command yet on chest side; open browse */ gui.openOrders(p, 0, false); }
            else dialogs.openOrders(p, q, 0, false);
            return;
        }
        if (!args[0].equalsIgnoreCase("create")) {
            p.sendMessage(Msg.c("&cUsage: /orders, /orders create <item> <amount> <price each>"));
            return;
        }
        if (args.length < 4) {
            // No full details given: open the item-pick flow instead of failing.
            if (b) gui.openMaterialPicker(p, "", 0); else dialogs.openMaterialPicker(p, "", 0);
            return;
        }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null || mat.isAir() || !mat.isItem()) { p.sendMessage(Msg.c("&cUnknown item.")); return; }
        if (!b) {
            dialogs.reviewOrderFromCommand(p, mat, args[2], args[3]);
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            p.sendMessage(Msg.c("&cInvalid amount."));
            return;
        }
        OptionalDouble price = MoneyUtil.parse(args[3]);
        if (price.isEmpty()) { p.sendMessage(Msg.c("&cInvalid price.")); return; }
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        if (orders.countBy(p.getUniqueId()) >= maxOrders) { p.sendMessage(Msg.c("&cYou can only have " + maxOrders + " active orders.")); return; }
        if (amount < 1 || amount > maxAmount) { p.sendMessage(Msg.c("&cAmount must be between 1 and " + maxAmount + ".")); return; }
        if (price.getAsDouble() < 0.01) { p.sendMessage(Msg.c("&cPrice must be at least $0.01 each.")); return; }
        double cost = MoneyUtil.round(amount * price.getAsDouble());
        if (eco.get(p.getUniqueId()) + 0.001 < cost) { p.sendMessage(Msg.c("&cYou need " + MoneyUtil.format(cost) + " for that order.")); return; }
        gui.maybeConfirmOrder(p, mat, amount, price.getAsDouble());
    }

    // ---------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (name) {
            case "pay", "balance" -> {
                if (args.length == 1) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            case "eco" -> {
                if (args.length == 1) out.addAll(List.of("give", "take", "set", "sellprice", "sellmultiplier"));
                else if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                } else if (args.length == 2 && args[0].equalsIgnoreCase("sellprice")) {
                    return matItems(last);
                }
            }
            case "ah" -> {
                if (args.length == 1) out.addAll(List.of("sell", "search"));
            }
            case "sell" -> {
                if (args.length == 1) out.addAll(List.of("hand", "all"));
            }
            case "orders" -> {
                if (args.length == 1) out.addAll(List.of("create", "search"));
                else if (args.length == 2 && args[0].equalsIgnoreCase("create")) return matItems(last);
            }
            case "esettings" -> {
                if (args.length == 1) out.addAll(List.of("confirmah", "confirmlisting", "confirmorders", "ui"));
                else if (args.length == 2 && args[0].equalsIgnoreCase("ui")) out.addAll(List.of("auto", "java", "bedrock"));
                else if (args.length == 2) out.addAll(List.of("on", "off"));
            }
            default -> {}
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }

    private List<String> matItems(String prefix) {
        return Arrays.stream(Material.values())
                .filter(m -> m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_"))
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .filter(s -> s.startsWith(prefix))
                .limit(50)
                .collect(Collectors.toList());
    }
}
