package com.tablegame.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家档案：跨局持久的「按玩家存的键值」（常驻玩法的金钱 / 等级存这里，掉线重连、重启都还在）。
 * **位置** {@code games/<游戏>/players/<uuid>.json} —— **属于这款游戏**（同一玩家在《钻石大陆》的等级 ≠ 在《21点》的等级）。
 * 跨游戏通用数据（全局金钱 / 总等级）**没有地方放，也不做**（YAGNI：真需要时再单开一处）。
 * 只在服务端线程调；脚本里仍写 {@code profile_get(actor, "level")} —— **语言侧零改动**，文件名的 UUID 由宿主从席位换过去。
 */
public final class ProfileStore {
    private static final Gson PRETTY = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    private final Path gamesDir;                                          // <serverDir>/tablegame/games
    /** 内存账：「游戏/uuid」→（键 → 值）；读到哪一位就缓存哪一位。 */
    private final Map<String, Map<String, String>> cache = new LinkedHashMap<>();

    public ProfileStore(Path serverDir) {
        this.gamesDir = serverDir.resolve("tablegame").resolve("games");
    }

    /** 一位玩家在这款游戏里的档案文件。 */
    private Path fileOf(String game, String uuid) {
        return gamesDir.resolve(BoardStore.safeName(game)).resolve("players")
                .resolve(BoardStore.safeName(uuid) + ".json");
    }

    /**
     * 取一个键；没存过 → 空串。
     *
     * @param game 哪款游戏（档案按游戏分家）
     * @param uuid 哪位玩家（文件名用的就是它；宿主拿名字换）
     */
    public String get(String game, String uuid, String key) {
        return values(game, uuid).getOrDefault(key, "");
    }

    /** 存一个键（**立刻落盘**；值空串 = 删键，删光了文件也撤掉）。 */
    public void set(String game, String uuid, String key, String value) {
        if (game.isEmpty() || uuid.isEmpty() || key.isEmpty()) return;
        Map<String, String> keys = values(game, uuid);
        if (value.isEmpty()) keys.remove(key);
        else keys.put(key, value);
        save(game, uuid, keys);
    }

    /** 懒读那一位的档案（缺 / 读坏 = 空账继续，不掐局）。 */
    private Map<String, String> values(String game, String uuid) {
        String k = game + "/" + uuid;
        Map<String, String> hit = cache.get(k);
        if (hit != null) return hit;
        Map<String, String> keys = new LinkedHashMap<>();
        Path f = fileOf(game, uuid);
        if (Files.isRegularFile(f)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.entrySet()) keys.put(e.getKey(), e.getValue().getAsString());
            } catch (Exception e) {
                TableGame.LOGGER.error("[档案] 读档失败 {}（按空账继续）: {}", f, e.toString());
            }
        }
        cache.put(k, keys);
        return keys;
    }

    /** 原子写那一位的档案（键空了 → 撤文件）。 */
    private void save(String game, String uuid, Map<String, String> keys) {
        Path f = fileOf(game, uuid);
        try {
            if (keys.isEmpty()) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            for (var e : keys.entrySet()) root.addProperty(e.getKey(), e.getValue());
            Files.writeString(tmpOf(f), PRETTY.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmpOf(f), f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TableGame.LOGGER.error("[档案] 写档失败 {}: {}", f, e.toString());
        }
    }

    private static Path tmpOf(Path f) {
        return f.resolveSibling(f.getFileName().toString() + ".tmp");
    }
}
