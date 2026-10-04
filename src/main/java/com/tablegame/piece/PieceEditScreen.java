package com.tablegame.piece;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.pack.AssetStore;
import com.tablegame.host.ClientGameHandler;

/**
 * 单棋子编辑页：显示名（重命名）/ 蓝图选择 / 属性字段表单。
 * 属性字段与 {@link CardEditScreen} 同款：数字/文本/布尔/枚举循环按钮，类型从值定型（枚举存 fieldDefs，元数据不持久化）。
 * 蓝图循环按钮（◂ key ▸）每帧 pull 读候选缓存 {@link ClientGameHandler#blueprints()}，到了自动可选，未到显示「加载中」（仍可填名字/属性）。
 * 行变化（加属性）时整页 init 重建，EditBox 值先收进草稿再重建（数据在草稿不在控件）。
 */
public class PieceEditScreen extends Screen implements EditorToolScreen {
    /** 字段行类型（与卡牌同款循环）。 */
    private enum FType { NUM, TEXT, BOOL, ENUM;
        public FType next() { return values()[(ordinal() + 1) % values().length]; }
        public String label() { return switch (this) { case NUM -> "数字"; case TEXT -> "文本"; case BOOL -> "布尔"; case ENUM -> "枚举"; }; }
        public static FType of(String key, JsonObject fields, JsonObject fieldDefs) {
            if (fieldDefs != null && fieldDefs.has(key)) return ENUM;
            var v = fields == null ? null : fields.get(key);
            if (v == null || !v.isJsonPrimitive()) return TEXT;
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isNumber()) return NUM;
            if (p.isBoolean()) return BOOL;
            return TEXT;
        }
    }

    /** 一行属性的草稿（init 重建时控件重建、草稿保留）。 */
    private static class Row {
        public String key = "", value = "";
        public FType t = FType.TEXT;
    }

    /** 编完 / 取消回哪一屏（对象页）。 */
    private final Screen backTo;
    /** 背后的玩法编辑器（读写 def、脚本唯一出口 applyScript 都从它走）。 */
    private final GameEditorScreen editor;

    /** 交出背后的编辑器（{@link EditorToolScreen}）—— 不实现它保存回执会落到「新开编辑器」那支，把人弹回总览。 */
    @Override
    public GameEditorScreen editor() {
        return editor;
    }
    private final int index;                 // 恒 >= 0（空棋子已在列表页建好）
    private final String pieceId;            // 声明名（= place_piece 的引用名）

    // 草稿（init 重建不丢）
    private String name, blueprint;
    private String scaleText = "";   // 尺寸倍率（自由数字框；空 = 1 = 蓝图原尺寸）
    private int mode = 0;          // 0 = 编辑表单；1 = 蓝图候选列表
    private final List<Row> rows = new ArrayList<>();

    // 控件当帧引用
    private EditBox nameBox;
    private int rowTop;                        // 属性行滚动
    private int rowFits = 4;
    private DrawBoardMenuUi.WrappedButton bpBtn;
    private EditBox bpScaleBox;    // 尺寸（自由数字框）
    private final List<EditBox> keyBoxes = new ArrayList<>();
    private final List<EditBox> valBoxes = new ArrayList<>();
    private final List<DrawBoardMenuUi.WrappedButton> typeBtns = new ArrayList<>();

    public PieceEditScreen(Screen backTo, GameEditorScreen editor, int idx) {
        super(Component.literal("编辑棋子"));
        this.backTo = backTo;
        this.editor = editor;
        this.index = idx;
        GameDefinition.PieceDef pc = editor.def.pieces().get(idx);
        this.pieceId = pc.id();
        this.name = pc.name();
        this.blueprint = pc.blueprint();
        // 尺寸倍率回填：1 = 不显示（＝蓝图原尺寸），其他值原样填回框里
        this.scaleText = pc.scale() > 0 && pc.scale() != 1.0 ? String.valueOf(pc.scale()) : "";
        // 已存 fields/fieldDefs 展开成草稿行（与卡牌同款）
        if (pc.fields() != null) {
            for (var e : pc.fields().entrySet()) {
                Row r = new Row();
                r.key = e.getKey();
                r.t = FType.of(e.getKey(), pc.fields(), pc.fieldDefs());
                r.value = e.getValue().isJsonNull() ? "" : e.getValue().getAsString();
                rows.add(r);
            }
        }
        if (pc.fieldDefs() != null) {
            for (var e : pc.fieldDefs().entrySet()) {
                Row r = new Row();
                r.key = e.getKey();
                r.t = FType.ENUM;
                List<String> opts = new ArrayList<>();
                e.getValue().getAsJsonArray().forEach(el -> opts.add(el.getAsString()));
                r.value = String.join(",", opts);
                rows.add(r);
            }
        }
        ClientGameHandler.requestBlueprints();   // 候选列表异步拉取（有缓存则不发包）
    }

    @Override
    protected void init() {
        clearWidgets();
        keyBoxes.clear(); valBoxes.clear(); typeBtns.clear();
        // 每次打开都重拉候选蓝图（防抖在 ClientGameHandler 里，同帧重复调用只发一次）。
        ClientGameHandler.requestBlueprints();
        if (mode == 1) {              // 蓝图候选列表：点一条就回填
            initBlueprintList();
            return;
        }
        int cx = width / 2;
        int y = 46;

        // 显示名（重命名）+ 蓝图循环选择
        nameBox = new EditBox(font, cx - 140, y, 130, 18, Component.literal("显示名"));
        nameBox.setMaxLength(40);
        nameBox.setValue(name);
        nameBox.setHint(Component.literal("显示名（可空）"));
        nameBox.setResponder(s -> name = s.trim());
        addRenderableWidget(nameBox);
        bpBtn = new DrawBoardMenuUi.WrappedButton(cx + 0, y, 90, 18, blueprintLabel(), null);
        bpBtn.setOnPress(() -> { mode = 1; init(); });   // 点开候选列表自选
        addRenderableWidget(bpBtn);
        // 尺寸（自由数字框）：乘在蓝图 scale 上，值越小棋子越大（0.25 = 放大 4 倍、2 = 缩小一半）。空 = 1。
        bpScaleBox = new EditBox(font, cx + 94, y, 46, 18, Component.literal("尺寸"));
        bpScaleBox.setMaxLength(12);
        bpScaleBox.setValue(scaleText);
        bpScaleBox.setHint(Component.literal("尺寸"));
        bpScaleBox.setResponder(s -> scaleText = s.trim());
        addRenderableWidget(bpScaleBox);
        y += 24;

        // 属性字段行（与卡牌同款：键 / 类型循环 / 值），行数不限，滚轮翻
        rowFits = Math.max(1, (height - 92 - y) / 22);
        rowTop = Math.max(0, Math.min(rowTop, Math.max(0, rows.size() - rowFits)));
        for (int kk = 0; kk < Math.min(rowFits, rows.size() - rowTop); kk++) {
            int i = rowTop + kk;
            Row r = rows.get(i);
            EditBox k = new EditBox(font, cx - 140, y, 70, 18, Component.literal("键"));
            k.setMaxLength(24);
            k.setValue(r.key);
            k.setResponder(s -> r.key = s);
            addRenderableWidget(k);
            keyBoxes.add(k);
            DrawBoardMenuUi.WrappedButton tb = new DrawBoardMenuUi.WrappedButton(cx - 66, y, 60, 18, r.t.label(), null);
            int ri = i;
            tb.setOnPress(() -> {
                rows.get(ri).t = rows.get(ri).t.next();
                tb.setMessage(Component.literal(rows.get(ri).t.label()));
                tb.setFocused(false);
            });
            addRenderableWidget(tb);
            typeBtns.add(tb);
            EditBox v = new EditBox(font, cx - 2, y, 138, 18, Component.literal("值"));
            v.setMaxLength(120);
            v.setValue(r.value);
            v.setHint(Component.literal(r.t == FType.ENUM ? "可选值,逗号分隔" : "值"));
            v.setResponder(s -> r.value = s);
            addRenderableWidget(v);
            valBoxes.add(v);
            y += 22;
        }

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 64, 196, 20, "+ 添加属性字段", this::addField));
        // 这枚棋子 → `place_piece("p1", x, y, z)` 一行（坐标 = 我脚下这格），只产片段
        addRenderableWidget(DrawBoardMenuUi.button(cx + 60, height - 64, 80, 20, "摆到脚下", this::genPlace));
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 38, 136, 24, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, height - 38, 136, 24, "取消", this::back));
    }

    /** 蓝图按钮标签：没选 = 「点选蓝图」；选了就显示当前 key。 */
    private String blueprintLabel() {
        if (blueprint.isEmpty()) return "点选蓝图（未选）";
        return "点选蓝图（当前：" + DrawBoardMenuUi.ellipsis(blueprint, 16) + "）";
    }

    /**
     * 蓝图候选列表（mode=1）：把服务端回的 {@code 组/名} 全列成按钮，点一条回填并回表单（每次进页重拉）。
     * <b>ponytail</b>：候选一次全列（几十个以内够用，放不下先截断）；要滚动 / 缩略图预览时再升级成列表屏。
     */
    private void initBlueprintList() {
        int cx = width / 2;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, 16, 280, 18, "◂ 返回棋子编辑", () -> { mode = 0; init(); }));
        List<String> cands = ClientGameHandler.blueprints();
        if (cands == null) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, 44, 280, 18, "候选加载中…（再点一次会刷新）", () -> {}));
            return;
        }
        if (cands.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, 44, 280, 18,
                    "（库中没有蓝图 —— 先去模型制作器「保存蓝图」）", () -> {}));
            return;
        }
        int y = 44;
        for (String c : cands) {
            if (y > height - 30) break;                        // 放不下就先截断
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 18,
                    (c.equals(blueprint) ? "✔ " : "") + c, () -> {
                        blueprint = c;
                        mode = 0;
                        init();
                    }));
            y += 20;
        }
        // 本项目导入的「模型美术」（组件库资产）：写成 "@资产名"，宿主 place_piece 时用 assetOf 查本项目 assets 段（零新协议）。
        var modelArt = editor.def.assets().stream()
                .filter(a -> com.tablegame.editor.pack.AssetStore.KIND_MODEL.equals(a.kind()))
                .toList();
        if (!modelArt.isEmpty()) {
            if (y <= height - 30) {
                addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 18,
                        "—— 本项目组件库（模型美术）——", () -> {}));
                y += 20;
            }
            for (var a : modelArt) {
                if (y > height - 30) break;
                String key = "@" + a.name();
                addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 18,
                        (key.equals(blueprint) ? "✔ " : "") + key, () -> {
                            blueprint = key;
                            mode = 0;
                            init();
                        }));
                y += 20;
            }
        }
    }

    /** 加一行属性（加到就滚到那一行）。 */
    private void addField() {
        rows.add(new Row());
        rowTop = Math.max(0, rows.size() - 1);
        clearWidgets();
        init();
    }

    /** 草稿 → 脚本里的 piece 声明（真源是脚本）。成功回列表。 */
    private void commit() {
        String text = editor.def.script();
        if (ScriptEdit.declOf(text, "piece", pieceId) == null) {
            DrawBoardMenuUi.msg("[棋子] 找不到这枚棋子的声明（回列表重进）");
            return;
        }
        // 尺寸校验（信任边界 = 玩家输入）：空 = 1 = 蓝图原尺寸；范围 1/32 ~ 64（再小顶到 16 格、再大看不见）。
        double mul = 1.0;
        String st = scaleText.trim();
        if (!st.isEmpty()) {
            try {
                mul = Double.parseDouble(st);
            } catch (NumberFormatException e) {
                DrawBoardMenuUi.msg("[游戏] 尺寸要填数字（如 1 = 原尺寸、0.25 = 放大 4 倍、2 = 缩小一半）");
                return;
            }
            if (mul <= 0 || mul < 1.0 / 32 || mul > 64) {
                DrawBoardMenuUi.msg("[游戏] 尺寸要在 1/32 ~ 64 之间（0.25 = 放大 4 倍、越大越小）");
                return;
            }
        }
        // ① 结构键：蓝图 / 显示名 / 尺寸（1 = 原尺寸 → 不写这段）
        text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, "blueprint",
                blueprint.isBlank() ? "" : ScriptEdit.strCode(blueprint)));
        if (text == null) return;
        text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, "name",
                name.isBlank() ? "" : ScriptEdit.strCode(name)));
        if (text == null) return;
        text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, "scale",
                mul == 1.0 ? "" : (mul == Math.floor(mul) ? String.valueOf((long) mul) : String.valueOf(mul))));
        if (text == null) return;
        // ② 属性行（草稿就是全部）
        Set<String> kept = new HashSet<>();
        for (Row r : rows) {
            String k = r.key.trim();
            if (k.isEmpty() || k.equals("blueprint") || k.equals("name") || k.equals("scale")) continue;
            if (r.t == FType.ENUM) {
                List<String> opts = new ArrayList<>();
                for (String opt : r.value.split(",")) if (!opt.trim().isEmpty()) opts.add(opt.trim());
                text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, ScriptEdit.optionsKey(k),
                        opts.isEmpty() ? "" : ScriptEdit.listCode(opts)));
                if (text == null) return;
                kept.add(k);
                continue;
            }
            String src;
            switch (r.t) {
                case NUM -> {
                    String v = r.value.trim();
                    try { Double.parseDouble(v); } catch (NumberFormatException e) {
                        DrawBoardMenuUi.msg("[游戏] 字段 " + k + " 不是数字");
                        return;
                    }
                    src = v;
                }
                case BOOL -> src = Boolean.parseBoolean(r.value.trim()) ? "true" : "false";
                default -> src = ScriptEdit.strCode(r.value);
            }
            text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, k, src));
            if (text == null) return;
            kept.add(k);
        }
        // ③ 声明里还有、草稿里没有的属性 → 删掉
        ScriptEdit.Decl cur = ScriptEdit.declOf(text, "piece", pieceId);
        if (cur != null) {
            for (ScriptEdit.Field f : cur.fields()) {
                if (kept.contains(f.key()) || f.key().equals("blueprint")
                        || f.key().equals("name") || f.key().equals("scale")) continue;
                text = step(text, ScriptEdit.setDeclField(text, "piece", pieceId, f.key(), ""));
                if (text == null) return;
            }
        }
        String err = editor.applyScript(text);
        if (!err.isEmpty()) {
            DrawBoardMenuUi.msg("[棋子] " + err);
            return;
        }
        DrawBoardMenuUi.msg("[棋子] 已存进脚本：" + pieceId);
        back();
    }

    /** 落地一步；被拒收 → 提示并返回 null，调用方收手（不留半份改动）。 */
    private String step(String text, ScriptEdit.Result r) {
        if (r.text().equals(text)) {
            DrawBoardMenuUi.msg("[棋子] " + r.note());
            return null;
        }
        return r.text();
    }

    private void back() {
        Minecraft.getInstance().setScreen(backTo);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (rows.size() > rowFits) {                                 // 行多到放不下：滚轮翻一屏
            rowTop = Math.max(0, Math.min(rows.size() - rowFits, rowTop + (dy > 0 ? -1 : 1)));
            clearWidgets();
            init();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** 把「这枚棋子摆到我脚下这格」写进 on start（没有就新建）。 */
    private void genPlace() {
        var pl = Minecraft.getInstance().player;
        if (pl == null) {
            DrawBoardMenuUi.msg("[棋子] 找不到玩家，摆不了");
            return;
        }
        var bp = pl.blockPosition();
        ScriptEdit.Result r = ScriptEdit.addPlacePiece(editor.def.script(), pieceId,
                bp.getX(), bp.getY(), bp.getZ());
        String err = editor.applyScript(r.text());
        DrawBoardMenuUi.msg("[棋子] " + (err.isEmpty() ? r.note() : err));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Component.literal("编辑棋子 · " + editor.def.pieces().get(index).id()),
                width / 2, 30, 0xFFFFFFFF);
        g.text(font, "蓝图可空（先声明后配置）；候选列表含本项目导入的模型美术（@资产名）；类型点按钮循环：数字/文本/布尔/枚举(可选值,逗号分隔)",
                width / 2 - 140, height - 58, 0xFF909090);
    }
}
