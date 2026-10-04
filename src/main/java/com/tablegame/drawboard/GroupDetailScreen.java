package com.tablegame.drawboard;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.editor.ScrollBar;

    /**
     * 单个组的详情 / 管理界面（从 {@link GroupsScreen} 点组行进入）。
     *
     * <p>角色：组长（owner）＞ 管理员（admins）＞ 成员 / 只读成员；界面按我的身份显示可用操作
     * （组长：成员行按钮、公开⇄私有、邀请、解散；管理员：普通成员行按钮；成员：浏览，可退组）。
     * 只读成员在此不能改画板（画板权限在编辑器里按 canEdit 处理）。
     * 每次操作后服务端重发 {@link BoardPackets.GroupsUiPayload} 快照 → {@link #applyData} 就地重建；
     * 若本组已不在我的组里（退组 / 被踢 / 解散）→ 自动退回组列表。
     */
public class GroupDetailScreen extends Screen {
    private static final int MAX_SHOWN = 8;

    /** 成员列表滚动：一屏 MAX_SHOWN 行；滚轮换窗口后 init() 按 scroll.index(row) 重摆。 */
    private final ListScroll scroll = new ListScroll(MAX_SHOWN);    // 成员区最多直显行数（人多的组折叠尾部）
    /** 滚动条：轨道贴在行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            clearWidgets();
            init();
        }
        return true;
    }

    private static final int LABEL_W = 118;    // 成员行左侧名字区宽（余下放操作按钮）
    private static final int BTN_W = 52;       // 成员行操作按钮宽

    private final GroupsScreen listScreen;     // 返回目标（持最新快照，退回时列表是新的）
    private final String me;
    private List<BoardPackets.GroupUiPayload> mine = new ArrayList<>();
    private BoardPackets.GroupUiPayload group; // 当前展示的组（快照更新后从 mine 重新取）
    private EditBox inviteBox;
    private boolean armed = false;             // 二次确认通用：第一次点变提示文案，第二次才真发
    private String armedAction = "";          // armed=true 时待确认的动作标识（leave/disband）

    public GroupDetailScreen(GroupsScreen listScreen, BoardPackets.GroupUiPayload group) {
        super(Component.literal("组详情"));
        this.listScreen = listScreen;
        this.me = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getName().getString() : "";
        this.group = group;
    }

    /** 快照刷新：组还在 mine → 重建；不在了（退组/被移出/解散）→ 退回列表。 */
    public void applyData(List<BoardPackets.GroupUiPayload> mine, List<BoardPackets.GroupUiPayload> joinable) {
        this.mine = mine == null ? new ArrayList<>() : new ArrayList<>(mine);
        this.group = find(this.mine, group == null ? "" : group.name());
        armed = false;                          // 快照回来 = 上一动作已生效/失败，解除武装
        if (this.group == null) {               // 本组已消失：最新快照先喂给列表屏，再退回它
            listScreen.applyData(this.mine, joinable);
            Minecraft.getInstance().setScreen(listScreen);
            return;
        }
        clearWidgets();
        init();
    }

    private static BoardPackets.GroupUiPayload find(List<BoardPackets.GroupUiPayload> list, String name) {
        for (BoardPackets.GroupUiPayload g : list) {
            if (g.name().equals(name)) return g;
        }
        return null;
    }

    @Override
    protected void init() {
        clearWidgets();
        if (group == null) return;
        int cx = width / 2;
        int y = 52;
        boolean owner = group.owner().equals(me);
        boolean iAmAdmin = group.admins().contains(me);
        boolean canManage = owner || iAmAdmin;   // 组长或管理员：见管理按钮
        boolean roMe = group.readOnly().contains(me);
        boolean anyManageBtn = false;            // 记录本屏是否出现过管理按钮（决定提示行文案）

        // —— 组信息 ——
        String myTag = owner ? "组长" : (iAmAdmin ? "管理员" : (roMe ? "只读成员" : "成员"));
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280,
                DrawBoardMenuUi.ellipsis(group.name(), 18) + "  [" + myTag + "]", 0xFFD0D0D0));
        y += 14;
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280,
                "规则：" + (group.open() ? "公开" : "私有") + " ｜ 组长：" + DrawBoardMenuUi.ellipsis(group.owner(), 12)
                        + (group.admins().isEmpty() ? "" : " ｜ 管理员：" + DrawBoardMenuUi.ellipsis(String.join(", ", group.admins()), 14)),
                0xFF909090));
        y += 14;
        if (roMe) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "你在此组为只读（只能看/导出，不能修改画板）", 0xFFC08040));
            y += 14;
        }
        y += 6;

        // —— 成员列表 ——
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "成员", 0xFFA0A0A0));
        y += 15;
        scroll.setTotal(group.members().size());
        for (int row = 0; row < scroll.rows(); row++) {
            final String m = group.members().get(scroll.index(row));
            boolean isOwnerRow = m.equals(group.owner());
            boolean isAdminRow = group.admins().contains(m);
            boolean isSelf = m.equals(me);
            boolean ro = group.readOnly().contains(m);
            // 徽章：★组长 / ♦管理员 / 只读 / 可写
            String tag = isOwnerRow ? "★ 组长" : (isAdminRow ? "♦ 管理员" : (ro ? "只读" : "可写"));
            // 我能对这行做什么？组长能管所有人（除自己）；管理员只能管普通成员（除组长/其他管理员/自己）
            boolean canAct = !isSelf && !isOwnerRow && (owner || (iAmAdmin && !isAdminRow));
            String nameLabel = DrawBoardMenuUi.ellipsis(m, 11) + "  " + tag;
            if (!canAct) {
                addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 4, 280, nameLabel,
                        isOwnerRow ? 0xFFFFD980 : (isAdminRow ? 0xFF80D0FF : 0xFFC0C0C0)));
                y += 24;
                continue;
            }
            anyManageBtn = true;
            // 行 = 左侧名字区 + 右侧操作按钮串（按钮数按身份/目标变化）
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 4, LABEL_W, nameLabel, 0xFFC0C0C0));
            int bx = cx - 140 + LABEL_W + 2;
            if (owner && !isAdminRow && !ro) {   // 组长：普通可写成员行 → [只读] [任命] [踢出]
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, "只读",
                        () -> sendAction("readonly", group.name(), m, true)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, "任命",
                        () -> sendAction("admin", group.name(), m, true)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W + 10, 20, "踢出",
                        () -> sendAction("kick", group.name(), m, false)));
            } else if (owner && !isAdminRow && ro) {  // 组长：只读成员行 → [改可写] [任命] [踢出]
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, "可写",
                        () -> sendAction("readonly", group.name(), m, false)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, "任命",
                        () -> sendAction("admin", group.name(), m, true)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W + 10, 20, "踢出",
                        () -> sendAction("kick", group.name(), m, false)));
            } else if (owner) {                     // 组长：管理员行 → [撤销] [踢出]
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, "撤销",
                        () -> sendAction("admin", group.name(), m, false)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W + 10, 20, "踢出",
                        () -> sendAction("kick", group.name(), m, false)));
            } else {                                // 管理员：普通成员行 → [只读⇄可写] [踢出]
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W, 20, ro ? "可写" : "只读",
                        () -> sendAction("readonly", group.name(), m, !ro)));
                bx += BTN_W + 2;
                addRenderableWidget(DrawBoardMenuUi.button(bx, y, BTN_W + 10, 20, "踢出",
                        () -> sendAction("kick", group.name(), m, false)));
            }
            y += 24;
        }
        if (!scroll.label().isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 3, 280,
                    "滚轮查看更多　" + scroll.label(), 0xFF707070));
            y += 20;
        }
        if (anyManageBtn) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "（按钮直接生效，无二次确认）", 0xFF707070));
            y += 14;
        }
        y += 4;

        if (owner) {
            // —— 组长操作区：公开/私有 + 邀请 ——
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22,
                    group.open() ? "改为私有（仅限邀请）" : "改为公开（任何人可加入）",
                    () -> sendAction("rule", group.name(), "", !group.open())));
            y += 30;
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "邀请玩家（输入玩家名）", 0xFFA0A0A0));
            y += 15;
            inviteBox = new EditBox(font, cx - 140, y, 200, 20, Component.literal("玩家名"));
            inviteBox.setMaxLength(16);
            inviteBox.setHint(Component.literal("玩家名"));
            addRenderableWidget(inviteBox);
            addRenderableWidget(DrawBoardMenuUi.button(cx + 66, y, 74, 20, "邀请", this::invite));
            y += 30;
            // 解散（二次确认；服务端还有「组内有项目」安全阀）
            String disbandLabel = armed && armedAction.equals("disband")
                    ? "再点一次确认解散（组记录将删除）" : "解散该组…";
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22, disbandLabel, () -> {
                if (!armed || !armedAction.equals("disband")) {
                    armed = true;
                    armedAction = "disband";
                    clearWidgets();
                    init();
                    return;
                }
                armed = false;
                sendAction("disband", group.name(), "", false);
            }));
            y += 30;
        } else {
            // —— 成员操作：退组（二次确认） ——
            String leaveLabel = armed && armedAction.equals("leave")
                    ? "再点一次确认退组" : "退出该组";
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22, leaveLabel, () -> {
                if (!armed || !armedAction.equals("leave")) {
                    armed = true;
                    armedAction = "leave";
                    clearWidgets();
                    init();
                    return;
                }
                armed = false;
                sendAction("leave", group.name(), "", false);
            }));
            y += 30;
        }

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y + 6, 280, 24, "返回", () ->
                Minecraft.getInstance().setScreen(listScreen)));
    }

    private void invite() {
        String target = inviteBox.getValue().trim();
        if (target.isEmpty()) {
            DrawBoardMenuUi.msg("[画板] 请先输入要邀请的玩家名");
            return;
        }
        inviteBox.setValue("");
        sendAction("invite", group.name(), target, false);
    }

    /** 通用组操作：发包即等快照回来重建（组还在 mine → 本屏刷新；不在 → 自动退回列表）。 */
    private void sendAction(String action, String groupName, String player, boolean flag) {
        ClientPacketDistributor.sendToServer(new BoardPackets.GroupActionPayload(action, groupName, player, flag));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (scroll.scroll(dy)) {          // 滚轮换一屏窗口 → 重建行（屏小、行少，重建最省事）
            clearWidgets();
            init();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
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
        barX = Math.max(8, width - 12);
        listY = 52;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 52 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Component.literal(title.getString()
                + (scroll.label().isEmpty() ? "" : "　" + scroll.label())), width / 2, 22, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
}
