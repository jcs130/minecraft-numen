package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 独立 JVM 把原 NeoForge 发布 API 放上 classpath,实际 codec 双向核对;不把旧 API 打进插件。 */
public final class OfficialReleaseCodecProbe {
    private static int checks;
    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf,Object> official(String name) throws Exception {
        return (StreamCodec<RegistryFriendlyByteBuf,Object>) Class.forName("com.dwinovo.numen.network.payload."+name)
                .getField("STREAM_CODEC").get(null);
    }
    private static <T> void roundTrip(String name, StreamCodec<? super RegistryFriendlyByteBuf,T> adapter, T value) throws Exception {
        var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        var back = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            adapter.encode(out,value);
            Object decoded = official(name).decode(out);
            if(out.isReadable()) throw new AssertionError(name+" official decoder left "+out.readableBytes()+" bytes");
            official(name).encode(back,decoded);
            if(!value.equals(adapter.decode(back)) || back.isReadable()) throw new AssertionError(name+" adapter round trip differed");
            checks++;
            System.out.println("PASS "+name);
        } finally { out.release(); back.release(); }
    }
    public static void main(String[] args) throws Exception {
        UUID id=UUID.fromString("11111111-2222-3333-4444-555555555555");
        var modules=new ReleasePackets.Modules(Map.of("route","123456789abc"),Map.of("123456789abc","return 42"));
        roundTrip("RunProgramPayload",ReleasePackets.Run.CODEC,new ReleasePackets.Run(id,"probe","return 42",modules));
        roundTrip("ProgramResultPayload",ReleasePackets.Result.CODEC,new ReleasePackets.Result(id,"probe","{\"receipt\":\"done\",\"status\":\"ok\"}"));
        roundTrip("StopProgramPayload",ReleasePackets.Stop.CODEC,new ReleasePackets.Stop(id,"probe",true,true,"stop"));
        roundTrip("CancelTasksPayload",ReleasePackets.Cancel.CODEC,new ReleasePackets.Cancel(id));
        roundTrip("ChangeSkinPayload",ReleasePackets.Skin.CODEC,new ReleasePackets.Skin(id,"texture","signature"));
        roundTrip("SummonRequestPayload",ReleasePackets.Summon.CODEC,new ReleasePackets.Summon("CodecProbe","texture","signature",false));
        roundTrip("ClientCallResultPayload",ReleasePackets.ClientResult.CODEC,new ReleasePackets.ClientResult(id,"probe#1","{}",Optional.empty()));
        roundTrip("ClientCallResultPayload",ReleasePackets.ClientResult.CODEC,new ReleasePackets.ClientResult(id,"probe#2","{}",Optional.of(modules)));
        roundTrip("CurrentTaskPayload",ReleasePackets.Task.CODEC,new ReleasePackets.Task(id,"task","move.to","walking",true,1500));
        roundTrip("NumenDeathPayload",ReleasePackets.Death.CODEC,new ReleasePackets.Death(id,"fell"));
        roundTrip("NumenRespawnPayload",ReleasePackets.Respawn.CODEC,new ReleasePackets.Respawn(id,"fell"));
        for(int size=0;size<3;size++) {
            var row=new ReleasePackets.Roster.Row(id,"CodecProbe",600,false);
            roundTrip("CompanionListPayload",ReleasePackets.Roster.CODEC,
                    new ReleasePackets.Roster("world",size==0?List.of():size==1?List.of(row):List.of(row,row)));
        }
        roundTrip("NumenEventPayload",ReleasePackets.Events.CODEC,new ReleasePackets.Events(id,
                List.of(new EventQueue.Entry("query","hello",45,true,null))));
        System.out.println("OFFICIAL_CODEC_CHECKS="+checks);
    }
}
