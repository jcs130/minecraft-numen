package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.brain.ReleaseClients;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.ReleasePackets;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/** Paper 频道边界识别协议族;旧包先完整解码再调原生逻辑,分片也经同一解码边界。 */
public final class PaperReleaseProtocol {
    private static final String ACK = "numen_api:hello_ack";
    private static final Set<String> MARKERS = Set.of("numen_api:companion_list", "numen_api:program_result",
            "numen_api:numen_event", "numen_api:client_ui_action");
    private static final Set<String> DOWNLINK = Set.of("numen_api:companion_list", "numen_api:client_ui_action",
            "numen_api:client_call", "numen_api:consent_request", "numen_api:numen_state", "numen_api:numen_locations",
            "numen_api:program_result", "numen_api:current_task", "numen_api:numen_event", "numen_api:numen_death",
            "numen_api:numen_respawn", "numen_api:fragment_to_client", "numen_api:path_debug");
    private static final Map<UUID, Fragments.Inbox> INBOXES = new HashMap<>();
    private static final Map<UUID, long[]> BUDGETS = new HashMap<>();
    private static final Map<UUID, Long> NOTIFIED = new HashMap<>();

    static {
        ServerLifecycle.onStopped(() -> { INBOXES.clear(); BUDGETS.clear(); NOTIFIED.clear(); });
    }
    private PaperReleaseProtocol() {}

    public static boolean release(Set<String> channels) {
        return !channels.contains(ACK) && channels.containsAll(MARKERS);
    }
    public static boolean release(ServerPlayer player) { return release(player.getBukkitEntity().getListeningPluginChannels()); }
    public static boolean accepts(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        String id = target(player, type.id()).toString();
        return release(player) && DOWNLINK.contains(id)
                && player.getBukkitEntity().getListeningPluginChannels().contains(id);
    }

    /** 寻路载荷的布局未变,但内容拆分后换了命名空间;这里只映射已审计的这个频道。 */
    public static ResourceLocation target(ServerPlayer player, ResourceLocation id) {
        return release(player) && id.toString().equals("numen:path_debug")
                ? ResourceLocation.fromNamespaceAndPath("numen_api", "path_debug") : id;
    }

    public static void registered(ServerPlayer player) {
        if (!player.server.getPlayerList().getPlayers().contains(player)) return;
        if (release(player)) ReleaseClients.joined(player);
        else if (ReleaseClients.hasClient(player.getUUID())) disconnected(player.getUUID());
    }

    public static void disconnected(UUID owner) {
        ReleaseClients.left(owner);
        INBOXES.remove(owner);
        BUDGETS.remove(owner);
        NOTIFIED.remove(owner);
    }

    /** 返回是否写入了旧版布局;其余允许的包与当前布局相同。 */
    public static boolean encode(ServerPlayer player, CustomPacketPayload payload, RegistryFriendlyByteBuf out) {
        if (!release(player)) return false;
        if (payload instanceof CompanionListPayload roster) {
            ReleaseClients.roster(player);
            ReleasePackets.Roster.CODEC.encode(out, ReleasePackets.Roster.of(roster));
            return true;
        }
        if (payload instanceof ClientUiActionPayload ui) {
            out.writeVarInt(switch (ui.action()) {
                case OPEN_SETTINGS -> 0;
                case DEBUG_TEXT_ON -> 2;
                case DEBUG_TEXT_OFF -> 3;
            });
            return true;
        }
        return false;
    }

    public static Runnable prepare(String channel, byte[] bytes, ServerPlayer player,
                                Map<String, BiFunction<ServerPlayer, RegistryFriendlyByteBuf, Runnable>> routes) {
        try {
            return preparePacket(channel, bytes, player, routes);
        } catch (DecoderException malformed) {
            long now = System.nanoTime();
            Long previous = NOTIFIED.get(player.getUUID());
            if (previous == null || now - previous > 5_000_000_000L) {
                NOTIFIED.put(player.getUUID(), now);
                com.dwinovo.numen.api.Constants.LOG.warn("[numen-net] rejected malformed {} from {}", channel, player.getUUID());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[Numen] 数据包格式与当前协议不符，本次没有执行。请核对客户端版本并重新连接。"));
            }
            return () -> {};
        }
    }

    private static Runnable preparePacket(String channel, byte[] bytes, ServerPlayer player,
                                Map<String, BiFunction<ServerPlayer, RegistryFriendlyByteBuf, Runnable>> routes) {
        if (!release(player)) return decode(channel, bytes, player, routes);
        registered(player);
        long second = System.nanoTime() / 1_000_000_000L;
        long[] budget = BUDGETS.computeIfAbsent(player.getUUID(), id -> new long[] {second, 0});
        if (budget[0] != second) { budget[0] = second; budget[1] = 0; }
        budget[1] += bytes.length;
        if (budget[1] > (long) Wire.MESSAGE_BYTES * 2) throw new DecoderException("Numen 旧协议上行超过预算");
        if (channel.equals(FragmentPayload.TO_SERVER.id().toString())) {
            var buffer = buffer(player, bytes);
            try {
                FragmentPayload fragment = read(FragmentPayload.TO_SERVER_CODEC, buffer);
                switch (INBOXES.computeIfAbsent(player.getUUID(), id -> new Fragments.Inbox(Wire.TO_SERVER)).accept(fragment)) {
                    case Fragments.Pending pending -> { return () -> {}; }
                    case Fragments.Rejected rejected -> throw new DecoderException(rejected.why());
                    case Fragments.Complete whole -> {
                        String kind = whole.kind().toString();
                        if (!kind.equals("numen_api:run_program") && !kind.equals("numen_api:client_call_result"))
                            throw new DecoderException("Numen 旧协议分片种类无效");
                        return decode(kind, whole.bytes(), player, routes);
                    }
                }
            } finally { buffer.release(); }
        } else return decode(channel, bytes, player, routes);
    }

    private static Runnable decode(String channel, byte[] bytes, ServerPlayer player,
                                Map<String, BiFunction<ServerPlayer, RegistryFriendlyByteBuf, Runnable>> routes) {
        var buffer = buffer(player, bytes);
        try {
            if (release(player) && channel.equals("numen_api:summon_request")) {
                var request = read(ReleasePackets.Summon.CODEC, buffer);
                return () -> ReleaseClients.summon(request, player);
            } else if (release(player) && channel.equals("numen_api:client_call_result")) {
                var reply = read(ReleasePackets.ClientResult.CODEC, buffer);
                return () -> ReleaseClients.clientResult(reply, player);
            } else {
                var route = routes.get(channel);
                if (route == null) throw new DecoderException("Numen 上行种类未登记");
                return route.apply(player, buffer);
            }
        } finally { buffer.release(); }
    }

    static <T> T read(net.minecraft.network.codec.StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                       RegistryFriendlyByteBuf buffer) {
        try {
            T value = codec.decode(buffer);
            consumed(buffer);
            return value;
        } catch (DecoderException alreadyExplained) {
            throw alreadyExplained;
        } catch (RuntimeException malformed) {
            throw new DecoderException("Numen 载荷无法按所选协议解码", malformed);
        }
    }

    static void consumed(RegistryFriendlyByteBuf buffer) {
        if (buffer.isReadable()) throw new DecoderException("Numen 数据包还有未读取字段，请核对客户端协议版本");
    }
    private static RegistryFriendlyByteBuf buffer(ServerPlayer player, byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), player.server.registryAccess());
    }
}
