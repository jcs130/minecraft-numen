package com.dwinovo.numen.api.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCommandsTest {
    @Test
    void idleAndBlockedAreDistinctWithoutAStringNullStatus() {
        assertEquals("待命", OwnerCommands.stateLabel(new com.dwinovo.numen.api.agent.loop.LoopStatus(null, null, null, false, java.util.List.of(), 0, null)));
        assertEquals("配置阻塞(BLOCKED)", OwnerCommands.stateLabel(new com.dwinovo.numen.api.agent.loop.LoopStatus(null,
                com.dwinovo.numen.api.agent.loop.Hold.BLOCKED, null, false, java.util.List.of(), 0, null)));
    }
    @Test
    void editingAProfilePreservesBudgetsAndNeverCopiesAKeyIntoIt() {
        JsonObject old = JsonParser.parseString("{\"ctx\":65536,\"max_tokens\":4096,\"api_key\":\"do-not-copy\",\"temperature\":0.3}").getAsJsonObject();
        var draft = OwnerCommands.profileDraft("mine", "custom", "responses", "model", "http://127.0.0.1:9000/v1", old);
        assertEquals(65536, draft.get("ctx").getAsInt());
        assertEquals(4096, draft.get("max_tokens").getAsInt());
        assertEquals(0.3, draft.get("temperature").getAsDouble());
        assertEquals("responses", draft.get("protocol").getAsString());
        assertFalse(draft.toString().contains("do-not-copy"));
        assertTrue(old.has("api_key"), "转换不修改调用者提供的对象");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///secret", "https://user:password@example.test/v1", "https://example.test/v1?key=secret", "https://example.test/#secret", "invalid", "javascript:alert(1)"})
    void endpointsCannotContainCredentialsOrNonHttpSchemes(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> OwnerCommands.profileDraft("mine", "custom", "chat", "model", endpoint, new JsonObject()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../other", "server:profile", ".hidden", "a/b", ""})
    void namesCannotEscapeTheCallersOwnLibrary(String name) {
        assertThrows(IllegalArgumentException.class, () -> OwnerCommands.profileDraft(name, "custom", "chat", "model", "https://example.test/v1", new JsonObject()));
    }

    @Test
    void unsupportedProtocolsAndEmptyModelsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> OwnerCommands.profileDraft("mine", "custom", "auto", "model", "https://example.test", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> OwnerCommands.profileDraft("mine", "custom", "chat", " ", "https://example.test", new JsonObject()));
    }
}
