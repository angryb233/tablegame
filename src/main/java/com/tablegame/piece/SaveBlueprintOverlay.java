package com.tablegame.piece;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 「保存蓝图」浮层：输入名字 + 「到：<组> ▸」循环选组 → 发 {@link ModelMakerPackets.SaveBlueprintPayload}。
 * 组列表由客户端请求、服务端发回（BoardManager.myGroupNames：单人组永远第一且默认选中），
 * 收包在 ModelMakerClient 里路由调 {@link #applyGroups}。
 */
public class SaveBlueprintOverlay extends ModelMakerOverlayScreen {
    public static final int MAX_NAME = 32;   // 与 BlueprintNameScreen 上限一致

    private EditBox nameBox;
    private Button groupButton;
    private List<String> groups = List.of();
    private int groupIdx = 0;

    public SaveBlueprintOverlay(ModelMakerScreen parent) {
        super(parent, Component.literal("保存蓝图到蓝图库"));
        this.panelH = 110;
    }

    /** 服务端组列表到了（ModelMakerClient 路由进来）：单人组已在首位，重建组按钮文字。 */
    public void applyGroups(List<String> groups) {
        this.groups = groups;
        this.groupIdx = 0;
        if (groupButton != null) {
            groupButton.setMessage(Component.literal(groupLabel()));
        }
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        // 名字输入框（留空 = 用蓝图现有名）
        nameBox = new EditBox(font, panelX + 10, panelY + 24, panelW - 20, 18, Component.literal("蓝图名"));
        nameBox.setMaxLength(MAX_NAME);
        nameBox.setHint(Component.literal("蓝图名（留空 = 蓝图现有名）"));
        addRenderableWidget(nameBox);
        setFocused(nameBox);

        // 「到：<组> ▸」循环选组（NewProjectScreen 同款交互）
        groupButton = addRenderableWidget(Button.builder(Component.literal(groupLabel()), b -> {
            if (!groups.isEmpty()) {
                groupIdx = (groupIdx + 1) % groups.size();
                b.setMessage(Component.literal(groupLabel()));
            }
            this.setFocused(null);   // 循环按钮点了不会被禁用，但保持与其它按钮同样的焦点卫生
        }).bounds(panelX + 10, panelY + 50, panelW - 20, 18).build());

        // 保存 / 取消
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> confirm())
                .bounds(panelX + 10, panelY + 76, (panelW - 26) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> close())
                .bounds(panelX + panelW / 2 + 4, panelY + 76, (panelW - 26) / 2, 18).build());

        // 打开浮层就向服务端要「我的组列表」（收包回 applyGroups）
        ClientPacketDistributor.sendToServer(new ModelMakerPackets.RequestMyGroupsPayload());
    }

    private String groupLabel() {
        if (groups.isEmpty()) return "到：（单人组） ▸";
        return "到：" + groups.get(groupIdx) + " ▸";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257) {   // Enter = 确认
            confirm();
            return true;
        }
        return super.keyPressed(event);   // Esc 走基类取消
    }

    private void confirm() {
        // 客户端只表达意图：名字/组都以服务端清洗与校验为准
        String name = nameBox.getValue().trim();
        String group = groups.isEmpty() ? "" : groups.get(groupIdx);   // 空列表 = 组列表未到 → 服务端落单人组
        ClientPacketDistributor.sendToServer(
                new ModelMakerPackets.SaveBlueprintPayload(group, name));
        close();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, title, panelX + 10, panelY + 8, 0xFF404040);
    }
}
