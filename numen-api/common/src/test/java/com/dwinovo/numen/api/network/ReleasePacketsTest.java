package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReleasePacketsTest {
    @Test void allExistingCompanionsIncludingRemovedOnesKeepTheirUuidInTheLegacyRoster() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var value = new CompanionListPayload("world-id", List.of(
                new CompanionListPayload.Entry(a, "A", -1, false, ""),
                new CompanionListPayload.Entry(b, "B", -1, false, "outside removal")));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var legacy = ReleasePackets.Roster.of(value);
            ReleasePackets.Roster.CODEC.encode(buffer, legacy);
            assertEquals(legacy, ReleasePackets.Roster.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            assertEquals(List.of(a, b), legacy.companions().stream().map(ReleasePackets.Roster.Row::uuid).toList());
        } finally { buffer.release(); }
    }

    @Test void realFragmentTransportReassemblesLongProgramsAndModuleBodies() {
        String code = "--" + "长".repeat(Wire.TO_SERVER.bytes());
        var value = new ReleasePackets.Run(UUID.randomUUID(), "test-program", code,
                new ReleasePackets.Modules(Map.of(), Map.of()));
        assertEquals(value, Fragments.crossed(Wire.TO_SERVER, ReleasePackets.Run.CODEC, value));
    }

    @Test void oldSummonReadsBothSkinFieldsAndTheActualCreativeFlag() {
        var buffer = Unpooled.buffer();
        try {
            var expected = new ReleasePackets.Summon("TestPet", "texture", "signature", false);
            ReleasePackets.Summon.CODEC.encode(buffer, expected);
            assertEquals(expected, ReleasePackets.Summon.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }
}
