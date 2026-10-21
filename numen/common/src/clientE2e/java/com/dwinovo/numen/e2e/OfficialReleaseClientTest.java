package com.dwinovo.numen.e2e;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 原发行客户端自己打开面板、调用模型、发送 Lua 和读取完成回执。 */
final class OfficialReleaseClientTest extends Scenario {
    @ParameterizedTest @ValueSource(strings = {"0.1.4", "0.1.4.1"})
    void nativeReleaseCompletesTheClientModelAndServerBodyRoundTrip(String version) throws Exception {
        String suffix = version.replace(".", "");
        String body = "NativeRel" + suffix;
        Path output = Path.of(System.getProperty("e2e.client.output"), suffix + "-" + System.currentTimeMillis());
        Files.createDirectories(output);
        Process process = new ProcessBuilder(System.getProperty("e2e.client.python"),
                System.getProperty("e2e.client.launcher"), "--java", System.getProperty("e2e.client.java"),
                "--cache", System.getProperty("e2e.client.cache"), "--official", System.getProperty("e2e.client.official"),
                "--fixture", System.getProperty("e2e.client.fixture"), "--version", version,
                "--output", output.toString(), "--server", SPEC.host() + ":" + SPEC.port(),
                "--model-port", Integer.toString(SPEC.modelPort()), "--body", body, "--owner", "NativeOwner" + suffix)
                .redirectErrorStream(true).redirectOutput(output.resolve("launcher.log").toFile()).start();
        try {
            waitFor(() -> Files.isRegularFile(output.resolve("companion-found.txt")), process, output);
            var matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?[dD]").matcher(console("data get entity " + body + " Pos"));
            double[] pos = new double[3];
            for (int i = 0; i < pos.length; i++) { assertTrue(matcher.find()); String n = matcher.group(); pos[i] = Double.parseDouble(n.substring(0, n.length() - 1)); }
            int x = (int)Math.floor(pos[0]) + 4, y = (int)Math.floor(pos[1]), z = (int)Math.floor(pos[2]);
            String floor = console("execute at " + body + " run fill ~-2 ~-1 ~-2 ~8 ~-1 ~2 minecraft:stone");
            assertFalse(floor.contains("not loaded"), floor);
            MODEL.whenHeard("native-release-smoke",
                    FakeModel.lua("numen.move.to({x=" + x + ",y=" + y + ",z=" + z + "}); return numen.status.self()"),
                    FakeModel.say("native release round trip complete"));
            Files.writeString(output.resolve("server-prepared.txt"), "ready");
            waitFor(() -> MODEL.asked().stream().anyMatch(a -> a.lastToolCallId() != null), process, output);
            var receipt = MODEL.asked().stream().filter(a -> a.lastToolCallId() != null).findFirst().orElseThrow();
            Files.writeString(output.resolve("model-roundtrip.json"), receipt.body());
            var messages = com.google.gson.JsonParser.parseString(receipt.body()).getAsJsonObject().getAsJsonArray("messages");
            String toolResult = java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(m -> m.get("role").getAsString().equals("tool"))
                    .reduce((a,b) -> b).orElseThrow().get("content").getAsString();
            assertTrue(toolResult.startsWith("ok ") && toolResult.contains("reached") && toolResult.contains("returned:"), toolResult);
            var arrived = Pattern.compile("(-?\\d+(?:\\.\\d+)?)d").matcher(console("data get entity " + body + " Pos[0]"));
            assertTrue(arrived.find()); assertEquals(x + .5, Double.parseDouble(arrived.group(1)), 1.0);
            Files.writeString(output.resolve("server-observed.txt"), "native task arrived and actual client returned its receipt to the model");
            waitFor(() -> Files.isRegularFile(output.resolve("passed.txt")), process, output);
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "client did not exit");
            assertEquals(0, process.exitValue(), Files.readString(output.resolve("launcher.log")));
            assertTrue(Files.size(output.resolve("official-settings.png")) > 1000);
            assertTrue(Files.size(output.resolve("official-world.png")) > 1000);
        } finally {
            process.descendants().forEach(child -> { if (child.isAlive()) child.destroy(); });
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
            console("numen player despawn " + body);
        }
    }

    private static void waitFor(BooleanSupplier condition, Process process, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(210).toNanos();
        while (!condition.getAsBoolean()) {
            assertFalse(Files.isRegularFile(output.resolve("failed.txt")), () -> read(output.resolve("failed.txt")));
            assertTrue(process.isAlive(), () -> "native client exited: " + read(output.resolve("launcher.log")));
            assertTrue(System.nanoTime() < deadline, () -> "native client timed out: " + read(output.resolve("game/logs/latest.log")));
            Thread.sleep(100);
        }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return e.toString(); } }
}
