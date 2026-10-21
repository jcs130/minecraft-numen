package com.dwinovo.numen.api.config;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器设置里权限的两项:硬规则默认开;说不清是谁的方块,专用服务器上默认按别人的算、单机与局域网默认问主人;
 * 文件里写了的以文件为准;没有文件就写出一份带着这两项的默认文件给服主看。
 */
class ServerSettingsTest {

    private static Home home(Path dir) {
        return new Home(dir, Io.direct(e -> {
            throw e;
        }));
    }

    @Test
    void defaultsDependOnWhetherTheServerIsDedicated(@TempDir Path dir) {
        ServerSettings dedicated = new ServerSettings(home(dir.resolve("a")), true);
        assertTrue(dedicated.hardRules());
        assertTrue(dedicated.unknownAsOther());
        ServerSettings lan = new ServerSettings(home(dir.resolve("b")), false);
        assertTrue(lan.hardRules());
        assertFalse(lan.unknownAsOther());
    }

    @Test
    void whatTheFileSaysWinsAndWhatItLeavesOutIsTheDefault(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"hardRules\": false}");
        ServerSettings off = new ServerSettings(home(dir), true);
        assertFalse(off.hardRules());
        assertTrue(off.unknownAsOther());

        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"unknownAsOther\": false}");
        off.reload();
        assertTrue(off.hardRules());
        assertFalse(off.unknownAsOther(), "服主可以让专用服也问主人");
    }

    @Test
    void theDefaultFileCarriesTheTwoSettings(@TempDir Path dir) throws IOException {
        new ServerSettings(home(dir), true);
        JsonObject written = JsonParser.parseString(Files.readString(dir.resolve(ServerSettings.FILE))).getAsJsonObject();
        assertEquals(true, written.get("hardRules").getAsBoolean());
        assertEquals(true, written.get("unknownAsOther").getAsBoolean());
    }

    @Test
    void anUnreadableFileFallsBackToTheDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "this is not json");
        ServerSettings settings = new ServerSettings(home(dir), true);
        assertTrue(settings.hardRules());
        assertTrue(settings.unknownAsOther());
    }

    @Test
    void residentsAreOptInPerBodyAndSurviveRestart(@TempDir Path dir) {
        UUID a = UUID.randomUUID(), sibling = UUID.randomUUID();
        ServerSettings settings = new ServerSettings(home(dir), true);
        assertTrue(settings.residents().isEmpty());
        settings.setResident(a, true);
        settings.setResident(a, true);
        assertEquals(Set.of(a), settings.residents());
        assertFalse(settings.resident(sibling));
        ServerSettings restarted = new ServerSettings(home(dir), true);
        assertTrue(restarted.resident(a));
        assertFalse(restarted.resident(sibling));
        restarted.setResident(a, false);
        assertTrue(new ServerSettings(home(dir), true).residents().isEmpty());
    }

    @Test
    void oldSettingsDoNotOptAnyoneIn(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"maxCompanions\":2,\"dailyTokenLimit\":12345}");
        ServerSettings settings = new ServerSettings(home(dir), true);
        assertTrue(settings.residents().isEmpty());
        settings.setResident(UUID.randomUUID(), true);
        ServerSettings reread = new ServerSettings(home(dir), true);
        assertEquals(2, reread.maxCompanions());
        assertEquals(12345, reread.dailyTokenLimit());
        assertTrue(reread.hardRules());
        assertTrue(reread.unknownAsOther());
    }

    @Test
    void malformedResidentListDoesNotEnableItsValidPrefix(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"residentCompanions\":[\""
                + UUID.randomUUID() + "\",\"broken-uuid\"]}");
        assertTrue(new ServerSettings(home(dir), true).residents().isEmpty());
    }

    @Test
    void removingListOnReloadDisablesResidency(@TempDir Path dir) throws IOException {
        ServerSettings settings = new ServerSettings(home(dir), true);
        settings.setResident(UUID.randomUUID(), true);
        Files.writeString(dir.resolve(ServerSettings.FILE), "{}");
        settings.reload();
        assertTrue(settings.residents().isEmpty());
    }

    @Test
    void dedicatedSharedKeysAreOptInAndSurviveUnrelatedSettingsWrites(@TempDir Path dir) throws IOException {
        UUID allowed = UUID.randomUUID(), stranger = UUID.randomUUID();
        ServerSettings settings = new ServerSettings(home(dir), true);
        assertFalse(settings.canUseSharedKeys(allowed));
        assertTrue(new ServerSettings(home(dir.resolve("single")), false).canUseSharedKeys(allowed));
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"sharedKeyOwners\":[\"" + allowed + "\"]}");
        settings.reload();
        assertTrue(settings.canUseSharedKeys(allowed));
        assertFalse(settings.canUseSharedKeys(stranger));
        settings.setResident(UUID.randomUUID(), true);
        assertTrue(new ServerSettings(home(dir), true).canUseSharedKeys(allowed));
        Files.writeString(dir.resolve(ServerSettings.FILE), "{}");
        settings.reload();
        assertFalse(settings.canUseSharedKeys(allowed));
    }

    @Test
    void malformedSharedKeyOwnersNeverEnableAValidPrefix(@TempDir Path dir) throws IOException {
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve(ServerSettings.FILE), "{\"sharedKeyOwners\":[\"" + owner + "\",\"broken\"]}");
        assertFalse(new ServerSettings(home(dir), true).canUseSharedKeys(owner));
        Files.writeString(dir.resolve(ServerSettings.FILE), "broken json");
        assertFalse(new ServerSettings(home(dir), true).canUseSharedKeys(owner));
    }
}
