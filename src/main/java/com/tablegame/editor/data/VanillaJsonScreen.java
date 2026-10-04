package com.tablegame.editor.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.tablegame.drawboard.DrawBoardMenuUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.item.ComponentTextScreen;

/**
 * 进度 / 魔咒 / 伤害类型 的编辑屏（三段共用，按段键参数化）。
 *
 * <p>这批数据是深树（进度的 criteria/display · 魔咒的 effects · …）⇒ 不逐字段做表单，直接编原版 JSON 原文
 * （多行框，借现成的 {@link ComponentTextScreen}）；保存时拿原版 codec 校验，没做 UI 的字段一个字不丢。
 * 新建给一份能过 codec 的模板（{@link VanillaJson#templateOf}）。
 */
public class VanillaJsonScreen extends Screen {
    private static final int ROW = 24;
    private static final int LBL = 96;
    private static final int BOX_W = 260;
    /** 原文框上限：长 JSON 不能被默默截断（ComponentTextScreen 默认 2000）。 */
    private static final int JSON_LIMIT = 20000;

    private final Screen back;
    private final GameEditorScreen parent;
    private final String key;
    private final String name0;                     // 旧名（空 = 新建）
    private String name;
    private String json;                            // **副本**：校验不过不该动真身
    private EditBox nameBox;
    private String status = "";
    private boolean armed;

    VanillaJsonScreen(Screen back, GameEditorScreen parent, String key, String name) {
        super(Component.literal(VanillaJson.cnOf(key)));
        this.back = back;
        this.parent = parent;
        this.key = key;
        this.name0 = name == null ? "" : name;
        this.name = name0;
        JsonElement e = parent.def.vanillaSection(key).get(name0);
        this.json = e == null || e.isJsonNull() ? VanillaJson.templateOf(key) : pretty(e);
    }

    private static String pretty(JsonElement e) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(e);
    }

    @Override
    protected void init() {
        final int x = EditorRail.LEFT;
        final int bx = x + LBL;
        nameBox = new EditBox(font, bx, 46, BOX_W, 16, Component.literal(""));
        nameBox.setMaxLength(64);
        nameBox.setValue(name == null ? "" : name);
        addRenderableWidget(nameBox);
        addRenderableWidget(DrawBoardMenuUi.button(bx, 70, BOX_W, 18,
                "改这一份 JSON（" + json.length() + " 字符）…", this::editJson));

        addRenderableWidget(DrawBoardMenuUi.button(x, height - 46, 84, 20, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(x + 90, height - 46, 84, 20, "取消", this::leave));
        if (!name0.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x + 180, height - 24, 84, 20,
                    armed ? "确认删除" : "删除", this::delete));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 24, 84, 20, "‹ 清单", this::leave));
    }

    private void editJson() {
        Minecraft.getInstance().setScreen(new ComponentTextScreen(this,
                VanillaJson.cnOf(key) + " JSON（原版格式）", json, JSON_LIMIT, text -> {
                    json = text;
                    status = "";
                    refresh();
                }));
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    private void commit() {
        String nm = nameBox == null ? "" : nameBox.getValue().trim();
        if (nm.isEmpty()) {
            status = "名字不能空";
            return;
        }
        // 名字**同时就是原版 id**（别的文件 / 指令 / 组件按它引用）⇒ 只许 a-z 0-9 _ . - /
        if (!VanillaJson.isName(nm)) {
            status = "名字同时是原版 id：只许 a-z 0-9 _ . - /（例 my_charm）—— 中文 / 大写 / 空格都不行";
            return;
        }
        if (!nm.equals(name0) && parent.def.vanillaSection(key).has(nm)) {
            status = "已经有这一份了：" + nm;
            return;
        }
        String err = parent.applyVanilla(key, nm, json);
        if (!err.isEmpty()) {
            status = err;                       // 不落盘：改的东西还在屏上
            return;
        }
        if (!name0.isEmpty() && !nm.equals(name0)) parent.applyVanilla(key, name0, "");   // 改名 = 写新的 + 删旧的
        leave();
    }

    private void delete() {
        if (!armed) {
            armed = true;
            status = "再点一次「确认删除」就删掉这一份";
            refresh();
            return;
        }
        parent.applyVanilla(key, name0, "");
        leave();
    }

    private void leave() {
        Minecraft.getInstance().setScreen(back);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        int x = EditorRail.LEFT;
        int bx = x + LBL;
        String cn = VanillaJson.cnOf(key);
        g.centeredText(font, Component.literal("原版数据 · " + cn + " · " + (name0.isEmpty() ? "新建" : name0)),
                EditorRail.cx(this), 12, 0xFFFFFFFF);
        g.text(font, "名字", x, 51, 0xFFC0C0C0);
        g.text(font, "内容", x, 75, 0xFFC0C0C0);
        String nm = nameBox == null ? name : nameBox.getValue().trim();
        if (!nm.isEmpty()) {
            g.text(font, "包内文件 " + VanillaJson.packFile(key, nm) + "　·　别的文件按 "
                    + VanillaJson.entryId(key, nm) + " 引用它", x, height - 92, 0xFF909090);
        }
        g.text(font, "写着" + cn + "的原版 JSON（保存时用原版 codec 校验）；名字只许 a-z 0-9 _ . - /",
                x, height - 80, 0xFF909090);
        if (!status.isEmpty()) {
            g.text(font, status, bx, height - 58, 0xFFE0C060);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                       // Esc = 丢掉改动回清单
            leave();
            return true;
        }
        return super.keyPressed(event);
    }
}
