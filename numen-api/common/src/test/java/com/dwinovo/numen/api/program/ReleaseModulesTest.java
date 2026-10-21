package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.network.ReleasePackets;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseModulesTest {
    private static final String BODY = "local M = {}; function M.value() return 7 end; return M";

    @Test void missingTextsAreReportedBeforeExecutionAndCanBeSuppliedOnce() {
        var cache = new ReleaseModules();
        String hash = Fingerprint.of(BODY);
        assertEquals(java.util.List.of(hash), cache.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of())));
        assertNull(cache.code("my.test"));
        assertTrue(cache.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of(hash, BODY))).isEmpty());
        assertEquals(BODY, cache.code("my.test"));
        assertTrue(cache.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of())).isEmpty());
    }

    @Test void badFingerprintsCannotPartiallyReplaceThePreviousSnapshot() {
        var cache = new ReleaseModules();
        String hash = Fingerprint.of(BODY);
        cache.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of(hash, BODY)));
        assertThrows(IllegalArgumentException.class, () -> cache.accept(new ReleasePackets.Modules(
                Map.of("my.changed", hash), Map.of(hash, "return {}"))));
        assertEquals(BODY, cache.code("my.test"));
        assertEquals(java.util.List.of("my.test"), cache.names());
    }

    @Test void oneConnectionsModulesCannotFillAnotherConnectionsCache() {
        var a = new ReleaseModules(); var b = new ReleaseModules();
        String hash = Fingerprint.of(BODY);
        a.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of(hash, BODY)));
        assertEquals(java.util.List.of(hash), b.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of())));
    }

    @Test void modulesRemovedByTheClientLeaveTheSnapshot() {
        var cache = new ReleaseModules();
        String hash = Fingerprint.of(BODY);
        cache.accept(new ReleasePackets.Modules(Map.of("my.test", hash), Map.of(hash, BODY)));
        cache.accept(new ReleasePackets.Modules(Map.of(), Map.of()));
        assertNull(cache.code("my.test"));
        assertTrue(cache.names().isEmpty());
    }

    @Test void invalidModuleNamesNeverBecomeFilesOrScriptNames() {
        var cache = new ReleaseModules();
        String hash = Fingerprint.of(BODY);
        assertThrows(IllegalArgumentException.class, () -> cache.accept(new ReleasePackets.Modules(
                Map.of("../../server", hash), Map.of(hash, BODY))));
        assertTrue(cache.names().isEmpty());
    }
}
