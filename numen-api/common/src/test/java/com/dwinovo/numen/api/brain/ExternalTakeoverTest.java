package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.*;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.*;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.program.ProgramHost;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.script.ScriptCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ExternalTakeoverTest {
    private final AtomicBoolean driving = new AtomicBoolean(false);
    private final List<String> cut = new ArrayList<>();
    private final Map<String, Consumer<Program.Outcome>> waiting = new HashMap<>();

    private CompanionLoop loop(Path dir) {
        UUID id = UUID.randomUUID();
        Io io = Io.direct(error -> { throw error; });
        NoteBook.init(uuid -> new Home(dir.resolve("memory/" + uuid), io), () -> 0);
        Mind mind = new Mind() {
            public Cause unavailable(String p) { return Cause.of(Cause.UNBOUND); }
            public LlmEndpoint endpoint(String p) { throw new AssertionError("no model call"); }
            public void ask(String p, UUID u, ModelRequest r, CancelToken c, Consumer<ModelPort.Delta> d, Consumer<ModelOutcome> done) { throw new AssertionError("no model call"); }
        };
        ProgramHost programs = new ProgramHost() {
            public void run(UUID u, String p, String code, Consumer<Program.Outcome> done) { waiting.put(p, done); }
            public void afterwards(String p, Consumer<Program.Outcome> done) { waiting.computeIfPresent(p, (k, old) -> done); }
            public void forget(String p) { waiting.remove(p); }
            public void interrupt(UUID u, String p, String why) { }
            public void cutOff(UUID u, String p, boolean body) { cut.add(p); }
            public void stopBody(UUID u) { throw new AssertionError("takeover must not stop body"); }
        };
        ScriptTool script = new ScriptTool();
        ToolBook tools = new ToolBook() {
            public List<NumenTool> all() { return List.of(script); }
            public NumenTool resolve(String name) { return name.equals("lua") ? script : null; }
        };
        return new CompanionLoop(new CompanionLoop.Parts(id, new Home(dir, io), Runnable::run, mind, programs, tools,
                () -> null, () -> null, () -> null, () -> false, driving::get, () -> 1,
                p -> null, () -> 0, (u, room) -> ""));
    }

    private LlmToolCall query(String id) { return new LlmToolCall(id, "lua", ScriptTool.args("return 1").toString()); }

    private void finish(String id) {
        Consumer<Program.Outcome> done = waiting.remove(id);
        assertNotNull(done);
        done.accept(new Program.Outcome("{\"message\":\"ok\",\"isError\":false}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null));
    }

    @Test void firstQueryBeforeNextTickKeepsItsCallbackAndDoesNotBlockLaterQueries(@TempDir Path dir) {
        CompanionLoop loop = loop(dir);
        driving.set(true); // MCP initialized after the previous server tick.
        List<String> answers = new ArrayList<>();
        assertTrue(loop.runExternal(query("first"), answers::add));
        loop.tick();
        assertTrue(cut.isEmpty(), "the first external query is not old internal work");
        finish("first");
        assertEquals(1, answers.size());
        assertTrue(loop.runExternal(query("second"), answers::add));
        finish("second");
        assertEquals(2, answers.size());
    }

    @Test void ordinaryTickTakeoverStillLeavesSubsequentExternalWorkAlone(@TempDir Path dir) {
        CompanionLoop loop = loop(dir);
        driving.set(true);
        loop.tick();
        List<String> answers = new ArrayList<>();
        assertTrue(loop.runExternal(query("normal"), answers::add));
        loop.tick(); loop.tick();
        assertTrue(cut.isEmpty());
        finish("normal");
        assertEquals(1, answers.size());
    }
}
