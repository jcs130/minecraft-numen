package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;

/** 无模组玩家的设置入口;同伴、模型与钥匙串仍只用原生服务,所有答复只回发令者。 */
public final class OwnerCommands {
    private static final int PAGE_SIZE = 5;
    private OwnerCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> helpNode() {
        return Commands.literal("help").executes(ctx -> help(ctx.getSource()));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> statusNode() {
        return Commands.literal("status").executes(OwnerCommands::status);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> modelNode() {
        return Commands.literal("model")
                .then(Commands.literal("list").executes(ctx -> models(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> models(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("service", StringArgumentType.word())
                                        .then(Commands.argument("protocol", StringArgumentType.word())
                                                .then(Commands.argument("model", StringArgumentType.string())
                                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                                .executes(OwnerCommands::saveModel)))))));
    }

    public static int help(CommandSourceStack source) {
        say(source, "[Numen] 查询：/numen status；模型目录：/numen model list [页码]。");
        say(source, "[Numen] 设置本人服务端模型：/numen model set <档案名> <服务商> <chat|responses|anthropic> <型号> <地址>。");
        say(source, "[Numen] 本人Key：/numen key <服务商> <Key>；基岩可用 /numenmenu 表单输入。Paper可能记录命令，勿在直播中展示Key。");
        say(source, "[Numen] 召唤：/numen player summon <名字>；绑定：/numen companion <名字> profile my:<档案名>。");
        say(source, "[Numen] 私聊同伴：/msg <名字> <内容>；外脑令牌：/numen token create brain <名字> 30。模型费用由本人承担。");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var owner = ctx.getSource().getPlayerOrException();
        String client = Presence.hasClient(owner.getUUID()) ? "当前协议客户端（服务端资料）"
                : ReleaseClients.hasClient(owner.getUUID()) ? "官方旧版客户端（玩家电脑资料）" : "命令入口（无需Numen客户端）";
        say(ctx.getSource(), "[Numen] 接入=" + client + "；凭据策略=本人优先；共享额度="
                + (ModelServices.of(owner.server).settings().canUseSharedKeys(owner.getUUID()) ? "服主允许" : "关闭")
                + "。命令：/numen help");
        var owned = CompanionRegistry.get(owner.server).ownedBy(owner.getUUID());
        if (owned.isEmpty()) say(ctx.getSource(), "[Numen] 尚无同伴。命令：/numen player summon <名字>");
        for (var her : owned) {
            var id = her.getKey(); var entry = her.getValue();
            var brain = CompanionLoops.get(id).orElse(null);
            boolean mcp = McpMode.instance().driving(owner.getUUID(), id);
            boolean clientBrain = ReleaseClients.driving(owner.getUUID(), id);
            String driver = mcp ? "MCP外脑" : clientBrain ? "客户端外脑" : "服务端大脑";
            String body = entry.removed() ? "已移除" : entry.diedAt() > 0 ? "死亡"
                    : NumenPlayer.findByUuid(owner.server, id) != null ? "在线" : "休眠";
            String state = brain == null ? "未建脑" : stateLabel(brain.status());
            String reason = brain == null || brain.status().holdReason() == null ? ""
                    : "；原因=" + brain.status().holdReason().forModel();
            say(ctx.getSource(), "[Numen] " + entry.name() + "；身体=" + body + "；驾驶=" + driver + "；状态=" + state
                    + reason + "；模型=" + (mcp || clientBrain ? "由外脑配置"
                    : brain == null || brain.bound(Slot.PROVIDER) == null ? "未绑定" : brain.bound(Slot.PROVIDER)));
        }
        return 1;
    }

    private static int models(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        var owner = ctx.getSource().getPlayerOrException();
        var entries = JsonParser.parseString(Libraries.of(owner.server).view(owner.getUUID(), false, Libraries.PROVIDER)).getAsJsonArray();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pages) { ctx.getSource().sendFailure(Component.literal("[Numen] 页码超出范围。命令：/numen model list " + pages)); return 0; }
        say(ctx.getSource(), "[Numen] 本人可见的服务端模型 " + page + "/" + pages + "（查询不调用模型）；旧版客户端模型请在 /numen settings 查看。");
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(entries.size(), page * PAGE_SIZE); i++) {
            var row = entries.get(i).getAsJsonObject();
            String key = field(row, "keySource");
            say(ctx.getSource(), "[Numen] " + field(row, "id") + "；型号=" + field(row, "model") + "；Key="
                    + (key.equals("mine") ? "本人已配置" : key.equals("server") ? "服主授权" : "未配置")
                    + (row.has("enabled") && !row.get("enabled").getAsBoolean() ? "；已停用" : ""));
        }
        return 1;
    }

    private static int saveModel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var owner = ctx.getSource().getPlayerOrException();
        if (!Presence.hasClient(owner.getUUID()) && ReleaseClients.hasClient(owner.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("[Numen] 当前由旧版客户端管理模型。请在 /numen settings 配置本人电脑上的档案和Key。"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        var shelf = LibraryHub.of(owner.server).library(ProviderLibrary.KIND, owner.getUUID()).mine();
        JsonObject draft;
        try {
            var previous = EntryId.segment(name) ? shelf.get(EntryId.mine(name)) : null;
            draft = profileDraft(name, StringArgumentType.getString(ctx, "service"),
                    StringArgumentType.getString(ctx, "protocol"), StringArgumentType.getString(ctx, "model"),
                    StringArgumentType.getString(ctx, "url").strip(), previous == null ? new JsonObject()
                            : ProviderLibrary.profileToJson(ProviderLibrary.read(previous.text())));
        } catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("[Numen] 档案格式不正确：使用有效名称、chat/responses/anthropic协议和不含凭据的HTTP(S)地址。命令：/numen help"));
            return 0;
        }
        var request = draft.deepCopy();
        request.addProperty("name", name); request.addProperty("id", EntryId.mine(name).toString());
        var result = Libraries.of(owner.server).handle(owner.getUUID(), false, Libraries.PROVIDER, Libraries.PUT, request);
        if (result.problem() != null) {
            ctx.getSource().sendFailure(Component.literal("[Numen] 档案未应用。命令：/numen model list")); return 0;
        }
        // 写车道后回读实际文件,不把排队保存说成已持久化。回执不跨玩家连接发送。
        var home = Homes.of(owner.server).owner(owner.getUUID());
        home.io().executor().execute(() -> {
            boolean saved;
            try {
                saved = JsonParser.parseString(Files.readString(home.file("provider").resolve("my").resolve(name + ".json"))).equals(draft);
            } catch (java.io.IOException | RuntimeException failed) {
                Constants.LOG.warn("[numen] 本人模型档案回读失败: {}", owner.getUUID());
                saved = false;
            }
            boolean confirmed = saved;
            owner.server.execute(() -> {
                if (owner.server.getPlayerList().getPlayer(owner.getUUID()) == owner) say(ctx.getSource(), confirmed
                        ? "[Numen] 本人档案已保存并核对：my:" + name + "。命令：/numen companion <名字> profile my:" + name
                        : "[Numen] 保存结果未确认，请查询后处理。命令：/numen model list");
            });
        });
        return 1;
    }

    /** 不含Key的档案更新;保留原生面板设过的上下文、输出预算和兼容项。 */
    static JsonObject profileDraft(String name, String service, String protocol, String model, String url, JsonObject previous) {
        if (!EntryId.segment(name) || !name.equals(EntryId.clean(name, "profile")) || !EntryId.segment(service)
                || !List.of("chat", "responses", "anthropic").contains(protocol) || model == null || model.isBlank())
            throw new IllegalArgumentException("invalid profile");
        URI endpoint = URI.create(url);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
            throw new IllegalArgumentException("invalid endpoint");
        JsonObject draft = previous.deepCopy();
        draft.addProperty("provider", service); draft.addProperty("protocol", protocol);
        draft.addProperty("model", model); draft.addProperty("base_url", url);
        return ProviderLibrary.profileToJson(ProviderLibrary.profileFromJson(draft));
    }

    private static String field(JsonObject row, String name) {
        return row.has(name) && row.get(name).isJsonPrimitive() ? row.get(name).getAsString() : "";
    }

    static String stateLabel(com.dwinovo.numen.api.agent.loop.LoopStatus state) {
        if (state.hold() != null) return switch (state.hold()) {
            case DEAD -> "身体离场(DEAD)";
            case EXTERNAL -> "外脑驾驶(EXTERNAL)";
            case AWAY -> "主人离线(AWAY)";
            case OWNER_STOP -> "主人已停止(OWNER_STOP)";
            case BLOCKED -> "配置阻塞(BLOCKED)";
            case FAILED -> "调用失败(FAILED)";
        };
        if (state.phase() == null) return state.bodyTaskRunning() ? "身体任务执行中" : "待命";
        return switch (state.phase()) {
            case MODEL -> "等待模型";
            case TOOLS -> "执行工具";
            case COMPACT -> "整理记忆";
        };
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
