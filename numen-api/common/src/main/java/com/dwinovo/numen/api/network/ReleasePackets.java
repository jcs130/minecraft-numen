package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 官方 0.1.4 / 0.1.4.1 的线路;同名包也保留各自的布局,不能交给新版解码器。 */
public final class ReleasePackets {
    private ReleasePackets() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("numen_api", path));
    }

    public record Modules(Map<String, String> manifest, Map<String, String> bodies) {
        public static final StreamCodec<ByteBuf, Modules> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(LinkedHashMap::new, Wire.TO_SERVER.text(), ByteBufCodecs.stringUtf8(12), 2048), Modules::manifest,
                ByteBufCodecs.map(LinkedHashMap::new, ByteBufCodecs.stringUtf8(12), Wire.TO_SERVER.text(), 2048), Modules::bodies,
                Modules::new);
        public Modules {
            manifest = Map.copyOf(manifest);
            bodies = Map.copyOf(bodies);
        }
    }

    public record Run(UUID entityUuid, String programId, String code, Modules modules)
            implements CustomPacketPayload, Wire.Fragmentable {
        public static final Type<Run> TYPE = ReleasePackets.type("run_program");
        public static final StreamCodec<ByteBuf, Run> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Run::entityUuid, Wire.TO_SERVER.text(), Run::programId,
                Wire.TO_SERVER.text(), Run::code, Modules.CODEC, Run::modules, Run::new);
        @Override public Type<Run> type() { return TYPE; }
    }

    public record Result(UUID entityUuid, String programId, String resultJson) implements CustomPacketPayload {
        public static final Type<Result> TYPE = ReleasePackets.type("program_result");
        public static final StreamCodec<ByteBuf, Result> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Result::entityUuid, Wire.TO_CLIENT.text(), Result::programId,
                Wire.TO_CLIENT.text(), Result::resultJson, Result::new);
        @Override public Type<Result> type() { return TYPE; }
    }

    public record Stop(UUID entityUuid, String programId, boolean cutOff, boolean stopBody, String why)
            implements CustomPacketPayload {
        public static final Type<Stop> TYPE = ReleasePackets.type("stop_program");
        public static final StreamCodec<ByteBuf, Stop> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Stop::entityUuid, Wire.TO_SERVER.text(), Stop::programId,
                ByteBufCodecs.BOOL, Stop::cutOff, ByteBufCodecs.BOOL, Stop::stopBody,
                Wire.TO_SERVER.text(), Stop::why, Stop::new);
        @Override public Type<Stop> type() { return TYPE; }
    }

    public record Cancel(UUID entityUuid) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = ReleasePackets.type("cancel_tasks");
        public static final StreamCodec<ByteBuf, Cancel> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Cancel::entityUuid, Cancel::new);
        @Override public Type<Cancel> type() { return TYPE; }
    }

    public record Skin(UUID uuid, String skinValue, String skinSig) implements CustomPacketPayload {
        public static final Type<Skin> TYPE = ReleasePackets.type("change_skin");
        public static final StreamCodec<ByteBuf, Skin> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Skin::uuid, Wire.TO_SERVER.text(), Skin::skinValue,
                Wire.TO_SERVER.text(), Skin::skinSig, Skin::new);
        @Override public Type<Skin> type() { return TYPE; }
    }

    public record Summon(String name, String skinValue, String skinSig, boolean creative) {
        public static final StreamCodec<ByteBuf, Summon> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), Summon::name, Wire.TO_SERVER.text(), Summon::skinValue,
                Wire.TO_SERVER.text(), Summon::skinSig, ByteBufCodecs.BOOL, Summon::creative, Summon::new);
    }

    public record ClientResult(UUID entityUuid, String callId, String replyJson, Optional<Modules> modules) {
        public static final StreamCodec<ByteBuf, ClientResult> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ClientResult::entityUuid, Wire.TO_SERVER.text(), ClientResult::callId,
                Wire.TO_SERVER.text(), ClientResult::replyJson, ByteBufCodecs.optional(Modules.CODEC),
                ClientResult::modules, ClientResult::new);
    }

    public record Roster(String worldId, List<Row> companions) {
        public record Row(UUID uuid, String name, long respawnInMs, boolean creative) {
            static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, Row::uuid, Wire.TO_CLIENT.text(), Row::name,
                    ByteBufCodecs.VAR_LONG, Row::respawnInMs, ByteBufCodecs.BOOL, Row::creative, Row::new);
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, Roster> CODEC = StreamCodec.composite(
                Wire.TO_CLIENT.text(), Roster::worldId, Row.CODEC.apply(ByteBufCodecs.list(64)), Roster::companions,
                Roster::new);
        public static Roster of(CompanionListPayload payload) {
            return new Roster(payload.worldId(), payload.companions().stream().map(e -> new Row(
                    e.uuid(), e.name(), e.respawnInMs(), e.creative())).toList());
        }
    }

    public record Death(UUID entityUuid, String cause) implements CustomPacketPayload {
        public static final Type<Death> TYPE = ReleasePackets.type("numen_death");
        public static final StreamCodec<ByteBuf, Death> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Death::entityUuid, Wire.TO_CLIENT.text(), Death::cause, Death::new);
        @Override public Type<Death> type() { return TYPE; }
    }

    public record Respawn(UUID entityUuid, String cause) implements CustomPacketPayload {
        public static final Type<Respawn> TYPE = ReleasePackets.type("numen_respawn");
        public static final StreamCodec<ByteBuf, Respawn> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Respawn::entityUuid, Wire.TO_CLIENT.text(), Respawn::cause, Respawn::new);
        @Override public Type<Respawn> type() { return TYPE; }
    }

    public record Task(UUID entityUuid, String taskId, String tool, String describe, boolean standing, long elapsedMs)
            implements CustomPacketPayload {
        public static final Type<Task> TYPE = ReleasePackets.type("current_task");
        public static final StreamCodec<ByteBuf, Task> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Task::entityUuid, Wire.TO_CLIENT.text(), Task::taskId,
                Wire.TO_CLIENT.text(), Task::tool, Wire.TO_CLIENT.text(), Task::describe,
                ByteBufCodecs.BOOL, Task::standing, ByteBufCodecs.VAR_LONG, Task::elapsedMs, Task::new);
        @Override public Type<Task> type() { return TYPE; }
    }

    public record Events(UUID entityUuid, List<EventQueue.Entry> entries)
            implements CustomPacketPayload, Wire.Oversized<Events> {
        public static final Type<Events> TYPE = ReleasePackets.type("numen_event");
        private static final StreamCodec<ByteBuf, EventQueue.Entry> ENTRY = StreamCodec.composite(
                Wire.TO_CLIENT.text(), EventQueue.Entry::type, Wire.TO_CLIENT.text(), EventQueue.Entry::text,
                ByteBufCodecs.VAR_LONG, EventQueue.Entry::ts, ByteBufCodecs.BOOL, EventQueue.Entry::urgent,
                ByteBufCodecs.optional(Wire.TO_CLIENT.text()), e -> Optional.ofNullable(e.result()).map(Object::toString),
                (type, text, ts, urgent, result) -> new EventQueue.Entry(type, text, ts, urgent,
                        result.map(s -> com.google.gson.JsonParser.parseString(s).getAsJsonObject()).orElse(null)));
        public static final StreamCodec<ByteBuf, Events> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Events::entityUuid, ENTRY.apply(ByteBufCodecs.list()), Events::entries,
                Events::new);
        @Override public Type<Events> type() { return TYPE; }
        @Override public Events shrunk(java.util.function.Predicate<Events> fits, int bytes, int budget) {
            List<EventQueue.Entry> out = new java.util.ArrayList<>(entries);
            List<Integer> longest = new java.util.ArrayList<>();
            for (int i = 0; i < entries.size(); i++) longest.add(i);
            longest.sort(java.util.Comparator.comparingInt((Integer i) -> entries.get(i).text().length()).reversed());
            for (int i : longest) {
                EventQueue.Entry e = entries.get(i);
                out.set(i, new EventQueue.Entry(e.type(), "事件正文过长，请查询当前状态。", e.ts(), e.urgent(), e.result()));
                Events value = new Events(entityUuid, List.copyOf(out));
                if (fits.test(value)) return value;
            }
            return new Events(entityUuid, List.copyOf(out));
        }
    }
}
