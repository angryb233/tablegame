package com.tablegame.table;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.host.ClientGameHandler;
import com.tablegame.net.DealerPackets;
import com.tablegame.net.GamePackets;
import com.tablegame.stage.StageScreen;

/**
 * 游戏台屏（右键 tablegame:dealer 打开）。两页，状态全部来自服务端台状态包（{@link DealerPackets.StatePayload}）
 * —— 客户端不存「选了哪款」，故同一台所有打开的屏看到的都一样。
 *
 * <ol>
 *   <li>列表页（还没选）：游戏列表 + 【进入游戏】</li>
 *   <li>总览页（已选）：游戏名 + 规模 + 简介 + 【运行】【进入游戏界面】【中断游戏】【重新选择游戏】</li>
 * </ol>
 */
public class DealerScreen extends Screen {

    private final net.minecraft.core.BlockPos pos;
    private List<GamePackets.GameInfoPayload> games;
    private String selected = "";            // 这台选中的游戏（服务端说）
    private String running = "";             // 这台正在跑的游戏（服务端说）
    private List<String> seats = List.of();
    private boolean joined = false;          // 收包的人是否已在那一局里
    private boolean paused = false;          // 那一局是挂起的吗（重进存档后非常驻局的常态）→ 【运行】变【继续游戏】
    private String note = "";                // 台要显示的一行提示（如「上次那局接不上，点【运行】重开」）
    private String pending = "";             // 列表页「已选中但还没提交」的那款（本地高亮，点【进入游戏】才落服务端）

    public DealerScreen(DealerPackets.StatePayload st) {
        super(Component.translatable("block.tablegame.dealer"));
        this.pos = st.pos();
        this.games = ClientGameHandler.games();
        applyState(st);
        if (games.isEmpty()) {
            ClientPacketDistributor.sendToServer(new GamePackets.RequestGamesPayload());
        }
    }

    /** 台状态到达 → 就地刷新（同 GamesScreen.applyData 的惯例）。 */
    public void applyState(DealerPackets.StatePayload st) {
        if (!pos.equals(st.pos())) return;                   // 别的台的包：不管
        this.selected = st.selected();
        this.running = st.running();
        this.seats = st.seats();
        this.joined = st.joined();
        this.paused = st.paused();
        this.note = st.note() == null ? "" : st.note();
        if (minecraft != null) { clearWidgets(); init(); }
    }

    public void applyData(List<GamePackets.GameInfoPayload> games) {
        this.games = games;
        clearWidgets();
        init();
    }

    /** 台坐标（客户端路由用：同一个台的包就地刷新，不同台的开新屏）。 */
    public net.minecraft.core.BlockPos tablePos() {
        return pos;
    }

    private boolean detailPage() {
        return !selected.isEmpty();
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        if (detailPage()) initDetail(cx);
        else initList(cx);
    }

    /** 总览页用：按**像素宽**折行（GUI 里换行只能量 font.width，不能数字符）。 */
    private static java.util.List<String> wrap(net.minecraft.client.gui.Font font, String text, int maxPx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                // ponytail: 逐字符重算宽度 = O(n²)，简介最多 600 字，量到卡再换增量法
                if (line.length() > 0 && font.width(line + "" + para.charAt(i)) > maxPx) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(para.charAt(i));
            }
            out.add(line.toString());
        }
        return out;
    }

    /** 列表页：点一款 = 选中它（本地高亮）；点【进入游戏】= 提交到服务端并跳总览页。 */
    private void initList(int cx) {
        int y = 56;
        for (int i = 0; i < games.size(); i++) {
            GamePackets.GameInfoPayload info = games.get(i);
            String name = info.name();
            boolean picked = name.equals(pending);
            Button b = Button.builder(Component.literal(picked ? "▶ " + name : name), btn -> {
                        pending = name;                      // 只改本地高亮 —— 整页重建，旧的自己会消失
                        clearWidgets();
                        init();
                    })
                    .bounds(cx - 140, y + i * 22, 280, 20)
                    .build();
            b.setAlpha(picked ? 1.0f : 0.75f);
            addRenderableWidget(b);
        }
        int by = height - 56;
        addRenderableWidget(Button.builder(Component.literal("进入游戏"), b -> {
                    if (pending.isEmpty()) return;           // 没选：文本行已经提示了
                    ClientPacketDistributor.sendToServer(new DealerPackets.SelectPayload(pos, pending));
                }).bounds(cx - 140, by, 280, 20).build());
    }

    /** 总览页：游戏名 + 规模 + 简介 + 四键。 */
    private void initDetail(int cx) {
        int by = height - 56;

        addRenderableWidget(Button.builder(Component.literal(paused ? "继续游戏" : "运行"), b -> {
                    // 挂起的那一局：这颗按钮 = 【继续游戏】——服务端接着跑（见 HostManager.dealerRun）。
                    if (running.isEmpty() || paused) {
                        ClientPacketDistributor.sendToServer(new DealerPackets.RunPayload(pos));
                    }
                }).bounds(cx - 140, by, 88, 20).build());

        addRenderableWidget(Button.builder(Component.literal(joined ? "回到游戏界面" : "进入游戏界面"), b -> {
                    if (joined) {                                // 已经在局里：本地直接回屏（数据都在缓存里）
                        net.minecraft.client.Minecraft.getInstance().setScreen(new StageScreen());
                        return;
                    }
                    ClientPacketDistributor.sendToServer(new DealerPackets.EnterPayload(pos));
                }).bounds(cx - 44, by, 88, 20).build());

        addRenderableWidget(Button.builder(Component.literal("中断游戏"), b ->
                        ClientPacketDistributor.sendToServer(new DealerPackets.AbortPayload(pos)))
                .bounds(cx + 52, by, 88, 20).build());

        addRenderableWidget(Button.builder(Component.literal("重新选择游戏"), b -> {
                    // 真的把台子的选中清掉（服务端权威）——不然关掉重开又跳回总览页，等于取消不了
                    ClientPacketDistributor.sendToServer(new DealerPackets.SelectPayload(pos, ""));
                }).bounds(cx - 140, by + 24, 280, 20).build());
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g,
            int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = width / 2;
        if (!detailPage()) {
            g.centeredText(font, title, cx, 24, 0xFFFFFFFF);
            g.centeredText(font, games.isEmpty() ? "（还没有游戏 —— 先用编辑器建一个）"
                            : (pending.isEmpty() ? "选一款游戏，再点【进入游戏】" : "已选：" + pending),
                    cx, 42, 0xFFA0A0A0);
            return;
        }
        g.centeredText(font, Component.literal("《" + selected + "》"), cx, 22, 0xFFFFE080);
        g.centeredText(font, "总览", cx, 40, 0xFFA0A0A0);
        GamePackets.GameInfoPayload info = infoOf(selected);
        int y = 56;
        if (info != null) {
            g.centeredText(font, "卡牌 " + info.cardCount() + " 张 · 牌堆 " + info.deckCount() + " 个", cx, y, 0xFFFFFFFF);
            y += 16;
        }
        // 简介：编辑器总览页底部通栏填的，按像素宽折行、最多 6 行
        String desc = info == null || info.desc() == null ? "" : info.desc().trim();
        if (desc.isEmpty()) {
            g.centeredText(font, "（还没写简介 —— 打开编辑器，总览页最下面那栏可以填）", cx, y, 0xFF909090);
            y += 14;
        } else {
            java.util.List<String> lines = wrap(font, desc, Math.min(300, width - 40));
            for (int i = 0; i < lines.size() && i < 6; i++) {
                boolean more = i == 5 && lines.size() > 6;
                g.centeredText(font, lines.get(i) + (more ? "…" : ""), cx, y, 0xFFD0D0D0);
                y += 11;
            }
            y += 6;
        }
        if (paused) {
            g.centeredText(font, "本台有一局《" + running + "》已挂起（退出存档前没打完）· 局内 " + seats.size() + " 人",
                    cx, y, 0xFFFFD060);
            g.centeredText(font, "点【继续游戏】接着跑，或【中断游戏】不要它", cx, y + 12, 0xFFA0A0A0);
        } else if (!running.isEmpty()) {
            g.centeredText(font, "本台正在运行《" + running + "》· 局内 " + seats.size() + " 人",
                    cx, y, 0xFF88D888);
        } else {
            g.centeredText(font, "本台未开局 —— 点【运行】开一桌", cx, y, 0xFF909090);
        }
        if (!note.isEmpty()) {                              // 台级提示（重进存档接不上那一局的理由）
            g.centeredText(font, "⚠ " + note, cx, y + 26, 0xFFFFA040);
        }
    }

    private GamePackets.GameInfoPayload infoOf(String name) {
        for (GamePackets.GameInfoPayload g : games) {
            if (g.name().equals(name)) return g;
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;                                        // 联机屏不暂停世界
    }
}
