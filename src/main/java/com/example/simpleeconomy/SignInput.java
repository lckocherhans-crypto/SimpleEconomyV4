package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Text input via a real (temporary) sign, for search boxes - sign editing is one of the most
 * reliable text-input mechanisms on Bedrock via Geyser. Only the sign's top line is ever read as
 * input; the other three lines are pre-filled with instructions for context and are ignored even
 * if the player types over them.
 *
 * The tricky part: Bukkit has no event for "player closed the sign editor without submitting"
 * (pressing Escape sends no packet at all on many client/version combinations), so a session that
 * only waited for SignChangeEvent could hang forever - which is exactly the "can't open the menu
 * again" bug this replaces. To guarantee the callback always fires, every session has a short
 * timeout fallback, and starting a new session for a player (or them disconnecting) always finishes
 * any previous one first. The original block is restored exactly, whether or not the player typed
 * anything.
 */
public class SignInput implements Listener {
    private static final long TIMEOUT_TICKS = 20L * 25; // 25 seconds

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static class Session {
        Location location;
        BlockData originalData;
        Consumer<String> onSubmit;
        boolean finished;
    }

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param contextLines up to 3 lines of instructions shown under the (blank) input line, so the
     *                      player has context for what they're typing and where to submit it
     * @param onSubmit      called exactly once, with the trimmed top-line text ("" if they left it
     *                      blank or the session timed out) - never called with null
     */
    public void open(Player p, String[] contextLines, Consumer<String> onSubmit) {
        // Finish any stale session for this player first so sessions never leak or double-fire.
        finishExisting(p);

        World world = p.getWorld();
        int y = world.getMinHeight();
        Location loc = new Location(world, p.getLocation().getBlockX(), y, p.getLocation().getBlockZ());
        Block block = loc.getBlock();

        Session s = new Session();
        s.location = loc;
        s.originalData = block.getBlockData().clone();
        s.onSubmit = onSubmit;
        sessions.put(p.getUniqueId(), s);

        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            // Couldn't place the sign (e.g. protection plugin) - restore and fail safely rather than hang.
            restore(s);
            sessions.remove(p.getUniqueId());
            onSubmit.accept("");
            return;
        }

        sign.line(0, Msg.lore(""));
        for (int i = 0; i < 3; i++) {
            sign.line(i + 1, Msg.lore(i < contextLines.length ? contextLines[i] : ""));
        }
        sign.setWaxed(false);
        sign.update(true, false);
        p.openSign(sign, Side.FRONT);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session cur = sessions.get(p.getUniqueId());
            if (cur == s && !s.finished) finish(p, s, "");
        }, TIMEOUT_TICKS);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !e.getBlock().getLocation().equals(s.location)) return;
        e.setCancelled(true);
        String text = e.getLine(0);
        finish(e.getPlayer(), s, text == null ? "" : text.trim());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null && !s.finished) {
            s.finished = true;
            restore(s);
        }
    }

    private void finishExisting(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null || s.finished) return;
        s.finished = true;
        restore(s);
        // Deliberately don't call the old callback - a brand new session is about to replace it.
    }

    private void finish(Player p, Session s, String text) {
        if (s.finished) return;
        s.finished = true;
        sessions.remove(p.getUniqueId());
        restore(s);
        Consumer<String> callback = s.onSubmit;
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(text));
    }

    private void restore(Session s) {
        try {
            s.location.getBlock().setBlockData(s.originalData, false);
        } catch (Exception ignored) {
            // Chunk may have unloaded; nothing more we can safely do.
        }
    }
}
