package com.tablegame.editor.card;

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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.host.ClientGameHandler;

    /**
     * 单卡编辑页：id / 卡面画板引用（实时预览）/ 卡背 / 属性字段表单。
     *
     * <p>属性字段 = 三类 + 枚举：数字存 number、文本 string、布尔 boolean（循环按钮切换，存值时定型）；
     * 枚举用「逗号分隔可选值」存进 {@code fieldDefs}（类型信息只有枚举需要持久化）。
     * 卡面预览走 CardWidget pull 管线：输入变化 → setFaceArt + requestFace，像素到了自动显示。
     * 行变化（加字段）时整页 {@link #init} 重建 —— EditBox 值先收进 {@link #draft} 再重建，
     * 免得重建吞掉正在输入的内容（26.x EditBox 不持久状态）。
     */
public class CardEditScreen extends Screen implements EditorToolScreen {
    /** 字段行类型（循环切换用）。 */
    private enum FType { NUM, TEXT, BOOL, ENUM;
        public FType next() { return values()[(ordinal() + 1) % values().length]; }
        public String label() { return switch (this) { case NUM -> "数字"; case TEXT -> "文本"; case BOOL -> "布尔"; case ENUM -> "枚举"; }; }
        public static FType of(String key, JsonObject fields, JsonObject fieldDefs) {
            if (fieldDefs != null && fieldDefs.has(key)) return ENUM;
            var v = fields == null ? null : fields.get(key);
            if (v == null || !v.isJsonPrimitive()) return TEXT;
            // Gson 判型走 primitive 层：number/boolean 有独立类型，其余当文本
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isNumber()) return NUM;
            if (p.isBoolean()) return BOOL;
            return TEXT;
        }
    }

    /** 一行字段的草稿（init 重建时控件重建、草稿保留——数据在草稿不在控件）。 */
    private static class Row {
        public String key = "", value = "";
        public FType t = FType.TEXT;
    }

    /** 编完 / 取消回哪一屏 = 对象页。 */
    private final Screen backTo;
    /** 背后的玩法编辑器（读写 def、脚本唯一出口 applyScript 都从它走）。 */
    private final GameEditorScreen editor;

    /** 交出背后的编辑器（{@link EditorToolScreen}）：不实现它，保存回执会落到「都没开着 → 新开编辑器」
     *  那一支，症状是被弹回总览。 */
    @Override
    public GameEditorScreen editor() {
        return editor;
    }
    private final int index;                 // -1 = 新建（完成时追加）
    private String back;

    private EditBox idBox, artBox, nameBox;
    private int rowTop;                        // 属性行滚动：第一个可见行（#18 放开字段上限）
    private int rowFits = 4;                   // 一屏放得下几行（init 里按窗口算）
    private CardWidget preview;
    private String id, art;
    /** 显示名（脚本段里的 {@code name "红桃A"}）；空串 = 没写 ⇒ 显示口退回 id。 */
    private String name = "";
    private final List<Row> rows = new ArrayList<>();
    /** 每行控件的当帧引用（render/点击时读草稿用；init 里重建）。 */
    private final List<EditBox> keyBoxes = new ArrayList<>();
    private final List<EditBox> valBoxes = new ArrayList<>();
    private final List<DrawBoardMenuUi.WrappedButton> typeBtns = new ArrayList<>();

    /** 从对象页打开已有卡（idx）或新建（-1，完成时追加）。 */
    public CardEditScreen(Screen backTo, GameEditorScreen editor, int idx) {
        super(Component.literal("编辑卡牌"));
        this.backTo = backTo;
        this.editor = editor;
        this.index = idx;
        if (idx >= 0) {
            GameDefinition.CardDef c = editor.def.cards().get(idx);
            this.id = c.id(); this.art = c.art(); this.back = c.back();
            this.name = c.name() == null ? "" : c.name();       // 显示名（老档 / 老声明没写 = 空串）
            // 把已存的 fields/fieldDefs 展开成草稿行
            if (c.fields() != null) {
                for (var e : c.fields().entrySet()) {
                    Row r = new Row();
                    r.key = e.getKey();
                    r.t = FType.of(e.getKey(), c.fields(), c.fieldDefs());
                    r.value = e.getValue().isJsonNull() ? "" : e.getValue().getAsString();
                    rows.add(r);
                }
            }
            if (c.fieldDefs() != null) {
                for (var e : c.fieldDefs().entrySet()) {
                    Row r = new Row();
                    r.key = e.getKey();
                    r.t = FType.ENUM;
                    List<String> opts = new ArrayList<>();
                    e.getValue().getAsJsonArray().forEach(el -> opts.add(el.getAsString()));
                    r.value = String.join(",", opts);
                    rows.add(r);
                }
            }
        } else {
            this.id = ""; this.art = ""; this.back = "blue";
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        keyBoxes.clear(); valBoxes.clear(); typeBtns.clear();
        int cx = width / 2;
        int y = 46;

        // 第一行：资产名（脚本里引用它的名字）· 显示名（界面上显示的名字）—— 两栏各 130 宽。
        // 资产名 = 段头，能改：改名走 ScriptEdit.renameDecl（段头 + 脚本里的引用一起改）。
        // 显示名 = 段里的 name，留空 = 没写（显示口退回资产名）。
        idBox = new EditBox(font, cx - 140, y, 130, 18, Component.literal("资产名"));
        idBox.setHint(Component.literal("资产名（脚本里引用它）"));
        idBox.setMaxLength(40);
        idBox.setValue(id);
        // 资产名在这儿改（不再 setEditable(false)），提交时走 renameDecl 连引用一起改
        addRenderableWidget(idBox);
        nameBox = new EditBox(font, cx + 10, y, 130, 18, Component.literal("显示名"));
        nameBox.setMaxLength(32);
        nameBox.setValue(name);
        nameBox.setHint(Component.literal("显示名（留空 = 资产名）"));
        nameBox.setResponder(s -> name = s);          // 输入即更新标题（不落盘，完成时才写）
        addRenderableWidget(nameBox);
        y += 24;
        artBox = new EditBox(font, cx - 140, y, 130, 18, Component.literal("卡面"));
        artBox.setMaxLength(80);
        artBox.setValue(art);
        artBox.setHint(Component.literal("组/项目名"));
        // 输入即存草稿；检索/预览改由「应用」按钮触发（点一下才发包，不逐字符刷包）
        artBox.setResponder(s -> art = s.trim());
        addRenderableWidget(artBox);
        addRenderableWidget(DrawBoardMenuUi.button(cx + 10, y, 130, 18, "应用卡面", () -> applyFace()));
        y += 24;

        // 卡面预览 + 卡背引用行（卡背框 130 宽 + 70 宽「应用卡背」按钮，与上行对齐）
        preview = new CardWidget(cx - 140, y, 1);
        preview.setFaceArt(art);
        preview.setBackArt(back);
        if (!art.isBlank()) ClientGameHandler.requestFace(art);
        if (!back.isBlank()) ClientGameHandler.requestFace(back);
        addRenderableWidget(preview);
        EditBox backBox = new EditBox(font, cx + 0, y + 4, 70, 18, Component.literal("卡背"));
        backBox.setMaxLength(80);
        backBox.setValue(back);
        backBox.setHint(Component.literal("组/项目名"));
        backBox.setResponder(s -> back = s.trim());
        addRenderableWidget(backBox);
        addRenderableWidget(DrawBoardMenuUi.button(cx + 74, y + 4, 66, 18, "应用卡背", () -> applyBack()));
        addRenderableWidget(DrawBoardMenuUi.label(cx + 0, y + 28, 140, "点预览卡翻面", 0xFF909090));
        y += CardBacks.CARD_H + 14;

        // 属性字段行（草稿 → 控件）。放不下的用滚轮翻（见 mouseScrolled）
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
            tb.setOnPress(() -> {   // 循环换类型：改草稿 + 换标签（AbstractWidget 持焦点会画白框，点击型控件无碍但顺手清掉）
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

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 64, 280, 20, "+ 添加属性字段", this::addField));
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 38, 136, 24, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, height - 38, 136, 24, "取消", this::back));
    }

    /** 加一行属性（加了就滚到它那一行）。 */
    private void addField() {
        rows.add(new Row());
        rowTop = Math.max(0, rows.size() - 1);
        clearWidgets();
        init();
    }

    /** 应用卡面：预览卡绑定输入框当前值 + 向服务端检索该画板像素（找不到回空包并提示）。 */
    private void applyFace() {
        preview.setFaceArt(art);
        if (!art.isBlank()) ClientGameHandler.requestFace(art);
    }

    /** 应用卡背：同上；空值 = 回落默认 blue。 */
    private void applyBack() {
        preview.setBackArt(back);
        if (!back.isBlank()) ClientGameHandler.requestFace(back);
    }

    /** 草稿 → 脚本里的 card 声明（真源是脚本）。成功回列表。 */
    private void commit() {
        String text = editor.def.script();
        if (id.isEmpty() || ScriptEdit.declOf(text, "card", id) == null) {
            DrawBoardMenuUi.msg("[卡牌] 找不到这张卡的声明（新建请回列表用「＋ 添加卡牌」）");
            return;
        }
        // ① 资产名改过 → 先改名（走 ScriptEdit.renameDecl，段头 + 脚本里的引用一起改；
        //    被拒收（重名 / 保留字）就原样停在这，一个字不写）
        String nwId = idBox == null ? id : idBox.getValue().trim();
        if (nwId.isEmpty()) {
            DrawBoardMenuUi.msg("[卡牌] 资产名不能为空");
            return;
        }
        if (!nwId.equals(id)) {
            ScriptEdit.Result rr = ScriptEdit.renameDecl(text, "card", id, nwId);
            if (rr.text().equals(text)) {
                DrawBoardMenuUi.msg("[卡牌] " + rr.note());
                return;
            }
            text = rr.text();
            id = nwId;
        }
        // ② 显示名：写段里的 name（空 = 删掉这一段 ⇒ 显示口退回资产名）
        String nwName = nameBox == null ? name : nameBox.getValue().trim();
        if (!nwName.equals(name)) {
            text = step(text, ScriptEdit.setDeclField(text, "card", id, "name",
                    nwName.isEmpty() ? "" : ScriptEdit.strCode(nwName)));
            if (text == null) return;
            name = nwName;
        }
        // ③ 结构键：卡面 / 卡背（空 = 删掉这一段，回落默认）。
        // ⚠ 这两栏没变就不写：setDeclField 遇到「本来就没这一段」会原样退回；当成失败收手会连带丢掉后面几栏的改动。
        String wantArt = art.isBlank() ? "" : ScriptEdit.strCode(art);
        if (!wantArt.equals(declValue(text, "art"))) text = step(text, ScriptEdit.setDeclField(text, "card", id, "art", wantArt));
        if (text == null) return;
        String wantBack = back.isBlank() || back.equals("blue") ? "" : ScriptEdit.strCode(back);
        if (!wantBack.equals(declValue(text, "back"))) text = step(text, ScriptEdit.setDeclField(text, "card", id, "back", wantBack));
        if (text == null) return;
        // ④ 属性行：草稿就是全部（草稿里有就写，没有的第 ⑤ 步删）
        Set<String> kept = new HashSet<>();
        for (Row r : rows) {
            String k = r.key.trim();
            if (k.isEmpty() || k.equals("art") || k.equals("back") || k.equals("name")) continue;   // name 是显示名那栏
            if (r.t == FType.ENUM) {                              // 枚举 = 只有可选值那一段
                List<String> opts = new ArrayList<>();
                for (String opt : r.value.split(",")) if (!opt.trim().isEmpty()) opts.add(opt.trim());
                text = step(text, ScriptEdit.setDeclField(text, "card", id, ScriptEdit.optionsKey(k),
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
                        DrawBoardMenuUi.msg("[卡牌] 字段 " + k + " 不是数字");
                        return;
                    }
                    src = v;
                }
                case BOOL -> src = Boolean.parseBoolean(r.value.trim()) ? "true" : "false";
                default -> src = ScriptEdit.strCode(r.value);
            }
            text = step(text, ScriptEdit.setDeclField(text, "card", id, k, src));
            if (text == null) return;
            kept.add(k);
        }
        // ⑤ 声明里还有、草稿里没有的属性 → 删掉（草稿是权威）
        ScriptEdit.Decl cur = ScriptEdit.declOf(text, "card", id);
        if (cur != null) {
            for (ScriptEdit.Field f : cur.fields()) {
                if (kept.contains(f.key()) || f.key().equals("art") || f.key().equals("back")
                        || f.key().equals("name")) continue;                  // name = 显示名那栏（别当属性删）
                text = step(text, ScriptEdit.setDeclField(text, "card", id, f.key(), ""));
                if (text == null) return;
            }
        }
        String err = editor.applyScript(text);             // 唯一出口：真源解析 + 落盘
        if (!err.isEmpty()) {
            DrawBoardMenuUi.msg("[卡牌] " + err);
            return;
        }
        DrawBoardMenuUi.msg("[卡牌] 已存进脚本：" + id);
        back();
    }

    /** 当前声明里那一栏的源码原文（没有这一段 = 空串）—— 提交前拿它判要不要写。 */
    private String declValue(String text, String key) {
        ScriptEdit.Decl d = ScriptEdit.declOf(text, "card", id);
        if (d == null) return "";
        for (ScriptEdit.Field f : d.fields()) {
            if (f.key().equals(key)) return f.value() == null ? "" : f.value().trim();
        }
        return "";
    }

    /** 落地一步；被拒收（文本没变）→ 提示并返回 null，调用方收手（不留半份改动）。 */
    private String step(String text, ScriptEdit.Result r) {
        if (r.text().equals(text)) {
            DrawBoardMenuUi.msg("[卡牌] " + r.note());
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 点预览卡 = 翻面（像素没到显示卡背，到了自动变正面）
        double mx = event.x(), my = event.y();
        if (preview != null && mx >= preview.getX() && mx < preview.getX() + preview.getWidth()
                && my >= preview.getY() && my < preview.getY() + preview.getHeight()) {
            preview.toggleFace();
            return true;
        }
        return super.mouseClicked(event, doubled);
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
        g.centeredText(font, Component.literal((index >= 0 ? "编辑卡牌 · " : "新建卡牌 · ") + id
                + (name.isBlank() || name.equals(id) ? "" : " · 显示名：" + ColorText.mask(name))),
                width / 2, 30, 0xFFFFFFFF);
        g.text(font, "类型点按钮循环：数字=1.5 文本=黑桃 布尔=true/false 枚举=可选值,逗号分隔"
                        + (rows.size() > rowFits ? "　滚轮翻行 " + (rowTop + 1) + "-"
                                + Math.min(rows.size(), rowTop + rowFits) + " / " + rows.size() : ""),
                width / 2 - 140, height - 88, 0xFF909090);
        // 对账：这张卡被脚本哪几行用着 / 是否被舞台摆过（改 id、删卡前先看这条）
        java.util.List<Integer> refs = ScriptEdit.declRefs(editor.def.script(), "card", id);
        g.text(font, refs.isEmpty() ? "脚本里还没用到这张卡（card(\"…\") 一处都没有）"
                        : "脚本里 " + refs.size() + " 处在用：第 " + refs.stream().map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(", ")) + " 行",
                width / 2 - 140, height - 76, refs.isEmpty() ? 0xFF909090 : 0xFFE0C060);
    }
}
