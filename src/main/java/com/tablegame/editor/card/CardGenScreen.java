package com.tablegame.editor.card;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;

/**
 * 卡牌完整生成器：两个列表做笛卡尔积（默认 花色 × 点数 = 52 张），名字与每个属性值走模板替换，
 * 一次生成一整批 {@code card} 声明。
 * 三条纪律：只产声明（真源还是脚本）；已存在的名字跳过（不覆盖手写的牌）；名字要过语言规则
 * （中文卡名走字符串字面量，见 {@link ScriptEdit#nameCode}）。
 * 占位符：{@code {列表名}}（列表没写冒号用 {@code {A}} / {@code {B}}）。例：列表一 {@code 花色: 红桃, 黑桃} ·
 * 名字 {@code {花色}{点数}} · 属性 {@code 花色={花色}; 点数={点数}}。
 */
public class CardGenScreen extends Screen implements EditorToolScreen {

    /** 背后的玩法编辑器 + 编完回哪一屏（对象页）。 */
    private final GameEditorScreen editor;

    /** 交出背后的编辑器（{@link EditorToolScreen}）—— 不实现它保存回执会落到「新开编辑器」那支，把人弹回总览。 */
    @Override
    public GameEditorScreen editor() {
        return editor;
    }
    private final Screen backTo;

    private EditBox boxA, boxB, boxName, boxArt, boxFields;
    private String listA = "花色: 红桃, 黑桃, 方块, 梅花";
    private String listB = "点数: A, 2, 3, 4, 5, 6, 7, 8, 9, 10, J, Q, K";
    private String namePat = "{花色}{点数}";
    private String artPat = "";
    private String fields = "花色={花色}; 点数={点数}";
    private String status = "";

    public CardGenScreen(GameEditorScreen editor, Screen backTo) {
        super(Component.literal("卡牌生成器"));
        this.editor = editor;
        this.backTo = backTo;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = 50;
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y - 12, 280, "两个列表做交叉（默认 4 花色 × 13 点数 = 52 张）", 0xFFB0B0B0));

        boxA = box(cx, y, "列表一", listA, s -> listA = s);
        y += 24;
        boxB = box(cx, y, "列表二", listB, s -> listB = s);
        y += 24;
        boxName = box(cx, y, "名字模板", namePat, s -> namePat = s);
        y += 24;
        boxArt = box(cx, y, "卡面模板（可空）", artPat, s -> artPat = s);
        y += 24;
        boxFields = box(cx, y, "属性模板（分号分隔）", fields, s -> fields = s);
        y += 30;

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 136, 24, "生成", this::gen));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, y, 136, 24, "返回", () ->
                Minecraft.getInstance().setScreen(backTo)));      // 回对象页（MC 自己会 init 它）
        EditorRail.build(editor, "对象", this::addRenderableWidget);
    }

    private EditBox box(int cx, int y, String hint, String value, java.util.function.Consumer<String> set) {
        EditBox b = new EditBox(font, cx - 140, y, 280, 18, Component.literal(hint));
        b.setMaxLength(400);
        b.setValue(value);
        b.setHint(Component.literal(hint));
        b.setResponder(set);
        addRenderableWidget(b);
        return b;
    }

    /** 一次生成的计划（不落地）：会生成哪几段声明 · 哪些名字 · 跳过了几张 · 不行就 err 给原因。 */
    private record Plan(List<String> codes, List<String> names, int skipped, String err) { }

    private Plan plan() {
        String[] a = ScriptEdit.parseListLine(listA);
        String[] b = ScriptEdit.parseListLine(listB);
        List<String> av = ScriptEdit.splitValues(a[1]);
        List<String> bv = ScriptEdit.splitValues(b[1]);
        if (av.isEmpty() || bv.isEmpty()) return new Plan(List.of(), List.of(), 0, "两个列表都要有值（逗号分隔）");
        String na = a[0].isEmpty() ? "A" : a[0];
        String nb = b[0].isEmpty() ? "B" : b[0];
        String src = editor.def.script();
        List<String> codes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int skipped = 0;
        for (String x : av) {
            for (String y : bv) {
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put(na, x);
                vars.put(nb, y);
                String nm = ScriptEdit.template(namePat, vars).strip();
                if (nm.isEmpty() || ScriptEdit.declOf(src, "card", nm) != null) {
                    skipped++;
                    continue;
                }
                List<ScriptEdit.Field> fs = new ArrayList<>();
                if (!artPat.isBlank()) {
                    fs.add(new ScriptEdit.Field("art", ScriptEdit.strCode(ScriptEdit.template(artPat, vars)), null));
                }
                for (String[] t : ScriptEdit.parseFieldTemplates(fields)) {
                    fs.add(new ScriptEdit.Field(t[0], ScriptEdit.strCode(ScriptEdit.template(t[1], vars)), null));
                }
                codes.add(ScriptEdit.declCode("card", nm, fs));
                names.add(nm);
            }
        }
        return new Plan(codes, names, skipped, "");
    }

    private void gen() {
        Plan p = plan();
        if (!p.err().isEmpty()) {
            status = p.err();
            return;
        }
        ScriptEdit.Result r = ScriptEdit.addDeclCodes(editor.def.script(), p.codes(),
                "生成 " + p.codes().size() + " 张卡");
        String err = editor.applyScript(r.text());
        status = err.isEmpty()
                ? r.note() + (p.skipped() > 0 ? "（跳过 " + p.skipped() + " 张已存在的）" : "")
                : err;
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
        g.centeredText(font, Component.literal("卡牌生成器 · 交叉生成一整批"), width / 2, 30, 0xFFFFFFFF);
        Plan p = plan();
        if (p.err().isEmpty()) {
            g.text(font, "将生成 " + p.codes().size() + " 张：" + DrawBoardMenuUi.ellipsis(String.join(", ", p.names()), 68),
                    width / 2 - 140, height - 52, 0xFF80E080);
        }
        g.text(font, status.isEmpty() ? "占位符 = {列表名}（列表没写冒号就用 {A} / {B}）" : status,
                width / 2 - 140, height - 36, status.isEmpty() ? 0xFF909090 : 0xFFFFD070);
    }
}
