package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.network.ReleasePackets;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.script.ScriptCatalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个旧客户端连接的模块快照。只存内存,不写进服务端主人的模块库。 */
public final class ReleaseModules implements ScriptCatalog.ModuleSource {
    private static final int MAX_ENTRIES = 2048;
    private final Map<String, String> cache = new LinkedHashMap<>();
    private volatile Map<String, String> manifest = Map.of();

    /** 先验证整批再提交;坏指纹、非法模块名或超预算不能部分污染缓存。 */
    public synchronized List<String> accept(ReleasePackets.Modules modules) {
        if (modules.manifest().size() > MAX_ENTRIES || modules.bodies().size() > MAX_ENTRIES)
            throw new IllegalArgumentException("模块清单过大，请拆分模块。");
        for (var item : modules.manifest().entrySet()) {
            if (Modules.problem(item.getKey()) != null || !item.getValue().matches("[0-9a-f]{12}"))
                throw new IllegalArgumentException("模块名或内容指纹无效。");
        }
        for (var item : modules.bodies().entrySet()) {
            if (!Fingerprint.of(item.getValue()).equals(item.getKey()))
                throw new IllegalArgumentException("模块内容与指纹不一致，请重新同步。");
        }
        Map<String, String> next = new LinkedHashMap<>(cache);
        next.putAll(modules.bodies());
        // 只保留当前清单引用的正文;内存预算不随历史模块版本增长。
        next.keySet().retainAll(modules.manifest().values());
        long bytes = next.values().stream().mapToLong(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
        if (bytes > Wire.MESSAGE_BYTES / 2 || next.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("模块正文超过同步预算，请减少本次模块数量。");
        cache.clear();
        cache.putAll(next);
        manifest = Map.copyOf(modules.manifest());
        List<String> missing = new ArrayList<>();
        for (String hash : manifest.values()) if (!cache.containsKey(hash) && !missing.contains(hash)) missing.add(hash);
        return List.copyOf(missing);
    }

    @Override public synchronized String code(String name) { return cache.get(manifest.get(name)); }
    @Override public List<String> names() { return manifest.keySet().stream().sorted().toList(); }
}
