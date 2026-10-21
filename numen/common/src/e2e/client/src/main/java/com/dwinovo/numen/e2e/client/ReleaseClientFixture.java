package com.dwinovo.numen.e2e.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 测试辅助模组只点原发布客户端的入口;Numen 原 JAR 和协议均不修改。 */
public final class ReleaseClientFixture implements ClientModInitializer {
    private final long started=System.nanoTime();
    private final Path output=Path.of(System.getProperty("numen.test.output"));
    private int stage,ticks;
    private boolean hidden;
    private UUID companion;
    private Object loop;
    private final String body=System.getProperty("numen.test.body","RelNative");

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        try {
            if(!hidden){ GLFW.glfwHideWindow(mc.getWindow().getWindow()); hidden=true; }
            if(System.nanoTime()-started>180_000_000_000L) throw new IllegalStateException("native client fixture timed out at stage "+stage);
            if(stage==0 && mc.screen instanceof TitleScreen){
                String address=System.getProperty("numen.test.server");
                ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),
                        new ServerData("Numen release test",address,ServerData.Type.OTHER),false,null);
                stage=1;
            } else if(stage==1 && mc.player!=null && mc.level!=null){
                mc.player.connection.sendCommand("numen settings"); stage=2; ticks=0;
            } else if(stage==2 && mc.screen!=null && mc.screen.getClass().getName().equals("com.dwinovo.numen.client.screen.NumenScreen")){
                if(++ticks<5)return;
                Files.createDirectories(output);
                try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){ image.writeToFile(output.resolve("official-settings.png")); }
                Files.writeString(output.resolve("settings-opened.txt"),mc.screen.getClass().getName());
                mc.setScreen(null);
                Class<?> type=Class.forName("com.dwinovo.numen.network.payload.SummonRequestPayload");
                Object payload=type.getConstructor(String.class,String.class,String.class,boolean.class)
                        .newInstance(body,"","",false);
                Class.forName("com.dwinovo.numen.network.NumenNetwork")
                        .getMethod("sendToServer",CustomPacketPayload.class).invoke(null,payload);
                stage=3;
            } else if(stage==3){
                Class<?> rosterType=Class.forName("com.dwinovo.numen.client.agent.NumenRoster");
                Object roster=rosterType.getMethod("instance").invoke(null);
                List<?> rows=(List<?>)rosterType.getMethod("entries").invoke(roster);
                for(Object row:rows){
                    if(!body.equals(row.getClass().getMethod("name").invoke(row)))continue;
                    companion=(UUID)row.getClass().getMethod("uuid").invoke(row);
                    loop=Class.forName("com.dwinovo.numen.client.agent.AgentLoopRegistry")
                            .getMethod("getOrCreate",UUID.class).invoke(null,companion);
                    Files.writeString(output.resolve("companion-found.txt"),companion.toString());
                    stage=4; break;
                }
            } else if(stage==4 && Files.isRegularFile(output.resolve("server-prepared.txt"))){
                loop.getClass().getMethod("setProviderEntry",String.class).invoke(loop,"fixture-model");
                loop.getClass().getMethod("submitPrompt",String.class).invoke(loop,"native-release-smoke");
                Files.writeString(output.resolve("prompt-submitted.txt"),companion.toString());
                stage=5; ticks=0;
            } else if(stage==5 && Files.isRegularFile(output.resolve("server-observed.txt"))){
                if(++ticks<20)return;
                try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){ image.writeToFile(output.resolve("official-world.png")); }
                Files.writeString(output.resolve("passed.txt"),"settings, summon, native client model and server body execution verified");
                mc.player.connection.sendCommand("numen player despawn "+body);
                stage=6; ticks=0;
            } else if(stage==6 && ++ticks>20){
                stage=7; mc.stop();
            }
        } catch(Throwable failure){
            try { Files.createDirectories(output); java.io.StringWriter text=new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(text)); Files.writeString(output.resolve("failed.txt"),text.toString()); }
            catch(Exception ignored){ failure.printStackTrace(); }
            stage=7; mc.stop();
        }
    }
}
