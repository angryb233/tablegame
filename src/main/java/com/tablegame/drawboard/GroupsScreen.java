package com.tablegame.drawboard;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 「组管理」主界面（主菜单「组管理」/ {@code /tablegame group} 进入）。
 * 数据来自服务端 {@link BoardPackets.GroupsUiPayload} 快照（mine=我所在的真实组，joinable=我没加入的公开组；
 * 单人组=玩家名不落盘，本界面自拼首行）；每次操作后服务端重发快照 → {@link #applyData} 就地重建。
 * 布局：我的组（单人组 + 真实组）· 新建组（≤24 字符、禁空格与 / 反斜杠）· 可加入的公开组（点行即加入）· 返回。
 */
public class GroupsScreen extends Screen {
    private static final int MAX_SHOWN = 6;   // 每组区块最多直显行数，超出折叠为计数行（组多时防出屏）

    private final Screen previous;
    private final String me;                  // 我的玩家名（单人组=自己，客户端自拼）
    private List<BoardPackets.GroupUiPayload> mine = new ArrayList<>();
    private List<BoardPackets.GroupUiPayload> joinable = new ArrayList<>();
    private EditBox createBox;

    /** 两段列表各自的滚动状态 + 纵向范围（滚轮按落点决定滚哪段）。 */
    private final ListScroll scrollMine = new ListScroll(MAX_SHOWN);
    private final ListScroll scrollJoin = new ListScroll(MAX_SHOWN);
    private int mineLo, mineHi, joinLo, joinHi;

    public GroupsScreen(Screen previous, List<BoardPackets.GroupUiPayload> mine, List<BoardPackets.GroupUiPayload> joinable) {
        super(Component.literal("组管理"));
        this.previous = previous;
        this.me = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getName().getString() : "";
        this.mine = mine == null ? new ArrayList<>() : new ArrayList<>(mine);
        this.joinable = joinable == null ? new ArrayList<>() : new ArrayList<>(joinable);
    }

    /** 快照刷新入口（客户端收包后调用）：换数据并整屏重建。 */
    public void applyData(List<BoardPackets.GroupUiPayload> mine, List<BoardPackets.GroupUiPayload> joinable) {
        this.mine = mine == null ? new ArrayList<>() : new ArrayList<>(mine);
        this.joinable = joinable == null ? new ArrayList<>() : new ArrayList<>(joinable);
        clearWidgets();
        init();
    }

    @Override
    protected void init() {
        clearWidgets();
        mineLo = mineHi = joinLo = joinHi = 0;      // 本屏每次重建都重算范围（空段 → 恒 0，永远不命中）
        int cx = width / 2;
        int y = 56;

        // —— 我的组 ——
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "我的组", 0xFFA0A0A0));
        y += 16;
        // 单人组：永远第一行，灰色静态（无操作可做）
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 5, 280,
                (me.isEmpty() ? "(未知玩家)" : me) + "（我的单人组，画板仅自己可见）", 0xFF808080));
        y += 24;

        mineLo = y;                                 // 本段列表的纵向范围（滚轮落点判定用）
        scrollMine.setTotal(mine.size());
        for (int row = 0; row < scrollMine.rows(); row++) {
            final BoardPackets.GroupUiPayload g = mine.get(scrollMine.index(row));
            String tag = g.owner().equals(me) ? "组长" : (g.admins().contains(me) ? "管理员" : (g.readOnly().contains(me) ? "只读" : "成员"));
            String label = DrawBoardMenuUi.ellipsis(g.name(), 14)
                    + "  [" + tag + "] " + (g.open() ? "公开" : "私有")
                    + " · " + g.members().size() + "人";
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22, label, () ->
                    Minecraft.getInstance().setScreen(new GroupDetailScreen(this, g))));
            y += 26;
        }
        if (!scrollMine.label().isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 4, 280,
                    "滚轮查看更多　" + scrollMine.label(), 0xFF707070));
            y += 22;
        }
        mineHi = y;
        y += 6;

        // —— 新建组 ——
        addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "新建组（创建后为私有，可邀人加入）", 0xFFA0A0A0));
        y += 16;
        createBox = new EditBox(font, cx - 140, y, 200, 20, Component.literal("组名"));
        createBox.setMaxLength(BoardManager.MAX_GROUP);
        createBox.setHint(Component.literal("组名（≤" + BoardManager.MAX_GROUP + "字符）"));
        createBox.setResponder(s -> {   // 组名规则：剔除空格与 / 反斜杠（会进路径/指令参数）
            String clean = s.replace(" ", "").replace("/", "").replace("\\", "");
            if (!clean.equals(s)) createBox.setValue(clean);
        });
        addRenderableWidget(createBox);
        addRenderableWidget(DrawBoardMenuUi.button(cx + 66, y, 74, 20, "创建", this::createGroup));
        y += 32;

        // —— 可加入的公开组 ——
        if (!joinable.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280, "可加入的公开组（点行即加入）", 0xFFA0A0A0));
            y += 16;
            joinLo = y;
            scrollJoin.setTotal(joinable.size());
            for (int row = 0; row < scrollJoin.rows(); row++) {
                final BoardPackets.GroupUiPayload g = joinable.get(scrollJoin.index(row));
                String label = DrawBoardMenuUi.ellipsis(g.name(), 14)
                        + "  [组长 " + DrawBoardMenuUi.ellipsis(g.owner(), 8) + "] "
                        + g.members().size() + "人 · 公开";
                addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22, label,
                        () -> sendAction("join", g.name(), "", false)));
                y += 26;
            }
            if (!scrollJoin.label().isEmpty()) {
                addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 4, 280,
                        "滚轮查看更多　" + scrollJoin.label(), 0xFF707070));
                y += 22;
            }
            joinHi = y;
            y += 6;
        }

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y + 6, 280, 24, "返回", () ->
                Minecraft.getInstance().setScreen(previous)));
    }

    private void createGroup() {
        String name = createBox.getValue().trim();
        if (name.isEmpty()) {
            DrawBoardMenuUi.msg("[画板] 请先输入组名");
            return;
        }
        if (name.equals(me)) {
            DrawBoardMenuUi.msg("[画板] 组名不能与自己的单人组同名");
            return;
        }
        createBox.setValue("");
        sendAction("create", name, "", false);
    }

    /** 通用组操作：发包即等快照回来重建（成功=列表变，失败=聊天栏有原因、列表不变）。 */
    private void sendAction(String action, String group, String player, boolean flag) {
        ClientPacketDistributor.sendToServer(new BoardPackets.GroupActionPayload(action, group, player, flag));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        ListScroll target = (y >= mineLo && y <= mineHi) ? scrollMine
                : (y >= joinLo && y <= joinHi) ? scrollJoin : null;
        if (target != null && target.scroll(dy)) {
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
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 24, 0xFFFFFFFF);
    }
}
