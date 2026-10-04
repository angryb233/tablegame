package com.tablegame.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.tablegame.TableGame;

/**
 * 世界玩法的**存档级绑定**：这个存档认哪一款游戏项目。
 * ⚠ 别与 {@code HostStore}（{@code <游戏目录>/tablegame/tables.json}）混：那个是「台选了什么 / 哪几局在跑」，住**游戏目录**、**所有存档共用**；绑定随存档 —— 分工是故意的。
 * 内容就一行 {@code {"format":1,"game":"钻石大陆"}}，可读可手改；写走 tmp + {@code ATOMIC_MOVE}（崩服不留半个文件）。
 * 读不到 / 读坏 / 不是对象 → 一律当「没开世界玩法」继续，绝不抬掉服务端。纯逻辑 ⇒ 进自检（写 → 读 → 读坏 → 撤四下）。
 */
public final class WorldGameStore {
    /** 这份文件的格式版本（将来要迁移时认它；现在只有 1）。 */
    public static final int FORMAT = 1;

    private static final String DIR = "tablegame";
    private static final String NAME = "world.json";

    private final Path file;                                   // <存档>/data/tablegame/world.json

    /** @param worldDataDir 存档的 data 目录（{@code getWorldPath(LevelResource.DATA)}） */
    public WorldGameStore(Path worldDataDir) {
        this.file = worldDataDir.resolve(DIR).resolve(NAME);
    }

    public Path file() {
        return file;
    }

    /** 这个存档绑的是哪款（没开过 / 读坏 → 空串 = 「没开世界玩法」）。 */
    public String game() {
        if (!Files.isRegularFile(file)) return "";
        try {
            var e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return "";
            var g = e.getAsJsonObject().get("game");
            return g != null && g.isJsonPrimitive() ? g.getAsString() : "";
        } catch (Exception e) {
            TableGame.LOGGER.error("[世界玩法] 读 {} 失败（按没开过继续）：{}", file, e.toString());
            return "";
        }
    }

    /** 绑一款（原子写）。 */
    public void set(String game) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("game", game == null ? "" : game);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TableGame.LOGGER.error("[世界玩法] 写 {} 失败：{}", file, e.toString());
        }
    }

    /** 关掉（撤掉整份；本来就没有 = 什么都不做）。 */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            TableGame.LOGGER.error("[世界玩法] 撤 {} 失败：{}", file, e.toString());
        }
    }
}
