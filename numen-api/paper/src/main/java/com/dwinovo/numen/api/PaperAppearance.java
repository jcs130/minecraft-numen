package com.dwinovo.numen.api;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.*;
import java.util.UUID;

/** Opt-in public appearance facts for server-side bodies which have no proxy login session. */
final class PaperAppearance {
    private static final Gson JSON = new Gson();
    private final Path config = NumenPaths.config().resolve("server/appearance_bindings.json");
    private final Path state = NumenPaths.config().resolve("server/appearance_state.json");
    private final String epoch = UUID.randomUUID().toString();

    static void start(JavaPlugin plugin) {
        var projection = new PaperAppearance();
        Bukkit.getScheduler().runTaskTimer(plugin, projection::write, 40, 40);
    }

    private void write() {
        try {
            if (!Files.isRegularFile(config) || Files.size(config) > 8192) return;
            var options = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            var bindings = options.getAsJsonObject("bindings");
            if (bindings == null || bindings.size() > 16) return;
            String backend = options.has("backend") ? options.get("backend").getAsString() : "paper";
            if (!backend.matches("[a-zA-Z0-9_-]{1,64}")) return;
            var rows = new JsonArray();
            var server = MinecraftServer.getServer();
            for (var binding : bindings.entrySet()) {
                UUID id = UUID.fromString(binding.getKey());
                var player = NumenPlayer.findByUuid(server, id);
                if (player == null || !player.isAlive()) continue;
                var entry = binding.getValue().getAsJsonObject();
                String model = entry.get("modelId").getAsString(), texture = entry.get("texture").getAsString();
                if (!model.matches("[a-zA-Z0-9_-]{1,96}") || !texture.matches("[a-zA-Z0-9_.-]{1,96}")) continue;
                var row = new JsonObject();
                row.addProperty("playerUuid", id.toString());
                row.addProperty("playerName", player.getGameProfile().getName());
                row.addProperty("entityId", player.getId());
                row.addProperty("dimension", player.level().dimension().location().toString());
                row.addProperty("backend", backend);
                row.addProperty("modelId", model); row.addProperty("texture", texture);
                rows.add(row);
            }
            var root = new JsonObject();
            root.addProperty("schemaVersion", 1); root.addProperty("source", "numen_server");
            root.addProperty("epoch", epoch); root.addProperty("observedAt", System.currentTimeMillis());
            root.add("players", rows);
            Files.createDirectories(state.getParent());
            Path temporary = state.resolveSibling(state.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(root));
            Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception invalid) {
            // Do not publish guessed identities or retain a fresh timestamp on failure.
            // Consumers expire the last valid snapshot after 15 seconds.
        }
    }
}
