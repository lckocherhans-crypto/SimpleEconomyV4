package com.example.simpleeconomy;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** An auction house listing: one whole item stack for a fixed total price. */
public record Listing(UUID id, UUID seller, String sellerName, ItemStack item, double price, long expiresAt) {
}
