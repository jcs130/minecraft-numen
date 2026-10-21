package com.dwinovo.numen.api.mcp.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandReceiptTest {
    @Test void originalCommandJsonTravelsSeparatelyFromTheAbbreviatedDisplay() {
        var command = new JsonObject();
        command.addProperty("command", "/mycli skills mine");
        var lines = new com.google.gson.JsonArray();
        String line = "MC_SKILL {\"description\":\"" + "技".repeat(12000) + "\"}";
        lines.add(line); command.add("output", lines); command.addProperty("result", 1);
        var envelope = new JsonObject(); envelope.addProperty("success", true);
        envelope.addProperty("message", "short Lua display"); envelope.add("commandReceipt", command);
        var response = McpServer.toolReceipt(envelope.toString(), "<events>real inbox</events>");
        assertEquals(line, response.getAsJsonObject("structuredContent").getAsJsonObject("commandReceipt")
                .getAsJsonArray("output").get(0).getAsString());
        assertEquals(2, response.getAsJsonArray("content").size());
        assertEquals("short Lua display", response.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test void plainTextAndOrdinaryLuaKeepTheirExistingContract() {
        assertFalse(McpServer.toolReceipt("plain result", null).has("structuredContent"));
        assertTrue(McpServer.toolReceipt("{\"success\":false,\"message\":\"refused\"}", null).get("isError").getAsBoolean());
    }
}
