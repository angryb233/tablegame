package com.tablegame.editor.world;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptGraph;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Interp;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.script.NodeEditScreen;

/**
 * 区域属性页：改一条区域声明的资产名 · 显示名 · 属性。
 * 资产名改名连引用一起改（{@link ScriptEdit#renameDecl}），内容快照 id 也对齐；显示名只给人看，留空 = 删字段。
 * 属性 = 条件（区域保护 / 进圈 / 出圈），后两条只生成空壳条件块，事件在节点编辑页里加。
 * ⚠ 只写脚本（区域真源 = 顶层 {@code area} 声明）；盒与内容不在这页动。
 */
public class AreaEditScreen extends Screen implements EditorToolScreen {
    /** 上一屏（返回 / 取消 / 提交完都回它 = 世界页，它会自己重读脚本刷新那行）。 */
    private final WorldTabScreen parent;
    /** 玩法编辑器（脚本唯一数据源）。 */
    private final GameEditorScreen editor;
    /** 资产名 / 显示名两个输入框的宽度（一样长，一处定义）。 */
    private static final int FIELD_W = 240;

    /** 进来那一刻那条声明（名字与显示名的初始值取自它）。 */
    private final Interp.Region region;

    /** 【＋属性】与「选值」菜单（AssetMenu 标签截到 16 字，标签要短）。 */
    private final AssetMenu menu = new AssetMenu();
    /** 反查出来的属性（每次 init 现算：**脚本是真源**，界面只是生成器 + 一层视图）。 */
    private List<ScriptEdit.AreaAttr> attrs = List.of();
    private EditBox idBox, nameBox;
    /** 输入框当前值（EditBox 不持久，值随输入进这两个字段）。 */
    private String liveId, liveName;
    private String status = "";
    private boolean statusBad;

    public AreaEditScreen(WorldTabScreen parent, GameEditorScreen editor, Interp.Region region) {
        super(Component.literal("区域"));
        this.parent = parent;
        this.editor = editor;
        this.region = region;
        this.liveId = region.name();
        this.liveName = region.label();
    }

    @Override
    public GameEditorScreen editor() {
        return editor;
    }

    @Override
    protected void init() {
        clearWidgets();
        // 左栏（本页无二级页签）：【返回】跟在页签之后，【完成】【取消】常驻栏底
        int railBottom = EditorRail.buildGeneric(new String[0], null, t -> { }, this::addRenderableWidget);
        addRenderableWidget(DrawBoardMenuUi.button(4, railBottom + 6, EditorRail.W, 18, "返回", this::back));
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 46, EditorRail.W, 18, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 24, EditorRail.W, 18, "取消", this::back));

        int x = EditorRail.LEFT;
        int fw = Math.min(460, Math.max(180, width - x - 24));
        boolean tight = height < 320;
        int gap = tight ? 32 : 38;
        int ty = tight ? 26 : 34;

        // ---------- 资产名 ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "资产名",
                0xFF909090));
        if (region.name().isEmpty()) {
            // 匿名区域没有名字 —— 改名前得先在脚本里给它起个名
            addRenderableWidget(DrawBoardMenuUi.label(x, ty + 12, fw,
                    "（匿名区域 —— 没有名字，先在 3D 视窗里点【框】框选一次，名字就建出来了）", 0xFFB06060));
        } else {
            idBox = new EditBox(font, x, ty + 12, FIELD_W, 18, Component.literal("资产名"));
            idBox.setMaxLength(64);
            idBox.setValue(liveId);
            idBox.setResponder(s -> liveId = s);
            addRenderableWidget(idBox);
        }
        ty += gap;

        // ---------- 显示名 ----------
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "显示名",
                0xFF909090));
        nameBox = new EditBox(font, x, ty + 12, FIELD_W, 18, Component.literal("显示名"));
        nameBox.setMaxLength(64);
        nameBox.setValue(liveName);
        nameBox.setResponder(s -> liveName = s);
        addRenderableWidget(nameBox);
        ty += gap;

        // ---------- 内容（只读：内容是视窗 / 框选那边的事）----------
        String snap = region.art().isEmpty() ? region.name() : region.art();
        GameDefinition.AreaDef def = null;
        for (var d : editor.def.areas()) {
            if (d.id().equals(snap)) def = d;
        }
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "内容", 0xFF909090));
        addRenderableWidget(DrawBoardMenuUi.label(x, ty + 12, fw,
                (snap.isEmpty() ? "—（匿名区域没有内容）"
                        : snap + (def != null && def.captured() ? " · 已捕获" : " · 未捕获"))
                        + "　盒 (" + (long) region.minX() + "," + (long) region.minY() + "," + (long) region.minZ()
                        + ")-(" + (long) region.maxX() + "," + (long) region.maxY() + "," + (long) region.maxZ() + ")"
                        + "　世界 " + region.dim(), 0xFFB0B0C0));
        ty += gap;

        // ---------- 属性（一行一个 + 一颗【＋属性】）----------
        // 落地形态 = 往脚本里生成一段代码：界面只是生成器 + 视图，作者随时能去脚本页改。
        attrs = ScriptEdit.areaAttrsOf(editor.def.script(), region.name());
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "属性", 0xFF909090));
        final int attrMenuX = x + 96, attrMenuY = ty + 34;          // lambda 只能捕获 final 的
        addRenderableWidget(DrawBoardMenuUi.button(x, ty + 12, 96, 20, "＋ 属性",
                () -> openAttrMenu(attrMenuX, attrMenuY)));         // 菜单开在按钮右下（不跟鼠标跑）
        int rowY = ty + 38;
        for (ScriptEdit.AreaAttr a : attrs) {
            buildAttrRow(x, rowY, fw, a);
            rowY += 24;
        }
        // 没配时不写字，只留【＋属性】那颗按钮
    }

    /**
     * 一行 = 一条属性：`属性： <名> [· 值] ▸ 编辑` + 行尾 ✕。
     * 点【编辑】进节点编辑页（{@link NodeEditScreen}），只看这一条属性的那段；值在那页里改。
     */
    private void buildAttrRow(int x, int y, int fw, ScriptEdit.AreaAttr a) {
        int bw = Math.max(80, Math.min(fw, 340) - 24);
        String lv = switch (a.value()) {
            case "1" -> "一级";
            case "2" -> "二级";
            case "3" -> "三级";
            default -> a.value();
        };
        String label = a.cn() + (lv.isEmpty() ? "" : " · " + lv);
        addRenderableWidget(DrawBoardMenuUi.button(x, y, bw, 20,
                DrawBoardMenuUi.ellipsis("属性： " + label + " ▸ 编辑", 30), () -> openAttrNode(a)));
        addRenderableWidget(DrawBoardMenuUi.button(x + bw + 4, y, 20, 20, "✕", () -> removeAttr(a)));
    }

    /**
     * 【编辑】→ 节点编辑页，只显示这一条属性那一段（同一条属性可能占两段）。
     */
    private void openAttrNode(ScriptEdit.AreaAttr a) {
        java.util.List<int[]> spans = ScriptEdit.attrSpans(editor.def.script(), region.name(), a.key());
        if (spans.isEmpty()) {
            setStatus("找不到这一段（脚本可能手改过，标记被删了）—— 去脚本页看", true);
            return;
        }
        // 保护挂在 on reload，其余属性在 on world —— 跳错块那页会空着。
        String on = a.key().equals("protect") ? "reload" : "world";
        try {
            for (ScriptGraph.Node n : ScriptGraph.build(Parser.parse(editor.def.script())).nodes()) {
                if (n.key().equals("on:" + on)) {
                    Minecraft.getInstance().setScreen(NodeEditScreen.ofSpans(editor, this,
                            n.key(), n.label(), n.kind(), n.line(), spans));
                    return;
                }
            }
        } catch (Ast.ScriptError e) {
            setStatus("脚本有语法错，先去【脚本】页修：" + e.getMessage(), true);
            return;
        }
        setStatus("图上找不到「on " + on + "」这个入口节点", true);
    }

    /**
     * 【＋属性】：列还没配的那几条；选完要值（切界面 / 切模式）时再弹第二层选值。
     */
    private void openAttrMenu(double mx, double my) {
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String k : ScriptEdit.attrKeys()) {
            boolean has = false;
            for (ScriptEdit.AreaAttr a : attrs) {
                if (a.key().equals(k)) has = true;
            }
            if (!has) {
                keys.add(k);
                labels.add(ScriptEdit.attrCn(k));
            }
        }
        if (keys.isEmpty()) {
            setStatus("三条属性都配上了（要改哪条就点它那行【编辑】）", true);
            return;
        }
        List<Runnable> acts = new ArrayList<>();
        for (String k : keys) {
            acts.add(() -> pickValue(k, mx, my));
        }
        menu.open(width, height, mx, my, labels, acts);
    }

    /** 选值：切界面 → 脚本里的画布名；切模式 → 四种模式；没值的属性 → 直接生成。 */
    private void pickValue(String key, double mx, double my) {
        if (key.equals("protect")) {
            // 三级保护（越高越死）：一级 = 只拦生存（冒险交给原版，带组件能破例）；二级 = 拦生存+冒险；三级 = 连创造也挡。级别写进属性值。
            menu.open(width, height, mx, my,
                    List.of("一级 · 只拦生存", "二级 · 拦生存+冒险", "三级 · 连创造也挡"),
                    List.of(() -> gen(key, "1"), () -> gen(key, "2"), () -> gen(key, "3")));
            return;
        }
        // 【进圈规则】/【出圈规则】：只生成空壳条件块、没有值可选；加事件点【编辑】进节点编辑页。
        gen(key, "");
    }

    private void gen(String key, String value) {
        ScriptEdit.Result r = ScriptEdit.genAreaAttr(editor.def.script(), region.name(), key, value);
        if (r.text().equals(editor.def.script())) {
            setStatus(r.note(), true);
            return;
        }
        String err = editor.applyScript(r.text());
        setStatus(err.isEmpty() ? r.note() : err, !err.isEmpty());
        rebuild();
    }

    private void removeAttr(ScriptEdit.AreaAttr a) {
        ScriptEdit.Result r = ScriptEdit.removeAreaAttr(editor.def.script(), region.name(), a.key());
        if (r.text().equals(editor.def.script())) {
            setStatus(r.note(), true);
            return;
        }
        String err = editor.applyScript(r.text());
        setStatus(err.isEmpty() ? r.note() : err, !err.isEmpty());
        rebuild();
    }

    /** 重排这一页（加 / 删 / 改属性之后都要：列表是从脚本现算的）。 */
    private void rebuild() {
        clearWidgets();
        init();
    }

    /**
     * 【完成】= 资产名（连引用与内容快照）+ 显示名一并写回。
     * ⚠ 顺序要紧：先改名，再在新脚本上写 {@code name} 字段（反过来字段会挂在旧名字上）。
     */
    private void commit() {
        String cur = editor.def.script();
        String note = "";
        String nm = liveId == null ? "" : liveId.strip();

        // ① 资产名（连引用一起改）
        if (!region.name().isEmpty() && !nm.equals(region.name())) {
            ScriptEdit.Result r = ScriptEdit.renameDecl(cur, "area", region.name(), nm);
            if (r.text().equals(cur)) {                       // 没改：名字被占 / 是保留字 / 非法标识符 …
                setStatus(r.note(), true);
                return;
            }
            cur = r.text();
            note = r.note();
            // 内容快照的 id 跟着对齐（声明名 = 内容名，否则会多出一栏「未绑定」）
            String oldSnap = region.art().isEmpty() ? region.name() : region.art();
            List<GameDefinition.AreaDef> list = new ArrayList<>();
            for (var d : editor.def.areas()) {
                list.add(d.id().equals(oldSnap)
                        ? new GameDefinition.AreaDef(nm, d.sizeX(), d.sizeY(), d.sizeZ(), d.palette(), d.blocks(),
                                d.marks())
                        : d);
            }
            editor.def = editor.def.withAreas(list);
        }

        // ② 显示名（空 = 删字段；本来就空又不写 = 不动它，免得白跑一次）
        String label = liveName == null ? "" : liveName.strip();
        if (!label.equals(region.label())
                && (!label.isEmpty() || !region.label().isEmpty())) {
            ScriptEdit.Result r = ScriptEdit.setDeclField(cur, "area", nm, "name",
                    label.isEmpty() ? "" : "\"" + label.replace("\"", "").replace("\\", "") + "\"");
            if (r.text().equals(cur)) {
                setStatus(r.note(), true);
                return;
            }
            cur = r.text();
            note = r.note();
        }

        String err = editor.applyScript(cur);
        if (!err.isEmpty()) {
            setStatus(err, true);
            return;
        }
        DrawBoardMenuUi.msg("[世界] " + (note.isEmpty() ? "区域已更新" : note));
        back();
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void setStatus(String s, boolean bad) {
        status = s;
        statusBad = bad;
        if (bad) DrawBoardMenuUi.msg("[世界] " + s);
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
        EditorRail.drawBackdrop(g, height);
        String show = region.label().isEmpty() || region.label().equals(region.name())
                ? region.name() : region.label() + " · " + region.name();
        g.centeredText(font, Component.literal("区域属性 · " + (show.isEmpty() ? "（匿名）" : show)),
                EditorRail.cx(this), 16, 0xFFFFFFFF);
        g.text(font, status.isEmpty()
                        ? ""                                       // 没有回执就不写字
                        : status,
                EditorRail.LEFT, height - 44, statusBad ? 0xFFFF8080 : 0xFF90C090);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);                 // 菜单画在最上层
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (menu.click(event.x(), event.y())) {
            return true;                                    // 菜单开着先喂菜单（点别处 = 关掉）
        }
        return super.mouseClicked(event, doubled);
    }

    // ===== 菜单检索/滚动/拖动转发（AssetMenu）=====

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menu.type(event.codepointAsString())) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) {
            return true;                              // 菜单检索行在退格
        }
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) {
            return true;                              // 菜单开着：回车不落到底下的控件
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (menu.drag(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (menu.release(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }
}
