package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.store.Atomic;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Explicit admin adoption keeps the original account's Bukkit permissions.
 * This server-owned marker grants no node and never trusts a client payload. */
public final class AdoptedPermissions {
    public static final String FILE = "adopted-permissions.json";
    private AdoptedPermissions() { }

    public static void record(Path file, UUID player, UUID owner) {
        JsonObject value = new JsonObject(); value.addProperty("v", 1);
        value.addProperty("player", player.toString()); value.addProperty("owner", owner.toString());
        value.addProperty("identity", "self");
        Atomic.write(file, value.toString());
    }

    public static boolean usesOriginal(Path file, UUID player, UUID owner) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 1024) return false;
            var value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return value.get("v").getAsInt() == 1 && value.get("identity").getAsString().equals("self")
                    && value.get("player").getAsString().equals(player.toString())
                    && value.get("owner").getAsString().equals(owner.toString());
        } catch (java.io.IOException | RuntimeException invalid) { return false; }
    }
}
