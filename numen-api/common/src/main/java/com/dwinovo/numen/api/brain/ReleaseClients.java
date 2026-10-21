package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.OwnedBody;
import com.dwinovo.numen.api.network.ReleasePackets;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.program.NetworkTransport;
import com.dwinovo.numen.api.program.ReleaseModules;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 官方发布客户端是一种外部大脑;协议存在不等于新版 Presence,也不授予别人的身体或模型资料。 */
public final class ReleaseClients {
    private static final Map<UUID, Session> OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> BODIES = new ConcurrentHashMap<>();
    private static final Map<UUID, History> HISTORIES = new LinkedHashMap<>();
    private static final long HISTORY_MS = 60 * 60 * 1000L;
    private static final int MAX_HISTORY_OWNERS = 64;

    static {
        ServerLifecycle.onStopped(() -> {
            List.copyOf(OWNERS.keySet()).forEach(ReleaseClients::left);
            HISTORIES.clear();
        });
    }

    private ReleaseClients() {}

    private static final class History {
        final Map<UUID, Map<String, Receipt>> books = new HashMap<>();
        long touched = System.currentTimeMillis();
        int cachedBytes;
    }

    private static final class Receipt {
        final String fingerprint;
        String result;
        boolean running = true;
        Receipt(String fingerprint) { this.fingerprint = fingerprint; }
    }

    private static final class Session {
        final ServerPlayer owner;
        final History history;
        final Map<UUID, ReleaseModules> modules = new HashMap<>();
        final Map<UUID, Active> active = new HashMap<>();
        final Map<UUID, TaskView> tasks = new HashMap<>();
        volatile Set<UUID> bodies = Set.of();
        boolean online = true;
        Session(ServerPlayer owner, History history) { this.owner = owner; this.history = history; }
    }

    private record Active(String clientId, String serverId) {}
    private record TaskView(String id, String description) {}

    /** 平台已通过旧版独有频道识别客户端,且玩家已完成入场。主线程调用。 */
    public static void joined(ServerPlayer owner) {
        if (owner instanceof NumenPlayer || OWNERS.containsKey(owner.getUUID())) return;
        long now = System.currentTimeMillis();
        HISTORIES.entrySet().removeIf(e -> !OWNERS.containsKey(e.getKey()) && now - e.getValue().touched > HISTORY_MS);
        if (!HISTORIES.containsKey(owner.getUUID()) && HISTORIES.size() >= MAX_HISTORY_OWNERS) {
            UUID oldest = HISTORIES.keySet().stream().filter(id -> !OWNERS.containsKey(id)).findFirst().orElse(null);
            if (oldest == null) throw new IllegalStateException("旧客户端连接预算已满");
            HISTORIES.remove(oldest);
        }
        Session session = new Session(owner, HISTORIES.computeIfAbsent(owner.getUUID(), id -> new History()));
        OWNERS.put(owner.getUUID(), session);
        refresh(session);
        // 在旧客户端得到名册并启动循环之前,先让内脑看到驾驶权变化。
        session.bodies.forEach(id -> CompanionLoops.get(id).ifPresent(CompanionLoop::tick));
    }

    private static void refresh(Session session) {
        Set<UUID> ids = CompanionRegistry.get(session.owner.server).ownedBy(session.owner.getUUID()).stream()
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        BODIES.entrySet().removeIf(e -> e.getValue() == session && !ids.contains(e.getKey()));
        session.bodies = ids;
        ids.forEach(id -> BODIES.put(id, session));
    }

    /** 所有仍存在的同伴都留在名册,包括死亡、休眠和被移出的身体。 */
    public static void roster(ServerPlayer owner) {
        Session session = OWNERS.get(owner.getUUID());
        if (session != null) refresh(session);
    }

    public static boolean hasClient(UUID owner) { return OWNERS.containsKey(owner); }

    public static boolean driving(UUID owner, UUID body) {
        Session session = OWNERS.get(owner);
        return session != null && session.online && session.bodies.contains(body)
                && !McpMode.instance().driving(owner, body);
    }

    public static boolean isDriving(UUID body) {
        Session session = BODIES.get(body);
        return session != null && driving(session.owner.getUUID(), body);
    }

    /** 外脑控制权切换先取消旧客户端的程序,避免两个驾驶者交错派发身体动作。 */
    public static void reconcile(UUID body) {
        Session session = BODIES.get(body);
        if (session == null || !McpMode.instance().driving(session.owner.getUUID(), body)) return;
        Active active = session.active.get(body);
        if (active != null) ServerPrograms.cutOff(body, active.serverId(), true);
    }

    /** 收件箱的路由仍由同伴大脑调用;同一输入只交给当前驾驶者一次。 */
    public static boolean deliver(UUID body, List<EventQueue.Entry> entries) {
        Session session = BODIES.get(body);
        if (session == null || !driving(session.owner.getUUID(), body)) return false;
        NumenNetwork.sendToPlayer(session.owner, new ReleasePackets.Events(body, entries));
        return true;
    }

    public static void life(UUID body, String cause, boolean alive) {
        Session session = BODIES.get(body);
        if (session != null && driving(session.owner.getUUID(), body)) {
            if (alive) NumenNetwork.sendToPlayer(session.owner, new ReleasePackets.Respawn(body, cause));
            else NumenNetwork.sendToPlayer(session.owner, new ReleasePackets.Death(body, cause));
        }
    }

    /** 每秒补任务状态,取原任务槽,不由客户端猜自己是否完成。 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        boolean refresh = server.getTickCount() % 20 == 0;
        for (Session session : OWNERS.values()) {
            if (refresh) refresh(session);
            for (UUID id : session.bodies) {
                var task = CompanionTickDispatcher.currentTaskFor(id);
                String taskId = task == null ? "" : task.publicId();
                TaskView old = session.tasks.get(id);
                if (old != null && old.id().equals(taskId) && !refresh) continue;
                TaskView view = new TaskView(taskId, task == null ? "" : task.describe());
                if (view.equals(old)) continue;
                session.tasks.put(id, view);
                NumenNetwork.sendToPlayer(session.owner, task == null
                        ? new ReleasePackets.Task(id, "", "", "", false, 0)
                        : new ReleasePackets.Task(id, task.publicId(), task.getToolName(), task.describe(),
                                task.getDeadlineGameTime() >= com.dwinovo.numen.api.task.TaskRecord.NO_DEADLINE,
                                Math.max(0, server.overworld().getGameTime()
                                        - task.getStartedGameTime()) * 50));
            }
        }
    }

    private static NumenPlayer body(ServerPlayer owner, UUID id, java.util.function.Consumer<String> refuse) {
        var entry = CompanionRegistry.get(owner.server).find(id);
        if (entry == null || !owner.getUUID().equals(entry.owner())) {
            refuse.accept("不是你的同伴，请先查询本人的同伴名册。");
            return null;
        }
        if (entry.diedAt() > 0) {
            refuse.accept("同伴正在等待正常复活，请稍后查询名册，不要重复召回或执行程序。");
            return null;
        }
        if (!driving(owner.getUUID(), id)) {
            refuse.accept("该同伴当前由其他外部大脑控制，请先停止原控制端。");
            return null;
        }
        return OwnedBody.of(owner, id, refuse);
    }

    public static void run(ReleasePackets.Run request, ServerPlayer owner) {
        Session session = OWNERS.get(owner.getUUID());
        if (session == null) return;
        if (request.programId().isBlank() || request.programId().length() > 256) {
            result(owner, request, refused("程序编号须为 1 至 256 字符。"));
            return;
        }
        NumenPlayer her = body(owner, request.entityUuid(), why -> result(owner, request, refused(why)));
        if (her == null) return;
        Map<String, Receipt> book = session.history.books.computeIfAbsent(her.getUUID(), id -> new LinkedHashMap<>());
        String identity = Fingerprint.of(request.code() + "\n" + new java.util.TreeMap<>(request.modules().manifest()));
        Receipt previous = book.get(request.programId());
        if (previous != null) {
            result(owner, request, !previous.fingerprint.equals(identity)
                    ? refused("这个程序编号已用于其他内容，请重新查询状态后使用新编号。")
                    : previous.result != null ? previous.result
                    : refused(previous.running ? "该程序仍在执行，请等待原回执，不要重复派发。"
                            : "该程序的结果尚不能确认，请查询身体和任务状态，不要重放动作。"));
            return;
        }
        if (session.active.containsKey(her.getUUID())) {
            result(owner, request, refused("这个同伴已有程序在执行，本次没有开始；请等待原回执。"));
            return;
        }
        ReleaseModules modules = session.modules.computeIfAbsent(her.getUUID(), id -> new ReleaseModules());
        List<String> missing;
        try {
            missing = modules.accept(request.modules());
        } catch (IllegalArgumentException badModule) {
            result(owner, request, refused(badModule.getMessage()));
            return;
        }
        if (!missing.isEmpty()) {
            JsonObject json = new JsonObject();
            JsonArray hashes = new JsonArray();
            missing.forEach(hashes::add);
            json.add("missing", hashes);
            result(owner, request, json.toString());
            return;
        }
        if (book.size() >= 2048) {
            result(owner, request, refused("程序回执记录已满，本次没有执行。请保存当前进度并联系服主归档记录。"));
            return;
        }
        Receipt receipt = new Receipt(identity);
        book.put(request.programId(), receipt);
        Active active = new Active(request.programId(), "release-" + her.getUUID() + "-" + UUID.randomUUID());
        session.active.put(her.getUUID(), active);
        ServerPrograms.runClientProgram(her, her.getUUID(), owner.getUUID(), active.serverId(), request.code(), modules,
                NetworkTransport.INSTANCE, outcome -> {
                    receipt.running = false;
                    String json = ended(outcome);
                    // 保留编号和未知标志,仅回执正文受预算约束;正文被淘汰也不能重执行。
                    if (session.history.cachedBytes + json.length() * 2 <= 2 * 1024 * 1024) {
                        receipt.result = json;
                        session.history.cachedBytes += json.length() * 2;
                    }
                    session.active.remove(her.getUUID(), active);
                    if (session.online) result(owner, request, json);
                });
    }

    public static void stopped(ReleasePackets.Stop request, ServerPlayer owner) {
        Session session = OWNERS.get(owner.getUUID());
        Active active = session == null ? null : session.active.get(request.entityUuid());
        if (active == null || !request.programId().equals(active.clientId())
                || body(owner, request.entityUuid(), why -> {}) == null) return;
        if (request.cutOff()) ServerPrograms.cutOff(request.entityUuid(), active.serverId(), request.stopBody());
        else ServerPrograms.interrupt(request.entityUuid(), active.serverId(), request.why());
    }

    public static void cancel(ReleasePackets.Cancel request, ServerPlayer owner) {
        NumenPlayer her = body(owner, request.entityUuid(), why -> {});
        if (her != null) CompanionTickDispatcher.cancelFor(her);
    }

    public static void clientResult(ReleasePackets.ClientResult reply, ServerPlayer owner) {
        Session session = OWNERS.get(owner.getUUID());
        Active active = session == null ? null : session.active.get(reply.entityUuid());
        if (active == null || !reply.callId().startsWith(active.serverId() + "#")
                || !driving(owner.getUUID(), reply.entityUuid())) return;
        if (reply.modules().isPresent()) {
            try {
                session.modules.computeIfAbsent(reply.entityUuid(), id -> new ReleaseModules()).accept(reply.modules().get());
            } catch (IllegalArgumentException invalidModules) {
                NetworkTransport.INSTANCE.answered(owner.getUUID(), new com.dwinovo.numen.api.network.payload.ClientCallResultPayload(
                        reply.entityUuid(), reply.callId(), com.dwinovo.numen.script.ApiReply.error(
                                com.dwinovo.numen.script.ErrorKind.FAILED,
                                "客户端模块同步失败，文件可能已在客户端保存；请先核对模块，不要直接重做之前的动作。", null, null).toString()));
                return;
            }
        }
        NetworkTransport.INSTANCE.answered(owner.getUUID(), new com.dwinovo.numen.api.network.payload.ClientCallResultPayload(
                reply.entityUuid(), reply.callId(), reply.replyJson()));
    }

    public static void summon(ReleasePackets.Summon request, ServerPlayer owner) {
        if (!hasClient(owner.getUUID())) return;
        Companions.summonFor(owner, request.name().trim(), request.creative(), summoned -> {
            if (summoned.problem() != null) owner.sendSystemMessage(summoned.problem());
            else if (summoned.body() != null && !request.skinValue().isBlank())
                Companions.applySkin(owner.server, summoned.body().getUUID(), new MojangSkins.Skin(request.skinValue(), request.skinSig()));
        });
    }

    public static void skin(ReleasePackets.Skin request, ServerPlayer owner) {
        if (body(owner, request.uuid(), why -> {}) != null)
            Companions.applySkin(owner.server, request.uuid(), request.skinValue().isBlank() ? null
                    : new MojangSkins.Skin(request.skinValue(), request.skinSig()));
    }

    public static void left(UUID owner) {
        Session session = OWNERS.remove(owner);
        if (session == null) return;
        session.online = false;
        session.history.touched = System.currentTimeMillis();
        BODIES.entrySet().removeIf(e -> e.getValue() == session);
        Map.copyOf(session.active).forEach((body, program) -> ServerPrograms.cutOff(body, program.serverId(), true));
        NetworkTransport.INSTANCE.ownerLeft(owner);
        session.modules.clear();
    }

    private static void result(ServerPlayer owner, ReleasePackets.Run request, String json) {
        NumenNetwork.sendToPlayer(owner, new ReleasePackets.Result(request.entityUuid(), request.programId(), json));
    }

    private static String refused(String message) { return ended(Program.Outcome.refused(message)); }

    /** 与官方 RunResult.Ended 完全相同的 JSON,不把 return 值或私有错误对象搬上网线。 */
    static String ended(Program.Outcome outcome) {
        JsonObject out = new JsonObject();
        out.addProperty("receipt", outcome.receipt());
        out.addProperty("status", outcome.ending().status().wire());
        if (outcome.ending().errorKind() != null) out.addProperty("error_kind", outcome.ending().errorKind());
        JsonArray calls = new JsonArray();
        outcome.calls().forEach(c -> {
            JsonObject row = new JsonObject();
            row.addProperty("function", c.function()); row.addProperty("call", c.call());
            if (c.first() != null) row.addProperty("first", c.first());
            if (c.kind() != null) row.addProperty("kind", c.kind());
            calls.add(row);
        });
        out.add("calls", calls);
        if (outcome.stoppedFor() != null) out.addProperty("stopped_for", outcome.stoppedFor());
        JsonArray used = new JsonArray();
        outcome.used().forEach(u -> {
            JsonObject row = new JsonObject();
            row.addProperty("module", u.module()); row.addProperty("ok", u.tally().ok());
            row.addProperty("line", u.tally().line());
            if (u.tally().error() != null) row.addProperty("error", u.tally().error());
            used.add(row);
        });
        out.add("used", used);
        return out.toString();
    }
}
