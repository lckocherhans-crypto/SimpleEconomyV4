package com.example.simpleeconomy;

import org.bukkit.Material;

import java.util.UUID;

public class Order {
    public final UUID id;
    public final UUID owner;
    public final String ownerName;
    public final Material material;
    public final double priceEach;
    public int total;    // items wanted
    public int filled;   // items delivered by sellers
    public int pending;  // filled items the owner hasn't collected yet

    public Order(UUID id, UUID owner, String ownerName, Material material, int total, int filled, int pending, double priceEach) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.material = material;
        this.total = total;
        this.filled = filled;
        this.pending = pending;
        this.priceEach = priceEach;
    }

    public int remaining() {
        return total - filled;
    }
}
