package com.tablegame.drawboard;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 「新建项目」界面：目标组 + 项目名 + 画布尺寸。
 * 目标组来自打开前向服务器要的「我的组列表」，顶部循环切换（单人组永远第一项、默认选中）。
 * 尺寸：预设 16/32/64 一键填入，也可自定义宽 × 高（各 8~512，支持非正方形）；创建时定死。
 */
public class NewProjectScreen extends Screen {
    private final Screen previous;
    private final List<String> groupNames;   // 我的组：单人组第一
    private int groupIdx = 0;
    private AbstractWidget groupButton;
    private EditBox nameBox, wBox, hBox;
    private static final int[] PRESETS = {16, 32, 64};
    private int customW = 16, customH = 16;

    public NewProjectScreen(Screen previous, List<String> groups) {
        this(previous, groups, false);
    }

    /** @param cardMode 制作卡牌入口：预填 30×42 卡面规格（扑克 5:7），标题提示卡面/卡背用途 */
    public NewProjectScreen(Screen previous, List<String> groups, boolean cardMode) {
        super(Component.literal(cardMode ? "制作卡牌（画卡面/卡背）" : "新建画板项目"));
        this.previous = previous;
        this.groupNames = groups == null ? new ArrayList<>() : new ArrayList<>(groups);
        if (cardMode) {
            this.customW = 30;
            this.customH = 42;
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = height / 2 - 140;

        // 目标组：循环切换（label 每次点按后更新）
        groupButton = DrawBoardMenuUi.button(cx - 110, y, 220, 20, groupLabel(), () -> {
            if (!groupNames.isEmpty()) groupIdx = (groupIdx + 1) % groupNames.size();
            groupButton.setMessage(Component.literal(groupLabel()));
        });
        addRenderableWidget(groupButton);
        y += 30;

        // 项目名
        nameBox = new EditBox(font, cx - 110, y, 220, 20, Component.literal("项目名"));
        nameBox.setMaxLength(BoardManager.MAX_NAME);
        nameBox.setHint(Component.literal("项目名（≤40 字符）"));
        addRenderableWidget(nameBox);
        y += 36;

        // 预设：点一下填进自定义框（预设都是正方形）
        g_text("预设尺寸（点选填入）", cx - 110, y);
        y += 14;
        for (int i = 0; i < PRESETS.length; i++) {
            final int s = PRESETS[i];
            addRenderableWidget(DrawBoardMenuUi.button(cx - 110 + i * 76, y, 72, 20, s + " × " + s, () -> {
                customW = customH = s;
                wBox.setValue(String.valueOf(s));
                hBox.setValue(String.valueOf(s));
            }));
        }
        y += 32;

        // 自定义宽高
        g_text("自定义（8 ~ 512，可非正方形）", cx - 110, y);
        y += 14;
        wBox = digitBox(cx - 110, y);
        if (customW != 16) wBox.setValue(String.valueOf(customW));   // 卡牌模式预填 30
        addRenderableWidget(wBox);
        g_text("宽", cx - 130, y + 4);
        hBox = digitBox(cx + 10, y);
        if (customH != 16) hBox.setValue(String.valueOf(customH));   // 卡牌模式预填 42
        addRenderableWidget(hBox);
        g_text("高", cx - 10, y + 4);
        y += 40;

        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 24, "创建项目", this::create));
        y += 32;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 24, "返回", () ->
                Minecraft.getInstance().setScreen(previous)));
    }

    private String groupLabel() {
        if (groupNames.isEmpty()) return "创建到：（单人组） ▸";
        return "创建到：" + groupNames.get(groupIdx) + " ▸";
    }

    private EditBox digitBox(int x, int y) {
        EditBox box = new EditBox(font, x, y, 90, 20, Component.literal("数字"));
        box.setMaxLength(3);
        box.setValue("16");
        // 只允许数字
        box.setResponder(s -> {
            String clean = s.replaceAll("[^0-9]", "");
            if (!clean.equals(s)) box.setValue(clean);
        });
        return box;
    }

    private void g_text(String s, int x, int y) {
        // 标签文本：用一次性 renderable 绘制（无交互）
        addRenderableOnly(new AbstractWidget(x, y, 220, 12, Component.literal(s)) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor gr, int mx, int my, float pt) {
                gr.text(font, getMessage().getString(), getX(), getY(), 0xFFB0B0B0);
            }

            @Override
            protected void updateWidgetNarration(NarrationElementOutput out) {}
        });
    }

    private void create() {
        String name = nameBox.getValue().trim();
        try {
            customW = Integer.parseInt(wBox.getValue().isBlank() ? "0" : wBox.getValue());
            customH = Integer.parseInt(hBox.getValue().isBlank() ? "0" : hBox.getValue());
        } catch (NumberFormatException e) {
            customW = customH = 0;
        }
        if (name.isEmpty()) {
            DrawBoardMenuUi.msg("[画板] 请先填项目名");
            return;
        }
        if (customW < BoardManager.MIN_DIM || customW > BoardManager.MAX_DIM
                || customH < BoardManager.MIN_DIM || customH > BoardManager.MAX_DIM) {
            DrawBoardMenuUi.msg("[画板] 尺寸需在 8~512 之间");
            return;
        }
        // 目标组：列表为空 = 没拿到组列表，发空字符串让服务端落到单人组
        String group = groupNames.isEmpty() ? "" : groupNames.get(groupIdx);
        ClientPacketDistributor.sendToServer(new BoardPackets.CreateProjectPayload(group, name, customW, customH));
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
        g.centeredText(font, title, width / 2, height / 2 - 170, 0xFFFFFFFF);
    }
}
