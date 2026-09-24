package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** A buy order: ask for an amount of a plain item at a price each. The total cost is held (escrowed) until filled or cancelled. */
public class OrderManager {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final File file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();

    public OrderManager(SimpleEconomyPlugin plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("orders");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(key);
                orders.put(id, new Order(id, UUID.fromString(s.getString("owner")), s.getString("ownerName", "?"),
                        Material.valueOf(s.getString("material")), s.getInt("total"), s.getInt("filled"),
                        s.getInt("pending"), s.getDouble("priceEach")));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping bad order " + key + ": " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String p = "orders." + o.id;
            y.set(p + ".owner", o.owner.toString());
            y.set(p + ".ownerName", o.ownerName);
            y.set(p + ".material", o.material.name());
            y.set(p + ".total", o.total);
            y.set(p + ".filled", o.filled);
            y.set(p + ".pending", o.pending);
            y.set(p + ".priceEach", o.priceEach);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save orders.yml: " + e.getMessage());
        }
    }

    public Collection<Order> all() {
        return orders.values();
    }

    public long countBy(UUID owner) {
        return orders.values().stream().filter(o -> o.owner.equals(owner) && o.remaining() > 0).count();
    }

    public int pendingFor(UUID owner) {
        return orders.values().stream().filter(o -> o.owner.equals(owner)).mapToInt(o -> o.pending).sum();
    }

    /** @return error message, or null on success */
    public String create(Player p, Material mat, int amount, double priceEach) {
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        if (countBy(p.getUniqueId()) >= maxOrders) return "&cYou can only have " + maxOrders + " active orders.";
        if (amount < 1 || amount > maxAmount) return "&cAmount must be between 1 and " + maxAmount + ".";
        if (priceEach < 0.01) return "&cPrice must be at least $0.01 each.";
        double cost = MoneyUtil.round(amount * priceEach);
        if (!eco.withdraw(p.getUniqueId(), cost)) return "&cYou need " + MoneyUtil.format(cost) + " to place that order.";
        Order o = new Order(UUID.randomUUID(), p.getUniqueId(), p.getName(), mat, amount, 0, 0, priceEach);
        orders.put(o.id, o);
        return null;
    }

    private static boolean matches(ItemStack it, Material mat) {
        // Only plain items count (no renamed / enchanted / custom items)
        return it != null && it.getType() == mat && !it.hasItemMeta();
    }

    public int countMatching(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (matches(it, mat)) n += it.getAmount();
        }
        return n;
    }

    private void removeMatching(Player p, Material mat, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (!matches(it, mat)) continue;
            int take = Math.min(amount, it.getAmount());
            amount -= take;
            if (take == it.getAmount()) contents[i] = null;
            else it.setAmount(it.getAmount() - take);
        }
        inv.setStorageContents(contents);
    }

    /** Fills as much of the order as the player can from their inventory. @return message */
    public String fill(Player p, Order o) {
        if (!orders.containsKey(o.id) || o.remaining() <= 0) return "&cThat order is no longer available.";
        if (o.owner.equals(p.getUniqueId())) return "&cYou can't fill your own order.";
        int has = countMatching(p, o.material);
        if (has == 0) return "&cYou don't have any plain " + ItemNames.pretty(o.material) + " to deliver.";
        int n = Math.min(has, o.remaining());
        removeMatching(p, o.material, n);
        double pay = MoneyUtil.round(n * o.priceEach);
        eco.deposit(p.getUniqueId(), pay);
        o.filled += n;
        o.pending += n;
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) {
            owner.sendMessage(Msg.c("&a" + p.getName() + " delivered " + n + "x " + ItemNames.pretty(o.material)
                    + " to your order. Collect them in &f/orders&a."));
        }
        return "&aYou delivered &f" + n + "x " + ItemNames.pretty(o.material) + "&a and earned &2" + MoneyUtil.format(pay) + "&a.";
    }

    /** Gives collected items to the owner. @return message */
    public String collect(Player p, Order o) {
        if (o.pending <= 0) return "&cNothing to collect on that order.";
        int given = 0;
        while (o.pending > 0) {
            int size = Math.min(o.pending, o.material.getMaxStackSize());
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(o.material, size));
            int notGiven = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            o.pending -= (size - notGiven);
            given += (size - notGiven);
            if (notGiven > 0) break;
        }
        if (o.remaining() <= 0 && o.pending <= 0) orders.remove(o.id);
        return given == 0 ? "&cYour inventory is full." : "&aCollected &f" + given + "x " + ItemNames.pretty(o.material) + "&a.";
    }

    /** Cancels the unfilled part of an order and refunds it. @return message */
    public String cancel(Player p, Order o) {
        if (!orders.containsKey(o.id)) return "&cThat order no longer exists.";
        int rem = o.remaining();
        double refund = MoneyUtil.round(rem * o.priceEach);
        eco.deposit(o.owner, refund);
        o.total = o.filled;
        if (o.pending <= 0) orders.remove(o.id);
        return "&aOrder cancelled. Refunded &2" + MoneyUtil.format(refund) + "&a.";
    }
}
