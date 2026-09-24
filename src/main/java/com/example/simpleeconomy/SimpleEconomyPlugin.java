package com.example.simpleeconomy;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public class SimpleEconomyPlugin extends JavaPlugin implements Listener {
    private EconomyManager economy;
    private AuctionManager auction;
    private OrderManager orders;
    private PriceManager prices;
    private PlayerSettings settings;
    private Gui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        economy = new EconomyManager(this);
        auction = new AuctionManager(this, economy);
        orders = new OrderManager(this, economy);
        prices = new PriceManager(this);
        settings = new PlayerSettings(this);
        economy.load();
        auction.load();
        orders.load();
        prices.load();
        settings.load();

        SignInput signInput = new SignInput(this);
        gui = new Gui(this, economy, auction, orders, prices, settings, signInput);
        Dialogs dialogHelper = new Dialogs(this);
        DialogMenus dialogMenus = new DialogMenus(this, economy, auction, orders, prices, settings, dialogHelper);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MoneyExpansion(this, economy).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }

        Commands cmds = new Commands(this, economy, auction, orders, prices, settings, gui, dialogMenus);
        for (String name : List.of("balance", "pay", "baltop", "eco", "ah", "sell", "orders", "esettings")) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmds);
                c.setTabCompleter(cmds);
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(signInput, this);

        // autosave every 5 minutes, expire listings every 30 seconds
        getServer().getScheduler().runTaskTimer(this, this::saveAll, 6000L, 6000L);
        getServer().getScheduler().runTaskTimer(this, auction::tickExpiry, 600L, 600L);
    }

    @Override
    public void onDisable() {
        saveAll();
    }

    private void saveAll() {
        economy.save();
        auction.save();
        orders.save();
        prices.save();
        settings.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        economy.ensure(id);
        int expired = auction.expiredCount(id);
        int pending = orders.pendingFor(id);
        if (expired > 0) p.sendMessage(Msg.c("&eYou have &f" + expired + " &eexpired auction item(s). Claim them in &f/ah&e."));
        if (pending > 0) p.sendMessage(Msg.c("&eYou have &f" + pending + " &eitems waiting on your orders. Collect in &f/orders&e."));
    }
}
