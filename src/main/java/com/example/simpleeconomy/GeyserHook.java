package com.example.simpleeconomy;

import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;

/** Isolated so this class only gets loaded (and only touches Geyser's API) after we've confirmed the plugin is present. */
final class GeyserHook {
    private GeyserHook() {}

    static boolean isBedrock(UUID uuid) {
        GeyserApi api = GeyserApi.api();
        return api != null && api.isBedrockPlayer(uuid);
    }
}
