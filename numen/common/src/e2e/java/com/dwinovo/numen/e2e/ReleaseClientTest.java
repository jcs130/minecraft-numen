package com.dwinovo.numen.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 官方发布协议的真实 Paper 连接;二进制布局另用原发布 JAR 的 codec 核对。 */
class ReleaseClientTest extends Scenario {
    static final String ROSTER = "numen_api:companion_list", RESULT = "numen_api:program_result";
    static final String[] CHANNELS = {ROSTER, RESULT, "numen_api:client_ui_action", "numen_api:numen_event",
            "numen_api:current_task", "numen_api:client_call", "numen_api:consent_request", "numen_api:numen_state",
            "numen_api:numen_locations", "numen_api:numen_death", "numen_api:numen_respawn", "numen_api:fragment_to_client", "numen_api:path_debug"};

    @BeforeEach void paper() { assumeTrue("paper".equals(System.getProperty("e2e.platform"))); }

    @Test void officialReleaseOpensSettingsSummonsAndReadsOnlyItsOwnCompanions() throws Exception {
        Bot owner = legacy("rel_gui");
        owner.command("numen settings");
        until(() -> !owner.payloads("numen_api:client_ui_action").isEmpty(), "settings UI action");
        assertArrayEquals(new byte[]{0}, owner.payloads("numen_api:client_ui_action").getFirst());
        UUID body = summonPacket(owner, "RelPet");
        console("give RelPet minecraft:diamond 3");
        String before = console("data get entity RelPet Inventory");
        owner.payload("numen_api:request_state", uuid(body));
        until(() -> !owner.payloads("numen_api:numen_state").isEmpty(), "inventory snapshot");
        assertTrue(owner.payloads("numen_api:numen_state").getLast()[16] != 0);

        Bot other = legacy("rel_other");
        other.payload("numen_api:request_state", uuid(body));
        until(() -> !other.payloads("numen_api:numen_state").isEmpty(), "foreign inventory refusal");
        assertEquals(0, other.payloads("numen_api:numen_state").getLast()[16]);
        run(other, body, "foreign-program", "return numen.status.self()");
        assertTrue(result(other, "foreign-program").get("receipt").getAsString().contains("不是你的同伴"));
        assertEquals(before, console("data get entity RelPet Inventory"));
        assertTrue(owner.payloads("numen_api:hello_ack").isEmpty());
        assertTrue(owner.payloads("numen_api:feed").isEmpty());
        owner.command("numen player despawn RelPet");
        until(() -> !online("RelPet"), "dismiss test companion");
    }

    @Test void legacyLuaUsesTheNativeBodyExecutorAndNeverReexecutesACompletedId() throws Exception {
        Bot owner = legacy("rel_lua");
        UUID body = summonPacket(owner, "RelLua");
        console("give RelLua minecraft:cobblestone 6");
        run(owner, body, "read-self", "return numen.status.self()");
        assertEquals("ok", result(owner, "read-self").get("status").getAsString());
        run(owner, body, "answer-42", "print('native-receipt'); return 42");
        JsonObject first = result(owner, "answer-42");
        assertTrue(first.get("receipt").getAsString().contains("42"));
        int count = owner.payloads(RESULT).size();
        run(owner, body, "answer-42", "print('native-receipt'); return 42");
        until(() -> owner.payloads(RESULT).size() > count, "cached receipt");
        assertEquals(first, result(owner, "answer-42"));
        run(owner, body, "answer-42", "return 999");
        until(() -> owner.payloads(RESULT).stream().anyMatch(b -> reply(b).id.equals("answer-42")
                && reply(b).json.toString().contains("其他内容")), "changed duplicate refusal");
        assertTrue(console("data get entity RelLua Inventory").contains("cobblestone"));
        owner.command("numen player despawn RelLua");
        until(() -> !online("RelLua"), "dismiss test companion");
    }

    @Test void privateWordsAreDeliveredToTheClientDriverAndNotToTheServerModel() throws Exception {
        Bot owner = legacy("rel_chat");
        UUID body = summonPacket(owner, "RelChat");
        owner.command("numen companion RelChat profile " + SPEC.modelProfile());
        int mark = owner.mark();
        owner.command("msg RelChat legacy-private-test");
        until(() -> owner.payloads("numen_api:numen_event").stream()
                .anyMatch(b -> new String(b, StandardCharsets.UTF_8).contains("legacy-private-test")), "private event");
        owner.command("numen permission mode RelChat");
        owner.await(mark, m -> m.text().contains("RelChat"), WAIT, "ordered command response");
        assertFalse(MODEL.wasToldAbout("legacy-private-test"), "server model must not also process the client's words");
        run(owner, body, "chat-read", "return numen.status.self()");
        assertEquals("ok", result(owner, "chat-read").get("status").getAsString());
        owner.command("numen player despawn RelChat");
        until(() -> !online("RelChat"), "dismiss test companion");
    }

    @Test void clientCallbackAndStopWorkThroughTheRealOwnerConnection() throws Exception {
        Bot owner = legacy("rel_callback");
        UUID body = summonPacket(owner, "RelCall");
        run(owner, body, "client-help", "return numen.api.help('my.client_only')");
        until(() -> !owner.payloads("numen_api:client_call").isEmpty(), "native reverse request");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(owner.payloads("numen_api:client_call").getLast()));
        UUID target = readUuid(in); String call = readText(in); String function = readText(in); readText(in);
        assertEquals(body, target); assertEquals("numen.api.help", function); assertEquals(0, in.available());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, call); writeText(out, "{\"ok\":true,\"value\":\"official client help\"}");
        out.writeBoolean(false);
        owner.payload("numen_api:client_call_result", bytes.toByteArray());
        assertTrue(result(owner, "client-help").get("receipt").getAsString().contains("official client help"));

        run(owner, body, "client-stop", "return numen.api.help('my.wait_for_stop')");
        until(() -> owner.payloads("numen_api:client_call").size() == 2, "waiting client call");
        bytes.reset(); out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, "client-stop"); out.writeBoolean(true); out.writeBoolean(true); writeText(out, "owner stop");
        owner.payload("numen_api:stop_program", bytes.toByteArray());
        assertNotEquals("ok", result(owner, "client-stop").get("status").getAsString());
        run(owner, body, "after-stop", "return 17");
        assertTrue(result(owner, "after-stop").get("receipt").getAsString().contains("17"));
        owner.command("numen player despawn RelCall");
        until(() -> !online("RelCall"), "dismiss test companion");
    }

    @Test void rejoiningKeepsTheBodyInventoryAndCompletedReceipts() throws Exception {
        Bot owner = legacy("rel_rejoin");
        UUID body = summonPacket(owner, "RelRejoin");
        console("give RelRejoin minecraft:emerald 5");
        run(owner, body, "before-rejoin", "return 23");
        JsonObject receipt = result(owner, "before-rejoin");
        String before = console("data get entity RelRejoin Inventory");
        owner.close();
        until(() -> !online("rel_rejoin"), "owner leaves");
        Bot again = legacy("rel_rejoin");
        until(() -> online("RelRejoin"), "same companion restored");
        until(() -> roster(again).stream().anyMatch(e -> e.id.equals(body)), "same UUID on roster");
        assertEquals(before, console("data get entity RelRejoin Inventory"));
        run(again, body, "before-rejoin", "return 23");
        assertEquals(receipt, result(again, "before-rejoin"));
        again.command("numen player despawn RelRejoin");
        until(() -> !online("RelRejoin"), "dismiss test companion");
    }

    @Test void permissionReplyIsOwnerBoundAndAnApprovedDropIsNotRepeated() throws Exception {
        Bot owner = legacy("rel_consent");
        UUID body = summonPacket(owner, "RelAsk");
        console("give RelAsk minecraft:diamond 1");
        run(owner, body, "one-drop", "numen.inv.drop('minecraft:diamond', {count=1})");
        until(() -> !owner.payloads("numen_api:consent_request").isEmpty(), "native permission request");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(owner.payloads("numen_api:consent_request").getFirst()));
        assertEquals(body, readUuid(in)); long consent = readVarLong(in); assertTrue(consent > 0);
        Bot other = legacy("rel_untrusted");
        consent(other, body, consent, 0);
        int mark = other.mark(); other.command("numen permission rules list");
        other.await(mark, m -> m.text().contains("deny:"), WAIT, "foreign reply processed");
        assertTrue(console("data get entity RelAsk Inventory").contains("diamond"));
        assertTrue(owner.payloads(RESULT).isEmpty(), "another owner must not approve the drop");
        consent(owner, body, consent, 0);
        JsonObject first = result(owner, "one-drop");
        assertEquals("ok", first.get("status").getAsString(), first.toString());
        assertFalse(console("data get entity RelAsk Inventory").contains("diamond"));
        int count = owner.payloads(RESULT).size();
        run(owner, body, "one-drop", "numen.inv.drop('minecraft:diamond', {count=1})");
        until(() -> owner.payloads(RESULT).size() > count, "drop cached receipt");
        assertEquals(first, result(owner, "one-drop"));
        owner.command("numen player despawn RelAsk");
        until(() -> !online("RelAsk"), "dismiss test companion");
    }

    @Test void missingModulesAreRequestedBeforeExecutingAnyStatement() throws Exception {
        Bot owner = legacy("rel_modules");
        UUID body = summonPacket(owner, "RelModules");
        console("give RelModules minecraft:diamond 1");
        String module = "local M = {}; function M.value() return 91 end; return M";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(module.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        String code = "numen.inv.drop('minecraft:diamond', {count=1}); return my.test.value()";
        runModules(owner, body, "modules-program", code, "my.test", hash, null);
        assertEquals(hash, result(owner, "modules-program").getAsJsonArray("missing").get(0).getAsString());
        assertTrue(owner.payloads("numen_api:consent_request").isEmpty(), "no statement may run before modules arrive");
        assertTrue(console("data get entity RelModules Inventory").contains("diamond"));
        int replies = owner.payloads(RESULT).size();
        runModules(owner, body, "modules-program", code, "my.test", hash, module);
        until(() -> !owner.payloads("numen_api:consent_request").isEmpty(), "drop starts only after module sync");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(owner.payloads("numen_api:consent_request").getFirst()));
        readUuid(in); consent(owner, body, readVarLong(in), 0);
        until(() -> owner.payloads(RESULT).size() > replies, "module program receipt");
        JsonObject done = result(owner, "modules-program");
        assertFalse(done.has("missing"));
        assertTrue(done.get("receipt").getAsString().contains("91"), done.toString());
        owner.command("numen player despawn RelModules");
        until(() -> !online("RelModules"), "dismiss test companion");
    }

    @Test void disconnectSettlesTheOldProgramAndLateRepliesCannotCompleteANewOne() throws Exception {
        Bot owner = legacy("rel_disconnect");
        UUID body = summonPacket(owner, "RelDisconnect");
        run(owner, body, "disconnected-program", "return numen.api.help('old')");
        until(() -> !owner.payloads("numen_api:client_call").isEmpty(), "old callback");
        String oldCall = callId(owner.payloads("numen_api:client_call").getLast());
        owner.close(); until(() -> !online("rel_disconnect"), "owner disconnects");
        Bot again = legacy("rel_disconnect"); until(() -> online("RelDisconnect"), "same body restored");
        run(again, body, "disconnected-program", "return numen.api.help('old')");
        assertNotEquals("ok", result(again, "disconnected-program").get("status").getAsString());
        assertTrue(again.payloads("numen_api:client_call").isEmpty(), "old unknown program is not restarted");
        run(again, body, "new-program", "return numen.api.help('new')");
        until(() -> !again.payloads("numen_api:client_call").isEmpty(), "new callback");
        String newCall = callId(again.payloads("numen_api:client_call").getLast());
        assertNotEquals(oldCall, newCall);
        callback(again, body, oldCall, "late old reply");
        int mark = again.mark(); again.command("numen permission rules list");
        again.await(mark, m -> m.text().contains("deny:"), WAIT, "late reply processed");
        assertFalse(again.payloads(RESULT).stream().map(ReleaseClientTest::reply).anyMatch(r -> r.id.equals("new-program")));
        callback(again, body, newCall, "new reply");
        assertTrue(result(again, "new-program").get("receipt").getAsString().contains("new reply"));
        again.command("numen player despawn RelDisconnect");
        until(() -> !online("RelDisconnect"), "dismiss test companion");
    }

    @Test void deathAndRespawnKeepTheRosterIdentityAndDoNotRunAnEarlyProgram() throws Exception {
        Bot owner = legacy("rel_life"); UUID body = summonPacket(owner, "RelLife");
        console("gamerule keepInventory true"); console("give RelLife minecraft:emerald 2");
        String before = console("data get entity RelLife Inventory");
        console("kill RelLife");
        until(() -> !owner.payloads("numen_api:numen_death").isEmpty(), "real death event");
        assertTrue(roster(owner).stream().anyMatch(row -> row.id.equals(body)));
        run(owner, body, "while-dead", "return 47");
        assertNotEquals("ok", result(owner, "while-dead").get("status").getAsString());
        until(() -> !owner.payloads("numen_api:numen_respawn").isEmpty(), "real respawn event");
        assertTrue(roster(owner).stream().anyMatch(row -> row.id.equals(body)));
        assertEquals(before, console("data get entity RelLife Inventory"));
        run(owner, body, "after-respawn", "return 48");
        assertTrue(result(owner, "after-respawn").get("receipt").getAsString().contains("48"));
        owner.command("numen player despawn RelLife"); until(() -> !online("RelLife"), "dismiss test companion");
    }

    private static void consent(Bot owner, UUID body, long id, int decision) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeVarLong(out, id); writeVarInt(out, decision); writeText(out, "");
        owner.payload("numen_api:consent_reply", bytes.toByteArray());
    }

    @Test void aLongFragmentedProgramRunsAndMalformedSummonsHaveNoEffect() throws Exception {
        Bot owner = legacy("rel_fragment"); UUID body = summonPacket(owner, "RelFragment");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, "fragmented"); writeText(out, "--" + "x".repeat(70_000) + "\nreturn 65");
        writeVarInt(out, 0); writeVarInt(out, 0);
        byte[] whole = bytes.toByteArray(); int size = 32_767 - 128, count = (whole.length + size - 1) / size;
        for(int i=0;i<count;i++){
            bytes.reset(); out = new DataOutputStream(bytes);
            writeVarInt(out, 741); writeVarInt(out, i); writeVarInt(out, count);
            if(i==0) writeText(out, "numen_api:run_program");
            int n=Math.min(size, whole.length-i*size); writeVarInt(out, n); out.write(whole,i*size,n);
            owner.payload("numen_api:fragment_to_server", bytes.toByteArray());
        }
        assertTrue(result(owner, "fragmented").get("receipt").getAsString().contains("65"));
        bytes.reset(); out=new DataOutputStream(bytes); writeText(out,"MustNotSpawn"); writeText(out,""); writeText(out,"");
        out.writeBoolean(false); out.writeByte(77);
        int mark=owner.mark(); owner.payload("numen_api:summon_request", bytes.toByteArray());
        owner.await(mark,m->m.text().contains("本次没有执行"),WAIT,"malformed packet guidance");
        assertFalse(online("MustNotSpawn")); assertTrue(owner.connected());
        owner.command("numen player despawn RelFragment"); until(()->!online("RelFragment"),"dismiss test companion");
    }

    @Test void walkingUsesTheNativeTaskAndReportsTheActualArrival() throws Exception {
        Bot owner=legacy("rel_walk"); UUID body=summonPacket(owner,"RelWalk");
        var numbers=java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?[dD]").matcher(console("data get entity RelWalk Pos"));
        double[] pos=new double[3]; for(int i=0;i<3;i++){assertTrue(numbers.find());String n=numbers.group();pos[i]=Double.parseDouble(n.substring(0,n.length()-1));}
        int x=(int)Math.floor(pos[0])+4,y=(int)Math.floor(pos[1]),z=(int)Math.floor(pos[2]);
        console("execute at RelWalk run fill ~-2 ~-1 ~-2 ~8 ~-1 ~2 minecraft:stone");
        owner.command("numen path");
        run(owner,body,"walk-real-body","numen.move.to({x="+x+",y="+y+",z="+z+"}); return numen.status.self()");
        JsonObject done=result(owner,"walk-real-body");
        assertEquals("ok",done.get("status").getAsString(),done.toString());
        var arrived=java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)d").matcher(console("data get entity RelWalk Pos[0]"));
        assertTrue(arrived.find()); assertEquals(x+.5,Double.parseDouble(arrived.group(1)),1.0);
        assertFalse(owner.payloads("numen_api:current_task").isEmpty());
        assertFalse(owner.payloads("numen_api:path_debug").isEmpty(),"the official path overlay channel received nothing");
        assertTrue(owner.payloads("numen:path_debug").isEmpty(),"release client received the renamed modern channel");
        owner.command("numen player despawn RelWalk"); until(()->!online("RelWalk"),"dismiss test companion");
    }

    @Test void aSkinChangeKeepsTheOriginalBodyAndInventory() throws Exception {
        Bot owner=legacy("rel_skin"); UUID body=summonPacket(owner,"RelSkin");
        console("give RelSkin minecraft:diamond 3");
        String before=console("data get entity RelSkin Inventory");
        int messages=owner.mark();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        writeUuid(out,body); writeText(out,java.util.Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(StandardCharsets.UTF_8))); writeText(out,"");
        owner.payload("numen_api:change_skin",bytes.toByteArray());
        owner.await(messages,m->m.text().contains("RelSkin") && m.text().contains("joined"),WAIT,"body re-admitted after skin change");
        assertEquals(body,roster(owner).stream().filter(e->e.name.equals("RelSkin")).findFirst().orElseThrow().id);
        assertEquals(before,console("data get entity RelSkin Inventory"));
        run(owner,body,"skin-original-body","return numen.status.self()");
        assertEquals("ok",result(owner,"skin-original-body").get("status").getAsString());
        owner.command("numen player despawn RelSkin"); until(()->!online("RelSkin"),"dismiss test companion");
    }
    private static String callId(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); readUuid(in); return readText(in);
    }
    private static void callback(Bot owner, UUID body, String call, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, call); JsonObject value = new JsonObject(); value.addProperty("ok", true); value.addProperty("value", text);
        writeText(out, value.toString()); out.writeBoolean(false); owner.payload("numen_api:client_call_result", bytes.toByteArray());
    }
    private static void runModules(Bot owner, UUID body, String id, String code, String name, String hash, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, id); writeText(out, code); writeVarInt(out, 1); writeText(out, name); writeText(out, hash);
        writeVarInt(out, text == null ? 0 : 1); if(text != null){writeText(out, hash); writeText(out, text);}
        owner.payload("numen_api:run_program", bytes.toByteArray());
    }

    private Bot legacy(String name) throws Exception {
        Bot owner = join(name, "en_us");
        owner.payload("minecraft:register", String.join("\0", CHANNELS).getBytes(StandardCharsets.UTF_8));
        until(() -> !owner.payloads(ROSTER).isEmpty(), "legacy roster");
        return owner;
    }
    private UUID summonPacket(Bot owner, String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeText(out, name); writeText(out, ""); writeText(out, ""); out.writeBoolean(false);
        owner.payload("numen_api:summon_request", bytes.toByteArray());
        until(() -> roster(owner).stream().anyMatch(e -> e.name.equals(name)), "legacy summon " + name);
        return roster(owner).stream().filter(e -> e.name.equals(name)).findFirst().orElseThrow().id;
    }
    private static void run(Bot owner, UUID body, String id, String code) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeUuid(out, body); writeText(out, id); writeText(out, code); writeVarInt(out, 0); writeVarInt(out, 0);
        owner.payload("numen_api:run_program", bytes.toByteArray());
    }
    record Reply(UUID body, String id, JsonObject json) {}
    private static Reply reply(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            Reply result = new Reply(readUuid(in), readText(in), JsonParser.parseString(readText(in)).getAsJsonObject());
            assertEquals(0, in.available()); return result;
        } catch (IOException e) { throw new AssertionError(e); }
    }
    private static JsonObject result(Bot owner, String id) throws Exception {
        until(() -> owner.payloads(RESULT).stream().map(ReleaseClientTest::reply).anyMatch(e -> e.id.equals(id)), "receipt " + id);
        return owner.payloads(RESULT).stream().map(ReleaseClientTest::reply).filter(e -> e.id.equals(id)).reduce((a, b) -> b).orElseThrow().json;
    }
    record Row(UUID id, String name) {}
    private static List<Row> roster(Bot owner) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(owner.payloads(ROSTER).getLast()));
            readText(in); int size = readVarInt(in); java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
            for (int i = 0; i < size; i++) { rows.add(new Row(readUuid(in), readText(in))); readVarLong(in); in.readBoolean(); }
            assertEquals(0, in.available(), "legacy roster has unread fields"); return rows;
        } catch (IOException e) { throw new AssertionError(e); }
    }
    private static byte[] uuid(UUID id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); writeUuid(new DataOutputStream(bytes), id); return bytes.toByteArray();
    }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static void writeText(DataOutputStream out, String text) throws IOException { byte[] data = text.getBytes(StandardCharsets.UTF_8); writeVarInt(out, data.length); out.write(data); }
    private static String readText(DataInputStream in) throws IOException { return new String(in.readNBytes(readVarInt(in)), StandardCharsets.UTF_8); }
    private static void writeVarInt(DataOutputStream out, int n) throws IOException { do { int b = n & 127; n >>>= 7; out.writeByte(n == 0 ? b : b | 128); } while(n != 0); }
    private static void writeVarLong(DataOutputStream out, long n) throws IOException { do { int b = (int)n & 127; n >>>= 7; out.writeByte(n == 0 ? b : b | 128); } while(n != 0); }
    private static int readVarInt(DataInputStream in) throws IOException { long n = readVarLong(in); return (int)n; }
    private static long readVarLong(DataInputStream in) throws IOException { long n=0; for(int i=0;i<10;i++){ int b=in.readUnsignedByte(); n|=(long)(b&127)<<(i*7); if((b&128)==0)return n; } throw new IOException("bad VarLong"); }
    private static void until(BooleanSupplier condition, String what) throws Exception {
        long deadline=System.nanoTime()+WAIT.toNanos();
        while(!condition.getAsBoolean()){ assertTrue(System.nanoTime()<deadline, "timed out: "+what); Thread.sleep(25); }
    }
    private static boolean online(String name) { try { return console("list").contains(name); } catch(IOException e) { throw new AssertionError(e); } }
}
