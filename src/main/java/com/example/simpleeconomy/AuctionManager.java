package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** The auction house: list a whole item stack for a fixed price. Sales pay the seller instantly, even offline. */
public class AuctionManager {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> expired = new HashMap<>();

    public AuctionManager(SimpleEconomyPlugin plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
        this.file = new File(plugin.getDataFolder(), "auction.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    UUID id = UUID.fromString(key);
                    listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")),
                            s.getString("sellerName", "?"), ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item"))),
                            s.getDouble("price"), s.getLong("expires")));
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping bad listing " + key + ": " + e.getMessage());
                }
            }
        }
        ConfigurationSection es = y.getConfigurationSection("expired");
        if (es != null) {
            for (String key : es.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (String enc : es.getStringList(key)) {
                    try {
                        items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(enc)));
                    } catch (Exception ignored) {
                    }
                }
                expired.put(UUID.fromString(key), items);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "listings." + l.id();
            y.set(p + ".seller", l.seller().toString());
            y.set(p + ".sellerName", l.sellerName());
            y.set(p + ".price", l.price());
            y.set(p + ".expires", l.expiresAt());
            y.set(p + ".item", Base64.getEncoder().encodeToString(l.item().serializeAsBytes()));
        }
        expired.forEach((uuid, items) ->
                y.set("expired." + uuid, items.stream()
                        .map(it -> Base64.getEncoder().encodeToString(it.serializeAsBytes())).toList()));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save auction.yml: " + e.getMessage());
        }
    }

    public Collection<Listing> all() {
        return listings.values();
    }

    public long countBy(UUID seller) {
        return listings.values().stream().filter(l -> l.seller().equals(seller)).count();
    }

    public Listing create(Player seller, ItemStack item, double price) {
        long hours = plugin.getConfig().getLong("auction.duration-hours", 48);
        Listing l = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), item, price,
                System.currentTimeMillis() + hours * 3_600_000L);
        listings.put(l.id(), l);
        return l;
    }

    /** Removes a listing. Returns true if it was still active. */
    public boolean cancel(Listing l) {
        return listings.remove(l.id()) != null;
    }

    /** @return error message, or null on success */
    public String buy(Player buyer, Listing l) {
        if (!listings.containsKey(l.id())) return "&cThat listing is no longer available.";
        if (l.seller().equals(buyer.getUniqueId())) return "&cYou can't buy your own listing.";
        if (buyer.getInventory().firstEmpty() == -1) return "&cYour inventory is full.";
        if (!eco.withdraw(buyer.getUniqueId(), l.price())) return "&cYou can't afford that.";

        listings.remove(l.id());
        buyer.getInventory().addItem(l.item().clone());

        double tax = l.price() * plugin.getConfig().getDouble("auction.tax-percent", 0) / 100.0;
        eco.deposit(l.seller(), l.price() - tax);

        Player seller = Bukkit.getPlayer(l.seller());
        if (seller != null) {
            seller.sendMessage(Msg.c("&a" + buyer.getName() + " bought your " + l.item().getAmount() + "x "
                    + ItemNames.pretty(l.item().getType()) + " for &2" + MoneyUtil.format(l.price() - tax) + "&a."));
        }
        return null;
    }

    public void addExpired(UUID owner, ItemStack item) {
        expired.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);
    }

    public int expiredCount(UUID owner) {
        return expired.getOrDefault(owner, List.of()).size();
    }

    public List<ItemStack> takeExpired(UUID owner) {
        List<ItemStack> items = expired.remove(owner);
        return items == null ? new ArrayList<>() : items;
    }

    public void tickExpiry() {
        long now = System.currentTimeMillis();
        Iterator<Listing> it = listings.values().iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expiresAt() <= now) {
                it.remove();
                addExpired(l.seller(), l.item());
                Player p = Bukkit.getPlayer(l.seller());
                if (p != null) p.sendMessage(Msg.c("&eOne of your auction listings expired. Claim it in &f/ah&e."));
            }
        }
    }
}
