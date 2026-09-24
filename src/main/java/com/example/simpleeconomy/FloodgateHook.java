package com.example.simpleeconomy;

import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/** Isolated so this class only gets loaded (and only touches Floodgate's API) after we've confirmed the plugin is present. */
final class FloodgateHook {
    private FloodgateHook() {}

    static boolean isBedrock(UUID uuid) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(uuid);
    }
}
