package com.tablegame.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.tablegame.TableGame;
import com.tablegame.drawboard.Cells;
import com.tablegame.script.Interp;

/**
 * 对局落盘。**位置** {@code <世界>/tablegame/tables.json}（一个世界一份；不像 ProfileStore 的「一人一档」—— 台子本来只有一两份，分文件只多出「哪几个文件是同一份」的账）。
 * 写走「tmp + ATOMIC_MOVE」原子替换（同 ProfileStore / BoardStore）：崩服不留半个文件。
 * **值的宇宙**（数 / 文本 / 真假 / 列表）→ JSON number / string / true|false / array 一一对应；认不出的形状当文本存（宁可存得丑，别静默丢）。
 */
public final class HostStore {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** 这份落盘文件的格式版本（将来要迁移时认它；现在只有 1）。 */
    public static final int FORMAT = 1;

    private final Path file;                                   // <世界>/tablegame/tables.json

    public HostStore(Path serverDir) {
        this.file = serverDir.resolve("tablegame").resolve("tables.json");
    }

    public Path file() {
        return file;
    }

    /** 读整份。缺 / 读坏 / 根本不是对象 → 空对象（宿主当「没存过」继续，不抬掉服务端）。 */
    public JsonObject load() {
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            TableGame.LOGGER.error("[对局] 读 {} 失败（按没存过继续）：{}", file, e.toString());
            return new JsonObject();
        }
    }

    /** 原子写整份。 */
    public void save(JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, PRETTY.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TableGame.LOGGER.error("[对局] 写 {} 失败：{}", file, e.toString());
        }
    }

    /** 撤掉整份（没有任何台 / 局要记时；不存在 = 什么都不做）。 */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            TableGame.LOGGER.error("[对局] 删 {} 失败：{}", file, e.toString());
        }
    }

    // ============================================================ 值 ↔ JSON

    /** 引擎值 → JSON（数 / 文本 / 真假 / 列表）。 */
    public static JsonElement valueJson(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object o : l) a.add(valueJson(o));
            return a;
        }
        // 记录（rec()）：「按人存的东西」脚本都自己拼成记录 ⇒ 值里会出现 Map。
        // 不认它的话会掉进下面那条兜底（当文本存），读回来是个字符串 —— 快照往返就断了。
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) o.add(String.valueOf(e.getKey()), valueJson(e.getValue()));
            return o;
        }
        return new JsonPrimitive(String.valueOf(v));           // 认不出的形状：当文本存，不丢
    }

    /** JSON → 引擎值。**数字一律回 Double**（引擎内部就是 Double，见 {@code Builtins}）。 */
    public static Object valueOf(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) out.add(valueOf(x));
            return out;
        }
        if (e.isJsonObject()) {                                // 记录（rec()）：键 → 值，同上
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                out.put(x.getKey(), valueOf(x.getValue()));
            }
            return out;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        return null;
    }

    // ============================================================ 局内画板（32×32）↔ JSON

    /**
     * 画板像素 → JSON：一行一个十六进制串（每像素 8 位，{@link Cells#toHex8}）—— 与画板项目的盘上格式**同形**，两边能互相搬。
     * **整块板全空 → null**（绝大多数局没画过东西，不写这段省几 KB）。ponytail: 画满的板一次约 8 KB，每 5 秒落一次；真嫌大再改「只存非空格」。
     */
    public static JsonObject boardJson(int[] px, int w, int h) {
        boolean any = false;
        for (int p : px) {
            if (p != 0) { any = true; break; }
        }
        if (!any) return null;
        JsonObject o = new JsonObject();
        o.addProperty("width", w);
        o.addProperty("height", h);
        JsonArray rows = new JsonArray();
        for (int y = 0; y < h; y++) {
            StringBuilder sb = new StringBuilder(w * 8);
            for (int x = 0; x < w; x++) sb.append(Cells.toHex8(px[y * w + x]));
            rows.add(sb.toString());
        }
        o.add("rows", rows);
        return o;
    }

    /** JSON → 画板像素；形状 / 行数 / 行长对不上 → null（调用方当「没存过」，不抛）。 */
    public static int[] boardPixels(JsonObject o, int w, int h) {
        if (o == null || !o.has("rows") || !o.get("rows").isJsonArray()) return null;
        JsonArray rows = o.getAsJsonArray("rows");
        if (rows.size() != h) return null;
        int[] out = new int[w * h];
        try {
            for (int y = 0; y < h; y++) {
                String row = rows.get(y).getAsString();
                if (row.length() < w * 8) return null;
                for (int x = 0; x < w; x++) {
                    out[y * w + x] = Cells.fromHex8(row.substring(x * 8, (x + 1) * 8));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    private static List<String> strList(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        return out;
    }

    // ============================================================ 引擎快照 ↔ JSON

    /**
     * 快照 → JSON。字段名按 {@link Interp.Snapshot} 的分量一对应（时间全是**相对量**，见它的注释）。
     *
     * <p>⚠ 这个形状是**盘上格式**：改字段名 = 旧档读不回来 ⇒ 得同时抬 {@link #FORMAT} 并写迁移，
     * 或者干脆让它读不回来（对局态本来就可以丢：读不回 = 从头开一局，不会崩服）。
     */
    public static JsonObject snapJson(Interp.Snapshot s) {
        JsonObject o = new JsonObject();
        JsonObject vars = new JsonObject();
        for (Map.Entry<String, Object> e : s.vars().entrySet()) vars.add(e.getKey(), valueJson(e.getValue()));
        o.add("vars", vars);
        JsonArray scopes = new JsonArray();
        for (Map<String, Object> m : s.scopes()) {
            JsonObject j = new JsonObject();
            for (Map.Entry<String, Object> v : m.entrySet()) j.add(v.getKey(), valueJson(v.getValue()));
            scopes.add(j);
        }
        o.add("scopes", scopes);
        JsonArray stack = new JsonArray();
        for (Interp.Snapshot.FramePos p : s.stack()) {          // 存的是栈顶 → 栈底（引擎侧 apply 会倒回去）
            JsonObject f = new JsonObject();
            f.addProperty("block", p.block());
            f.addProperty("ip", p.ip());
            f.addProperty("scope", p.ownScope());
            stack.add(f);
        }
        o.add("stack", stack);
        o.addProperty("stage", s.stage());
        o.addProperty("stageElapsed", s.stageElapsedSec());
        o.addProperty("stageSec", s.stageSec());
        o.addProperty("timeoutFired", s.timeoutFired());
        o.addProperty("waiting", s.waiting());
        o.addProperty("waitLeft", s.waitLeftSec());
        o.addProperty("hideSeq", s.hideSeq());
        o.addProperty("curFull", s.curFull());                 // null → JsonNull（Gson 自己处理）
        o.addProperty("curHud", s.curHud());
        o.addProperty("drawer", s.drawer());
        // 每人**单独**指定的当前画布（show(谁, "名")）—— 不落盘的话重进存档就退回全局那份
        JsonObject fullPer = new JsonObject();
        for (Map.Entry<String, String> e : s.fullPer().entrySet()) fullPer.addProperty(e.getKey(), e.getValue());
        o.add("fullPer", fullPer);
        JsonObject hudPer = new JsonObject();
        for (Map.Entry<String, String> e : s.hudPer().entrySet()) hudPer.addProperty(e.getKey(), e.getValue());
        o.add("hudPer", hudPer);
        JsonObject every = new JsonObject();
        for (Map.Entry<String, Double> e : s.everyElapsedSec().entrySet()) every.addProperty(e.getKey(), e.getValue());
        o.add("every", every);
        return o;
    }

    /** JSON → 快照（缺字段 / 形状不对用默认值，不抛：读坏一份档不该抬掉启动）。 */
    public static Interp.Snapshot snapOf(JsonObject o) {
        if (o == null) return null;
        Map<String, Object> vars = new LinkedHashMap<>();
        if (o.has("vars") && o.get("vars").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("vars").entrySet()) {
                vars.put(e.getKey(), valueOf(e.getValue()));
            }
        }
        List<Map<String, Object>> scopes = new ArrayList<>();
        if (o.has("scopes") && o.get("scopes").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("scopes")) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (e.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> v : e.getAsJsonObject().entrySet()) {
                        m.put(v.getKey(), valueOf(v.getValue()));
                    }
                }
                scopes.add(m);
            }
        }
        List<Interp.Snapshot.FramePos> stack = new ArrayList<>();
        if (o.has("stack") && o.get("stack").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("stack")) {
                JsonObject f = e.getAsJsonObject();
                stack.add(new Interp.Snapshot.FramePos(num(f, "block", 0).intValue(),
                        num(f, "ip", 0).intValue(), bool(f, "scope", false)));
            }
        }
        Map<String, Double> every = new LinkedHashMap<>();
        if (o.has("every") && o.get("every").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("every").entrySet()) {
                every.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
        return new Interp.Snapshot(vars, scopes, stack,
                str(o, "stage", ""), num(o, "stageElapsed", 0).doubleValue(),
                num(o, "stageSec", -1).doubleValue(), bool(o, "timeoutFired", false),
                bool(o, "waiting", false), num(o, "waitLeft", 0).doubleValue(),
                num(o, "hideSeq", 0).intValue(), str(o, "curFull", null), str(o, "curHud", null),
                str(o, "drawer", ""), strMap(o.get("fullPer")), strMap(o.get("hudPer")), every);
    }

        /** JSON 对象 → 字符串表（缺 / 形状不对 = 空表：老档没这两个键也照样读得回来）。 */
    private static Map<String, String> strMap(JsonElement e) {
        Map<String, String> out = new LinkedHashMap<>();
        if (e != null && e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> v : e.getAsJsonObject().entrySet()) {
                out.put(v.getKey(), v.getValue().isJsonNull() ? null : v.getValue().getAsString());
            }
        }
        return out;
    }

private static Number num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsNumber() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        return e.isJsonPrimitive() ? e.getAsString() : def;
    }
}
