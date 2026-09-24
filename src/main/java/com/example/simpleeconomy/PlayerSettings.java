package com.example.simpleeconomy;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Per-player preferences: whether to show confirm screens, and which menu style (dialog/chest) to use. */
public class PlayerSettings {

    public enum UiMode { AUTO, JAVA, BEDROCK }

    private static class Prefs {
        boolean confirmAh = true;
        boolean confirmOrders = true;
        boolean confirmListing = true;
        UiMode ui = UiMode.AUTO;
    }

    private final SimpleEconomyPlugin plugin;
    private final File file;
    private final Map<UUID, Prefs> prefs = new HashMap<>();

    public PlayerSettings(SimpleEconomyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "settings.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            var s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Prefs p = new Prefs();
                p.confirmAh = s.getBoolean("confirmAh", true);
                p.confirmOrders = s.getBoolean("confirmOrders", true);
                p.confirmListing = s.getBoolean("confirmListing", true);
                p.ui = UiMode.valueOf(s.getString("ui", "AUTO"));
                prefs.put(UUID.fromString(key), p);
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping bad settings entry '" + key + "': " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        prefs.forEach((id, p) -> {
            String base = "players." + id;
            y.set(base + ".confirmAh", p.confirmAh);
            y.set(base + ".confirmOrders", p.confirmOrders);
            y.set(base + ".confirmListing", p.confirmListing);
            y.set(base + ".ui", p.ui.name());
        });
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save settings.yml: " + e.getMessage());
        }
    }

    private Prefs of(UUID id) {
        return prefs.computeIfAbsent(id, k -> new Prefs());
    }

    public boolean confirmAh(UUID id) { return of(id).confirmAh; }
    public boolean confirmOrders(UUID id) { return of(id).confirmOrders; }
    public boolean confirmListing(UUID id) { return of(id).confirmListing; }
    public UiMode ui(UUID id) { return of(id).ui; }

    public void setConfirmAh(UUID id, boolean v) { of(id).confirmAh = v; save(); }
    public void setConfirmOrders(UUID id, boolean v) { of(id).confirmOrders = v; save(); }
    public void setConfirmListing(UUID id, boolean v) { of(id).confirmListing = v; save(); }
    public void setUi(UUID id, UiMode mode) { of(id).ui = mode; save(); }

    /** True if this player should see the chest/anvil menus instead of dialogs. */
    public boolean useBedrockUi(org.bukkit.entity.Player p) {
        UiMode mode = ui(p.getUniqueId());
        return switch (mode) {
            case JAVA -> false;
            case BEDROCK -> true;
            case AUTO -> BedrockDetector.isBedrock(p);
        };
    }
}
