package com.example.simpleeconomy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Small inventory helpers shared by both the Bedrock (chest/anvil) and Java (dialog) menus. */
final class InvUtil {
    private InvUtil() {}

    /** Finds a stack in the player's inventory matching the snapshot's type/meta with enough amount. */
    static ItemStack findMatching(Player p, ItemStack snapshot) {
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.isSimilar(snapshot) && it.getAmount() >= snapshot.getAmount()) return it;
        }
        return null;
    }

    static void removeOne(Player p, ItemStack found, int amount) {
        if (found.getAmount() == amount) {
            p.getInventory().removeItem(found);
        } else {
            found.setAmount(found.getAmount() - amount);
        }
    }

    /** Only plain items (no display name, no enchants) count - keeps listings/orders/sells from hiding value in NBT. */
    static boolean isPlain(ItemStack it) {
        return it != null && !it.getType().isAir()
                && (!it.hasItemMeta() || (!it.getItemMeta().hasDisplayName() && !it.getItemMeta().hasEnchants()));
    }
}
