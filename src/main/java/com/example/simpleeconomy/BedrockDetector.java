package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Tells Java and Bedrock (Geyser) players apart, so the plugin can give Java players the dialog
 * menus and Bedrock players the chest/anvil menus.
 *
 * Prefers Floodgate's API when the Floodgate plugin is installed - this is the most reliable check
 * and works even when Geyser itself runs as a separate standalone process. Falls back to Geyser's
 * own API if only the Geyser-Spigot plugin is installed on this server. If neither plugin is
 * present, every player is treated as Java (the safer default, since dialogs at least render for
 * everyone even if not perfectly for Bedrock).
 */
public final class BedrockDetector {
    private static Boolean floodgatePresent;
    private static Boolean geyserPresent;

    private BedrockDetector() {}

    public static boolean isBedrock(Player p) {
        return isBedrock(p.getUniqueId());
    }

    public static boolean isBedrock(UUID uuid) {
        if (floodgatePresent == null) {
            floodgatePresent = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        }
        if (floodgatePresent) {
            try {
                return FloodgateHook.isBedrock(uuid);
            } catch (Throwable ignored) {
                // Plugin present but API mismatch or not fully enabled yet - fall through
            }
        }
        if (geyserPresent == null) {
            geyserPresent = Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                    || Bukkit.getPluginManager().getPlugin("Geyser") != null;
        }
        if (geyserPresent) {
            try {
                return GeyserHook.isBedrock(uuid);
            } catch (Throwable ignored) {
                // Plugin present but API mismatch or not fully enabled yet
            }
        }
        return false;
    }
}
