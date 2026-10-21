package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** 服主管的常驻开关:只改指定身体,不授 OP、不改变世界权限和主人是否在场。回执只给操作者。 */
public final class ResidentCommand {
    private ResidentCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var root = Commands.literal("resident").requires(source -> Services.permissions().isAdmin(source));
        root.then(Commands.literal("status").executes(ctx -> {
            var registry = CompanionRegistry.get(ctx.getSource().getServer());
            registry.all().forEach(id -> status(ctx.getSource(), id));
            if (registry.all().isEmpty()) say(ctx.getSource(), "[常驻] 尚无同伴。先召唤并配置模型，再启用常驻。");
            return registry.all().size();
        }).then(argument().executes(ctx -> run(ctx, null))));
        root.then(Commands.literal("enable").then(argument().executes(ctx -> run(ctx, true))));
        root.then(Commands.literal("disable").then(argument().executes(ctx -> run(ctx, false))));
        root.then(Commands.literal("adopt").then(Commands.argument("playerUuid", StringArgumentType.word())
                .then(Commands.argument("playerName", StringArgumentType.word())
                        .then(Commands.argument("ownerUuid", StringArgumentType.word()).executes(ResidentCommand::adopt)))));
        return root;
    }

    /** Explicit administrator operation; only registers an offline, verified existing account. */
    private static int adopt(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        try {
            UUID id = UUID.fromString(StringArgumentType.getString(ctx, "playerUuid"));
            UUID owner = UUID.fromString(StringArgumentType.getString(ctx, "ownerUuid"));
            String name = StringArgumentType.getString(ctx, "playerName");
            var registry = CompanionRegistry.get(server);
            if (id.equals(owner) || registry.find(owner) != null)
                throw new IllegalArgumentException("主人必须是独立的配置账号");
            if (server.getPlayerList().getPlayer(id) != null || server.getPlayerList().getPlayerByName(name) != null)
                throw new IllegalArgumentException("角色仍在线，请先正常退出");
            if (registry.find(id) != null || registry.all().stream().anyMatch(u -> registry.find(u).name().equalsIgnoreCase(name)))
                throw new IllegalArgumentException("该角色已登记，请用 resident status 查看");
            var profile = server.getProfileCache().get(id).orElseThrow(() -> new IllegalArgumentException("角色不在账号缓存中"));
            if (!profile.getName().equalsIgnoreCase(name) || server.getPlayerList().isOp(profile))
                throw new IllegalArgumentException("角色身份不匹配，或属于管理员账号");
            var ownerProfile = server.getProfileCache().get(owner).orElseThrow(() -> new IllegalArgumentException("主人账号尚未登录登记"));
            if (server.getPlayerList().isOp(ownerProfile))
                throw new IllegalArgumentException("请使用非 OP 的专用配置账号");
            var path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
            var tag = net.minecraft.nbt.NbtIo.readCompressed(path, net.minecraft.nbt.NbtAccounter.create(16L * 1024 * 1024));
            var saved = ResidentAdoption.inspect(tag, id, name);
            var location = net.minecraft.resources.ResourceLocation.tryParse(saved.dimension());
            var dimension = location == null ? null : net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, location);
            if (dimension == null || server.getLevel(dimension) == null)
                throw new IllegalArgumentException("存档维度未加载");
            AdoptedPermissions.record(com.dwinovo.numen.api.home.Homes.of(server).companion(id)
                    .file(AdoptedPermissions.FILE), id, owner);
            registry.put(id, new CompanionRegistry.Entry(name, owner, dimension, saved.position()));
            say(source, "[常驻] 已登记原角色 " + name + "，UUID=" + id
                    + "；未修改背包、职业或位置，尚未启用常驻。命令：/numen resident enable " + id);
            return 1;
        } catch (java.io.IOException | IllegalArgumentException | com.dwinovo.numen.api.store.StoreException e) {
            source.sendFailure(Component.literal("[常驻] 接管未完成：" + e.getMessage() + "。请核对离线账号与原 UUID 后重试。"));
            return 0;
        }
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> argument() {
        return Commands.argument("companion", StringArgumentType.string()).suggests((ctx, builder) ->
                SharedSuggestionProvider.suggest(CompanionRegistry.get(ctx.getSource().getServer()).all().stream()
                        .map(UUID::toString), builder));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Boolean enabled) {
        var source = ctx.getSource();
        var server = source.getServer();
        var registry = CompanionRegistry.get(server);
        String target = StringArgumentType.getString(ctx, "companion");
        var matches = registry.all().stream().filter(id -> id.toString().equals(target)
                || registry.find(id).name().equalsIgnoreCase(target)).toList();
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(matches.isEmpty()
                    ? "[常驻] 未找到该同伴。命令：/numen resident status"
                    : "[常驻] 名字重名，请用 status 显示的身体 UUID。命令：/numen resident status"));
            return 0;
        }
        UUID id = matches.getFirst();
        if (enabled != null) {
            ModelServices.of(server).settings().setResident(id, enabled);
            if (enabled) {
                CompanionBrains.ensure(server, id);
                Companions.scheduleResidentRestore(id);
            } else if (!OwnerPresence.present(server, registry.find(id).owner())) {
                var body = NumenPlayer.findByUuid(server, id);
                if (body != null) ServerPrograms.stop(body);
                CompanionLoops.get(id).ifPresent(CompanionLoop::ownerLeft);
            }
        }
        status(source, id);
        return 1;
    }

    private static void status(CommandSourceStack source, UUID id) {
        var server = source.getServer();
        var entry = CompanionRegistry.get(server).find(id);
        var loop = CompanionLoops.get(id).orElse(null);
        var body = NumenPlayer.findByUuid(server, id);
        String bodyState = entry.removed() ? "REMOVED" : entry.diedAt() > 0 ? "DEAD"
                : body != null ? "PRESENT" : "DORMANT";
        String state = loop == null ? "未建脑" : String.valueOf(loop.status().hold());
        String reason = loop == null || loop.status().holdReason() == null ? ""
                : "，原因=" + loop.status().holdReason().forModel();
        say(source, "[常驻] " + entry.name() + "，enabled=" + ModelServices.of(server).settings().resident(id)
                + "，body=" + (body != null) + "，bodyState=" + bodyState
                + (entry.removed() ? "，离场原因=" + entry.removedFor() : "")
                + "，hold=" + state + reason + "，UUID=" + id
                + "。命令：/numen resident " + (ModelServices.of(server).settings().resident(id) ? "disable " : "enable ") + id);
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
