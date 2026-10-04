package com.tablegame.editor.stage;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptCard;
import com.tablegame.script.edit.ScriptEdit;
import com.tablegame.script.edit.ScriptGraph;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.script.NodeEditScreen;

/**
 * 实体画面 → 右键组件 →「编辑组件」→ 本页：可视化编辑（版式同 StageCompScreen）。
 * 字段：资产名（只读）· 显示名 · 类型（文字 / 卡牌 / 物品，换的是声明动词）· 引用 base · 位置 x/y/z ·
 * 角度（三格都空 = 不写 rot = 面向观察者）· 尺寸 · 身份值 mark；事件一条一行（点它进事件编辑，行尾 ✕ 删）。
 * 真源永远是脚本：改完按【完成】才串成一次 {@link GameEditorScreen#applyScript} 写回；【取消】丢弃。
 */
public class EntityCompScreen extends Screen implements EditorToolScreen {

    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return back.editor();
    }

    /** 类型三颗（顺序 = 界面顺序；值 = 声明动词）。 */
    private static final String[] KINDS = { "text", "card", "item" };
    private static final String[] KIND_NAMES = { "文字", "卡牌", "物品" };

    private final EntityVisualScreen back;
    private final String scr;
    private final String asset;

    /** 现算的组件（脚本被改掉 = null）。 */
    private Ast.EntityComp comp;
    private String builtFrom = "";

    // —— 草稿（改了不一定马上写：点【完成】才落）——
    private String vLabel = "";
    private String vBase = "";
    private String vScale = "1";
    private String vMark = "";
    private String vx = "0", vy = "1.9", vz = "0";
    private String vrx = "", vry = "", vrz = "";
    private String kind = "text";

    private EditBox boxLabel, boxBase, boxScale, boxMark, boxX, boxY, boxZ, boxRx, boxRy, boxRz;
    private String status = "";
    private boolean statusBad;

    public EntityCompScreen(EntityVisualScreen back, String screen, String asset) {
        super(Component.literal("组件编辑"));
        this.back = back;
        this.scr = screen;
        this.asset = asset;
    }

    // ==================== 读 / 构建 ====================

    private void load(String src) {
        comp = findComp(src);
        if (comp == null) {
            return;
        }
        kind = comp.kind();
        vLabel = comp.label() == null ? "" : comp.label();
        vBase = ScriptEdit.literalOf(comp.base());        // 变量那种 base 显示为空（改一下才变回字面量）
        vScale = ScriptEdit.numText(comp.scale());
        vMark = comp.mark() == null ? "" : comp.mark();
        vx = ScriptEdit.numText(comp.ax());
        vy = ScriptEdit.numText(comp.ay());
        vz = ScriptEdit.numText(comp.az());
        vrx = comp.hasRot() ? ScriptEdit.numText(comp.rx()) : "";
        vry = comp.hasRot() ? ScriptEdit.numText(comp.ry()) : "";
        vrz = comp.hasRot() ? ScriptEdit.numText(comp.rz()) : "";
    }

    private Ast.EntityComp findComp(String src) {
        try {
            Ast.Screen s = Parser.parse(src).screens().get(scr);
            if (s != null) {
                for (Ast.EntityComp c : s.comps()) {
                    if (c.asset().equals(asset)) {
                        return c;
                    }
                }
            }
        } catch (Ast.ScriptError e) {
            return null;
        }
        return null;
    }

    @Override
    protected void init() {
        clearWidgets();
        String src = back.editor().def.script();
        if (comp == null || !src.equals(builtFrom)) {
            load(src);
            builtFrom = src;
        }
        // ---- 左栏（照【全屏界面组件】页：动作全进左栏）----
        int railBottom = EditorRail.buildGeneric(new String[0], null, t -> { }, this::addRenderableWidget);
        addRenderableWidget(DrawBoardMenuUi.button(4, railBottom + 6, EditorRail.W, 18, "返回", this::back));
        if (comp != null) {
            int byEvent = Math.min(height * 3 / 5, height - 88);
            addRenderableWidget(DrawBoardMenuUi.button(4, byEvent, EditorRail.W, 18, "＋ 事件", this::addEvent));
        }
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 46, EditorRail.W, 18, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 24, EditorRail.W, 18, "取消", this::back));

        int x = EditorRail.LEFT;
        int x2 = x + 320;
        int fw = Math.min(560, Math.max(220, width - x - 24));
        int y = 16;
        final int g = 24;

        if (comp == null) {
            addRenderableWidget(DrawBoardMenuUi.label(x, y, fw,
                    "脚本里找不到这个组件了（画面 " + scr + " / 组件 " + asset + "）—— 点【返回】回 3D 编辑页", 0xFFE0A060));
            return;
        }

        addRenderableWidget(DrawBoardMenuUi.label(x, y, fw, "组件 · 脚本第 " + comp.line() + " 行 · 在 screen "
                + scr + " 里", 0xFFFFFFFF));
        y += 18;

        // ---------- 资产名（只读：改名 = 删了重建，免得改漏引用处）| 显示名 ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 170, "资产名", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x2, y, 170, "显示名", 0xFFB0B0C0));
        y += 11;
        boxAssetRO(x, y, 170);
        boxLabel = field(x2, y, 170, "显示名", vLabel, 32);
        boxLabel.setHint(Component.literal("只给人看"));
        boxLabel.setResponder(s -> vLabel = s);
        y += g;

        // ---------- 类型（三颗，换的是声明动词）| 引用 base ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 170, "类型", 0xFF909090));
        int bw = 96, by = y + 11;
        for (int i = 0; i < KINDS.length; i++) {
            String k = KINDS[i];
            boolean on = k.equals(kind);
            addRenderableWidget(DrawBoardMenuUi.button(x + i * (bw + 4), by, bw, 18,
                    (on ? "● " : "") + KIND_NAMES[i], () -> switchKind(k)));
        }
        if (!kind.equals("text")) {
            addRenderableWidget(DrawBoardMenuUi.label(x2, y + 11, 170, "引用 base", 0xFFB0B0C0));
            boxBase = field(x2, y + 22, 170, "引用", vBase, 64);
            boxBase.setHint(Component.literal(kind.equals("card") ? "档里卡牌的资产名" : "原版物品 id / 资产名"));
            boxBase.setResponder(s -> vBase = s);
        }
        y += 11 + 18 + 10;

        // ---------- 位置 x / y / z ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 56, "x（右）", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x + 60, y, 56, "y（上）", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x + 120, y, 56, "z（前）", 0xFFB0B0C0));
        y += 11;
        boxX = numField(x, y, 56, "x", vx, s -> vx = s);
        boxY = numField(x + 60, y, 56, "y", vy, s -> vy = s);
        boxZ = numField(x + 120, y, 56, "z", vz, s -> vz = s);
        y += 16 + 10;

        // ---------- 角度（三格都空 = 不写 rot = 面向观察者 / 卡牌正面朝锚点）----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 300, "角度（绕 x / y / z；三格都空 = 面向观察者）",
                0xFFB0B0C0));
        y += 11;
        boxRx = numField(x, y, 56, "绕x", vrx, s -> vrx = s);
        boxRy = numField(x + 60, y, 56, "绕y", vry, s -> vry = s);
        boxRz = numField(x + 120, y, 56, "绕z", vrz, s -> vrz = s);
        y += 16 + 10;

        // ---------- 尺寸 | 身份值 ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 78, "尺寸", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x2, y, 170, "身份值 mark", 0xFFB0B0C0));
        y += 11;
        boxScale = numField(x, y, 78, "尺寸", vScale, s -> vScale = s);
        boxMark = field(x2, y, 170, "身份值", vMark, 32);
        boxMark.setHint(Component.literal("点它时 on pick 收到的值"));
        boxMark.setResponder(s -> vMark = s);
        y += 16 + 14;

        // ---------- 事件（一条一行；【＋ 事件】在左栏）----------
        List<ScriptEdit.Event> ev = events();
        addRenderableWidget(DrawBoardMenuUi.label(x, y, fw, "事件", 0xFF909090));
        y += 11;
        if (ev.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(x, y, fw,
                    "（还没配）点左栏【＋ 事件】—— 会顺手把身份值补成组件名，并加一条 on pick 分支", 0xFF909090));
        }
        for (ScriptEdit.Event e : ev) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y, 260, 18, "事件： " + e.label() + " ▸ 编辑",
                    () -> openEventCards(e)));
            addRenderableWidget(DrawBoardMenuUi.button(x + 266, y, 18, 18, "✕", () -> delEvent(e)));
            y += 22;
        }
    }

    /** 标签 + 一个 16 高输入框（照 StageCompScreen 的 {@code field}）。 */
    private EditBox field(int x, int y, int w, String label, String value, int maxLen) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.literal(label));
        b.setMaxLength(maxLen);
        b.setValue(value == null ? "" : value);
        return addRenderableWidget(b);
    }

    /** 数字格（允许空 —— 角度空 = 不写 rot）。 */
    private EditBox numField(int x, int y, int w, String label, String value,
            java.util.function.Consumer<String> set) {
        EditBox b = field(x, y, w, label, value, 24);
        b.setResponder(set);
        return b;
    }

    /** 资产名格：只读（要改名就去脚本 / 删了重建）。 */
    private void boxAssetRO(int x, int y, int w) {
        EditBox b = field(x, y, w, "资产名", comp.asset(), 32);
        b.setEditable(false);
        b.setHint(Component.literal("引用它的地方用这个"));
    }

    // ==================== 动作 ====================

    private List<ScriptEdit.Event> events() {
        if (comp == null || comp.mark().isEmpty()) {
            return List.of();
        }
        return ScriptEdit.eventsOf(back.editor().def.script(), "画面组件", "", comp.mark());
    }

    /** 【＋ 事件】：身份值空就先补成组件名，再往 `on pick` 里加一条 `if (pick == "…") { }`（当场写一次）。 */
    private void addEvent() {
        if (comp == null) {
            return;
        }
        String src = back.editor().def.script();
        if (comp.mark().isEmpty()) {
            ScriptEdit.Result m = ScriptEdit.setEntityCompValue(src, scr, asset, "mark",
                    ScriptEdit.strCode(asset));
            if (m.text().equals(src)) {
                status(m.note(), true);
                return;
            }
            String err = back.editor().applyScript(m.text());
            if (!err.isEmpty()) {
                status(err, true);
                return;
            }
            load(back.editor().def.script());
            builtFrom = back.editor().def.script();
        }
        if (!events().isEmpty()) {
            status("这个组件已经配过事件了（一条就够，里面写多个动作）", true);
            return;
        }
        ScriptEdit.Result r = ScriptEdit.genBlockTrigger(back.editor().def.script(), "pick",
                "pick == " + ScriptEdit.strCode(comp.mark()), "点它");
        if (r.text().equals(back.editor().def.script())) {
            status(r.note(), true);
            return;
        }
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        builtFrom = back.editor().def.script();
        load(builtFrom);
        status(r.note(), false);
        clearWidgets();
        init();
    }

    private void delEvent(ScriptEdit.Event ev) {
        String src = back.editor().def.script();
        int onLine = ScriptEdit.handlerLine(src, ev.on());
        if (onLine < 0) {
            status("脚本里没有 on " + ev.on() + " 这个入口，没动", true);
            return;
        }
        ScriptEdit.Result r = ScriptCard.removeGuard(src, onLine, 0, ev.guard());
        if (r.text().equals(src)) {
            status(r.note(), true);
            return;
        }
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        builtFrom = back.editor().def.script();
        load(builtFrom);
        status(r.note(), false);
        clearWidgets();
        init();
    }

    /** 事件行点进去：`on pick` 那一块的动作卡页（返回目标 = 本屏）。 */
    private void openEventCards(ScriptEdit.Event ev) {
        if (ev == null) {
            return;
        }
        try {
            for (ScriptGraph.Node n : ScriptGraph.build(Parser.parse(back.editor().def.script())).nodes()) {
                if (n.key().equals("on:" + ev.on())) {
                    Minecraft.getInstance().setScreen(NodeEditScreen.of(back.editor(), this, n.key(),
                            n.label(), n.kind(), n.line(), ev));
                    return;
                }
            }
            status("图上找不到「on " + ev.on() + "」这个入口节点", true);
        } catch (Ast.ScriptError e) {
            status("脚本有语法错，先去【脚本】页修：" + e.getMessage(), true);
        }
    }

    /** 类型那三颗：先把**声明动词**换掉（补缺省字段），再刷新本页。 */
    private void switchKind(String k) {
        if (comp == null || k.equals(comp.kind())) {
            return;
        }
        String base = null;
        if (!k.equals("text")) {
            if (k.equals("card")) {
                var cards = back.editor().def.cards();
                if (cards == null || cards.isEmpty()) {
                    status("档里还没有卡牌：先去对象页建一张卡，再切过来", true);
                    return;
                }
                base = cards.get(0).id();
            } else {
                base = comp.kind().equals("card") ? "minecraft:stone" : (vBase.isEmpty() ? "minecraft:stone" : vBase);
            }
        }
        ScriptEdit.Result r = ScriptEdit.setEntityCompKind(back.editor().def.script(), scr, asset, k, base);
        if (r.text().equals(back.editor().def.script())) {
            status(r.note(), true);
            return;
        }
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        builtFrom = back.editor().def.script();
        load(builtFrom);
        status(r.note(), false);
        clearWidgets();
        init();
    }

    /** 【完成】：把这一页的草稿**一次**写回脚本（任一字段不合法就整轮不动）。 */
    private void commit() {
        if (comp == null) {
            back();
            return;
        }
        String src = back.editor().def.script();
        String work = src;
        java.util.List<String> notes = new java.util.ArrayList<>();

        work = step(work, "name", ScriptEdit.strCode(vLabel.strip()), "显示名", notes);
        if (!kind.equals("text")) {
            if (vBase.isBlank()) {
                status("引用 base 不能空（卡牌写卡资产名 / 物品写原版 id）", true);
                return;
            }
            work = step(work, "base", ScriptEdit.strCode(vBase.strip()), "引用", notes);
        }
        String sc = vScale.strip();
        Double scv = parseNum(sc);
        if (scv == null || scv <= 0) {
            status("尺寸要写一个大于 0 的数字（值越小越大）", true);
            return;
        }
        work = step(work, "scale", ScriptEdit.numText(scv), "尺寸", notes);

        double[] p = nums(new String[] { vx, vy, vz }, "位置");
        if (p == null) {
            return;                                             // nums 里已经报过原因
        }
        work = step(work, "at", ScriptEdit.numText(p[0]) + " " + ScriptEdit.numText(p[1]) + " "
                + ScriptEdit.numText(p[2]), "位置", notes);

        boolean anyRot = !vrx.isBlank() || !vry.isBlank() || !vrz.isBlank();
        if (anyRot) {
            String rx = vrx.isBlank() ? "0" : vrx.strip();
            String ry = vry.isBlank() ? "0" : vry.strip();
            String rz = vrz.isBlank() ? "0" : vrz.strip();
            double[] rr = nums(new String[] { rx, ry, rz }, "角度");
            if (rr == null) {
                return;
            }
            work = step(work, "rot", ScriptEdit.numText(rr[0]) + " " + ScriptEdit.numText(rr[1]) + " "
                    + ScriptEdit.numText(rr[2]), "角度", notes);
        } else if (comp.hasRot()) {
            ScriptEdit.Result rr = ScriptEdit.removeEntityCompKey(work, scr, asset, "rot");
            if (!rr.text().equals(work)) {
                work = rr.text();
                notes.add("角度已清（回到面向观察者）");
            }
        }
        work = step(work, "mark", vMark.isBlank() ? null : ScriptEdit.strCode(vMark.strip()), "身份值", notes);

        if (work.equals(src)) {
            status("这一页没改动", false);
            back();
            return;
        }
        String err = back.editor().applyScript(work);
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        DrawBoardMenuUi.msg("[组件] " + String.join("；", notes));
        back();
    }

    /** 一步写回：空值 = 删这个键；拒收（原文没变）= 跳过并记一句。 */
    private String step(String src, String key, String raw, String what, java.util.List<String> notes) {
        ScriptEdit.Result r = raw == null
                ? ScriptEdit.removeEntityCompKey(src, scr, asset, key)
                : ScriptEdit.setEntityCompValue(src, scr, asset, key, raw);
        if (!r.text().equals(src)) {
            notes.add(what);
        } else if (!r.note().isEmpty() && raw == null) {
            notes.add(r.note());
        }
        return r.text();
    }

    private double[] nums(String[] vals, String what) {
        double[] out = new double[vals.length];
        for (int i = 0; i < vals.length; i++) {
            Double d = parseNum(vals[i].isBlank() ? "0" : vals[i].strip());
            if (d == null) {
                status(what + "要写数字（空着当 0）", true);
                return null;
            }
            out[i] = d;
        }
        return out;
    }

    private static Double parseNum(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void back() {
        back.rebuildKeep(asset);
        Minecraft.getInstance().setScreen(back);
    }

    private void status(String s, boolean bad) {
        status = s;
        statusBad = bad;
    }

    // ==================== 画 / 键 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            g.centeredText(font, Component.literal(status), width / 2, height - 34,
                    statusBad ? 0xFFFF9090 : 0xFF90D090);
        }
        g.centeredText(font, Component.literal("Enter 完成 · Esc 取消（改完这一页才写回脚本；"
                + "位置/角度也能在 3D 编辑页拖手柄改）"),
                width / 2, height - 20, 0xFF909090);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257) {                                // Enter = 完成
            commit();
            return true;
        }
        if (event.key() == 256) {                                // Esc = 取消（丢弃这一页的改动）
            back();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
