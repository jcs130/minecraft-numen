package com.dwinovo.numen.api.brain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdoptedPermissionsTest {
    @Test void onlyTheExplicitOriginalIdentityAndOwnerMatch(@TempDir Path dir) {
        Path file = dir.resolve(AdoptedPermissions.FILE);
        UUID player = UUID.randomUUID(), owner = UUID.randomUUID();
        assertFalse(AdoptedPermissions.usesOriginal(file, player, owner));
        AdoptedPermissions.record(file, player, owner);
        assertTrue(AdoptedPermissions.usesOriginal(file, player, owner));
        assertFalse(AdoptedPermissions.usesOriginal(file, UUID.randomUUID(), owner));
        assertFalse(AdoptedPermissions.usesOriginal(file, player, UUID.randomUUID()));
    }

    @Test void invalidMarkersCannotChangePermissionIdentity(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AdoptedPermissions.FILE);
        UUID player = UUID.randomUUID(), owner = UUID.randomUUID();
        for (String text : new String[]{"{}", "not json", "x".repeat(1025)}) {
            Files.writeString(file, text);
            assertFalse(AdoptedPermissions.usesOriginal(file, player, owner));
        }
    }
}
