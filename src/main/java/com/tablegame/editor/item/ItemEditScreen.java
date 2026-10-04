package com.tablegame.editor.item;

import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptCard;
import com.tablegame.script.edit.ScriptEdit;
import com.tablegame.script.edit.ScriptGraph;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.ColorText;
import com.tablegame.core.ComponentGroup;
import com.tablegame.core.ComponentModel;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.ItemText;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.IdPickScreen;
import com.tablegame.editor.data.LootCanvasScreen;
import com.tablegame.editor.script.NodeEditScreen;
import com.tablegame.net.AssetPackets;

/**
 * **物品编辑页**：自定义物品的**显示名 + 描述**在这一个页里改完，不再分两个小窗。
 * 版式与「组件库」屏同族：左栏 {@link EditorRail}（本页无二级页签，【返回】跟页签后、【完成】【取消】常驻栏底）+ 内容一律从 {@link EditorRail#LEFT} 起排。
 * 三点：① **只发改过的那栏** —— 名字变了发 {@link AssetPackets.RenameAssetPayload}、描述变了发 {@link AssetPackets.SetItemLorePayload}（两包现成、服务端零改动），
 * 按 {@code @} 前缀分流「库里的 / 项目自己那份」⇒ 两个入口（组件库屏 / 编辑器「组件」页）通用。
 * ② **先回宿主屏再发包**：回执路由按「此刻屏上是什么」分派，编辑页开着时那两个包**收不到** —— 顺序反了就是「改完看着没变」。
 * ③ 物品的**外观**是基底原版物品（不做贴图），本页只管显示名与描述，右侧照实画一遍预览（图标 + 名字 + 描述行，{@code &} 上色生效）。
 */
public class ItemEditScreen extends Screen implements EditorToolScreen {

    private final Screen parent;
    /** 这条资产在哪个库（项目自己那份 = {@code @游戏名}，服务端认这个前缀分流）。 */
    private final String pack;
    private final String id;
    /** 基底原版物品 id（预览图标用；认不出 = 不画图标）。 */
    private final String base;
    /** 进页时的原值 —— 记着它才知道哪一栏动过（没动的那栏不发包）。 */
    private final String initName;
    private final List<String> initLore;
    /**
     * 这一条是**文本对象**时的点击标记原值 —— {@code null} = 不是文本：不摆【标记】栏、
     * 描述那栏照旧叫「描述」。文本对象没有基底、也不是物品：正文走 lore（声明里的 {@code body}）、
     * 点击标记走声明里的 {@code mark}（发给玩家的那条消息挂命令 `tablegame pick 标记`，点了就回投 on pick）。
     */
    private final String mark;
    /** 标记输入框的当前值（只在文本对象上有这一栏）。 */
    private String liveMark;

    private EditBox nameBox, idBox, markBox;
    /** 描述框：**多行** —— 分行用回车；老写法 {@code |} 也认。 */
    private MultiLineEditBox loreBox;
    /**
     * 生成脚本段要的回口（**可空**）：对象页那三个入口（物品 / 方块 / 实体）把它传进来；
     * 组件库 / 组件页那边进这个屏传 {@code null} —— 那里不摆「条件 / 事件」栏（它们只改名字与描述）。
     */
    private final GameEditorScreen editor;
    /** 「事件 / 条件」两个下拉（复用组件库那件菜单件）+ 当前选中的事件（入口 + 守卫 + 人话）与附加条件。 */
    private final AssetMenu evMenu = new AssetMenu(), condMenu = new AssetMenu();
    private final AssetMenu lootMenu = new AssetMenu();          // 掉落表下拉（只列 type 匹配的表）
    private String liveLootTable = "";                            // 这条挂着哪张表（空 = 没挂）
    private net.minecraft.client.gui.components.AbstractWidget lootBtn;   // 那一行按钮（改完就地换文案，不重开屏）
    private String evOn = "", evGuard = "", evLabel = "";
    private String condExtra = "";
    /** 输入框当前值（预览用；EditBox 不持久，值随输入进这两个字段）。 */
    private String liveName, liveLore;
    /** 资产名输入框的当前值（只在对象页入口有这一栏；组件库那边不摆）。 */
    private String liveId;
    /** 预览区左上角（init 里算好，渲染时用）。 */
    private int previewX, previewY;
    /** 【属性】段分割线的 y（init 里按表单排完的高度算）。 */
    private int sectY;
    /** 属性段的行（一行一个组件，值原文）—— null = 还没读过。 */
    private java.util.List<ComponentModel.Row> propRows;
    /** 属性段的入场原值（对账用：跟现值一样就不动脚本）。 */
    private String initRows = "";
    /** 属性段的滚动（行多了一屏摆不下）：位置 / 可见行数 / 列表起排 x 与宽（init 里填）。 */
    private ListScroll propScroll = new ListScroll(3);
    /** 属性段列表的上边 y（= 分割线下方）。 */
    private int propTop;
    /** 属性段一屏能摆几行（按窗口高度算）。 */
    private int propFits = 1;
    /** 属性段起排 x / 宽（滚动条贴它右边排）。 */
    private int propX, propW;
    /** 内容列宽（init 里按窗口算：底部状态行按它截断，窄窗不越界）。 */
    private int contentW = 300;
    /** 这一屏够不够宽摆预览（窄窗整块不摆，省得半行字出屏）。 */
    private boolean previewOn = true;
    /** 正在拖滚动条。 */
    private boolean propDrag;
    /** 描述框高度（多行框）。 */
    private static final int LORE_H = 56;
    /** 属性行行高（一行一个组件）。 */
    private static final int ROW_H = 20;
    /** 描述框**后（右）边**的标记竖槽偏移：真行开头画点、折行的续行画竖条。 */
    private static final int LORE_GUT = 10;
    /** 本页就地提示（名字为空这类；不进聊天栏）。 */
    private String status = "";

    /** @param parent 上一屏（完成 / 取消 / 返回都回它）· @param pack 库名（{@code @游戏名} = 项目自己那份）· @param id 资产 id
     *  @param base 基底原版物品 id（可空）· @param name 当前显示名 · @param lore 当前描述行
     *  @param editor 能生成脚本段的编辑器（**可空** —— null 就不摆「条件 / 事件」栏） */
    public ItemEditScreen(Screen parent, String pack, String id, String base, String name, List<String> lore,
                   GameEditorScreen editor) {
        this(parent, pack, id, base, name, lore, editor, null);   // 老形状：不是文本对象
    }

    /** 八参那条 = **文本对象**：{@code mark} 非 null ⇒ 按「文本」渲染（【正文】= {@code body}、【标记】= {@code mark} 两栏，不摆【条件 · 事件】栏 —— 文本的事件就是「玩家点它」，脚本里自己写 {@code if (pick == "标记")}）。
     *  @param mark 点击标记（非 null 才按文本渲染；空串 = 文本但还没写标记 ⇒ 发出去但不可点） */
    public ItemEditScreen(Screen parent, String pack, String id, String base, String name, List<String> lore,
                   GameEditorScreen editor, String mark) {
        super(Component.literal("编辑物品"));
        this.parent = parent;
        this.editor = editor;
        this.pack = pack;
        this.id = id;
        this.base = base == null ? "" : base;
        this.initName = name == null ? "" : name;
        this.initLore = lore == null ? List.of() : List.copyOf(lore);
        this.liveName = initName;
        this.liveId = id;
        this.liveLore = String.join("\n", initLore);          // 多行框：一行一个元素（2026-09-25）
        this.mark = mark;                                          // null = 不是文本对象（老形状）
        this.liveMark = mark == null ? "" : mark;
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        clearWidgets();
        // 左栏（本页无二级页签）：【返回】跟在页签之后，【完成】【取消】常驻栏底
        //（动作按钮全进左栏、放靠下的位置，内容区不再占一行摆按钮）
        int railBottom = EditorRail.buildGeneric(new String[0], null, t -> { }, this::addRenderableWidget);
        addRenderableWidget(DrawBoardMenuUi.button(4, railBottom + 6, EditorRail.W, 18, "返回", this::back));
        if (editor != null && mark == null) {
            // 属性段的两枚入口：位置 = 栏内 3/5 高度处，
            // 【＋事件】紧贴它下面；矮窗整体往上夹一下（别压到【完成】）
            int byAttr = Math.min(height * 3 / 5, height - 88);
            addRenderableWidget(DrawBoardMenuUi.button(4, byAttr, EditorRail.W, 18, "＋组件", this::openAttrPicker));
            addRenderableWidget(DrawBoardMenuUi.button(4, byAttr + 20, EditorRail.W, 18, "＋事件", this::addEvent));
        }
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 46, EditorRail.W, 18, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 24, EditorRail.W, 18, "取消", this::back));

        // ---------- 分辨率自适应 ----------
        // 全部只由 width / height 推，不再写死坐标：
        //   · 内容列宽先按「右边给预览列留 ~208」算，再夹到 [180, 420]
        //   · 预览列贴着内容列右边（宽窗仍钉最右 width-212 ⇒ 与旧观感一致）
        //   · 矮窗（< 320）：行距收紧、描述框瘦身，属性段才不会被挤没
        liveLootTable = currentLootTable();
        int x = EditorRail.LEFT;
        contentW = Math.min(420, Math.max(180, width - x - 232));
        int fw = contentW;
        previewX = Math.min(Math.max(x + fw + 24, width - 212), x + fw + 340);   // 贴内容列右边 / 宽窗钉最右 / 但不离太远
        previewY = 34;
        previewOn = width - previewX >= 90;              // 太窄就不摆预览（省得半行字出屏）
        boolean tight = height < 320;
        int gap = tight ? 32 : 38;                       // 一栏占的高度（标签 12 + 框 18 + 间距）
        int loreH = tight ? Math.max(24, height / 8) : Math.min(LORE_H, Math.max(28, height / 6));
        int ty = tight ? 26 : 34;
        if (editor != null) {                            // 对象页入口才多一行【资产名】（组件库那边不摆）
            addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "资产名", 0xFF909090));
            idBox = new EditBox(font, x, ty + 12, 140, 18, Component.literal("资产名"));   // 短框：资产名是标识符，不长
            idBox.setMaxLength(64);
            idBox.setValue(liveId);          // ★ 入场必须把**现在的**资产名摆出来 —— 框空着按【完成】就会撞
                                             //   「资产名不能为空」直接 return（显示名/描述/属性/组件跟着一个字都写不进去）
            // ②c：老条目（没有脚本声明、资产名=显示名）进编辑页预填一个**建议资产名**
            // = 基底路径 + 序号（默认规则，如 wooden_shovel2）—— 按一次【完成】即补出声明，它就不再是老条目。
            // ⚠ 只改框里的字，`liveId` 保持它现在的身份（掉落表那条链拿 liveId 找表头）；两处 setValue 都不碰 liveId
            //   （responder 还没设 —— `EditBox.setValue` 会回灌 responder）。
            if (isLegacyEntry()) {
                idBox.setValue(suggestId());
            }
            idBox.setResponder(s -> liveId = s);
            addRenderableWidget(idBox);
            ty += gap;
        }
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "显示名", 0xFF909090));
        nameBox = new EditBox(font, x, ty + 12, 140, 18, Component.literal("显示名"));   // 与资产名同宽（2026-09-25 用户定）
        nameBox.setMaxLength(64);
        nameBox.setValue(liveName);
        nameBox.setResponder(s -> liveName = s);          // 输入即带进预览（不落盘，完成时才写）
        addRenderableWidget(nameBox);

        ty += gap;
        addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, mark == null ? "描述" : "正文", 0xFF909090));
        // 描述 = **多行框**：分行就用**回车**，不再手写 `|`（老写法 `|` 照样认）
        loreBox = MultiLineEditBox.builder()
                .setX(x)
                .setY(ty + 12)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(font, Math.min(240, fw), loreH, Component.literal("描述"));   // 宽度 = 显示名那一档（2026-09-25 用户定）
        loreBox.setCharacterLimit(500);
        loreBox.setValue(liveLore);
        addRenderableWidget(loreBox);
        ty += 12 + loreH + 10;                     // 描述框占掉的高度（下文接着往下排）

        if (mark != null) {
            // 文本对象的**点击标记**：say_mark 发出去的那条消息挂着
            // runCommand("/tablegame pick 标记")，玩家点它 → 回投一次 on pick（内建值 pick = 这里的值）。
            // 留空 = 发出去但不可点（不做假动作：没标记就没有点击）。
            addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "标记", 0xFF909090));
            markBox = new EditBox(font, x, ty + 12, Math.min(240, fw), 18, Component.literal("标记"));
            markBox.setMaxLength(64);
            markBox.setValue(liveMark);
            markBox.setResponder(s -> liveMark = s);
            addRenderableWidget(markBox);
            ty += gap;
        }
        // 掉落表：只有方块 / 实体挂得上原版表 —— 下拉只列 type 匹配的表，「▸ 编辑」跳表画布。
        if (canHoldLoot()) {
            ty += 4;
            addRenderableWidget(DrawBoardMenuUi.label(x, ty, fw, "掉落表", 0xFF909090));
            int menuY = ty + 32;                                  // lambda 只能捕 final —— 先取出来
            lootBtn = DrawBoardMenuUi.button(x, ty + 12, Math.min(200, fw), 18, lootLabel(), () -> openLootMenu(x, menuY));
            addRenderableWidget(lootBtn);
            addRenderableWidget(DrawBoardMenuUi.button(x + Math.min(200, fw) + 6, ty + 12, 56, 18, "▸ 编辑", this::openLootCanvas));
            ty += gap;
        }
        if (previewOn) {
            addRenderableWidget(DrawBoardMenuUi.label(previewX, previewY - 14, 200, "预览", 0xFF909090));
        }

        // 【属性】段分割线：线身从内容区左边拉到屏幕右边，段名写在线的左端。
        sectY = ty + 6;
        // 线下面 = **属性段**：一行一个组件（中文名 + 内联小控件 + 删），
        // 底部【＋ 添加属性…】开拾取屏（候选从注册表现读）；写回仍走 setDeclBlock 那一条唯一出口。
        // 只有「对象页入口 + 非文本对象」才摆（组件库那边改的是档里的条目；文本对象不是物品）。
        if (editor != null && mark == null) {
            if (propRows == null) {                    // 入场读一次；之后以 propRows 为准
                propRows = new java.util.ArrayList<>(ComponentModel.parse(
                        ScriptEdit.blockText(editor.def.script(), declKindOf(), id, "components")));
                initRows = ComponentModel.toText(propRows);
            }
            propX = x;
            propW = fw;
            // 事件行：跟组件行**摆在一起**、不要单独的分割线；**一条事件一行**；
            // **有没有事件由脚本现算**（退出编辑页再进来照样在）。
            java.util.List<ScriptEdit.Event> evRows = eventsOfIn();
            for (int i = 0; i < evRows.size(); i++) {
                buildEventRow(x, sectY + 16 + i * ROW_H, fw, evRows.get(i));
            }
            propTop = sectY + 16 + evRows.size() * ROW_H;
            propFits = Math.max(1, (height - 34 - propTop) / ROW_H);
            int keep = propScroll.top();               // ★ 重建时保住滚动位置（不然滚一格就弹回顶）
            propScroll = new ListScroll(propFits);
            propScroll.setTotal(propRows.size());
            propScroll.setTop(keep);
            for (int r = 0; r < propScroll.rows(); r++) {
                buildPropRow(propX, propTop + r * ROW_H, propScroll.index(r), propW);
            }
        }
    }

    /** **事件行**：跟组件行**摆在一起**（属性段里一行，不单独加分割线）—— 点它**直接进「事件编辑」**（那一屏的动作卡列表 = 那个入口 {@code on X} 块里的卡）。
     *  一段事件**一行**（多个事件 = 同一个 {@code on} 块里**并列的多条 if 分支**）；有没有事件**由脚本现算**，退出编辑页再进来照样在。 */
    private void buildEventRow(int x, int y, int fw, ScriptEdit.Event ev) {
        int bw = Math.max(80, Math.min(fw, 340) - 24);
        addRenderableWidget(DrawBoardMenuUi.button(x, y, bw, ROW_H - 2,
                "事件： " + DrawBoardMenuUi.ellipsis(ev.label(), 12) + " ▸ 编辑",
                () -> openEventCards(ev)));
        addRenderableWidget(DrawBoardMenuUi.button(x + bw + 4, y, 20, ROW_H - 2, "✕",
                () -> delEvent(ev)));
    }

    /**
     * 事件行尾的 ✕：**只删这一条**（那个 `on X` 块里条件正好等于它守卫的那张 {@code if}，连里面的卡一起）
     * —— 别的方块的事件、块里别的语句一个不动。删完可以在【脚本】页点【↶ 撤销】退回来（全局历史）。
     */
    private void delEvent(ScriptEdit.Event ev) {
        if (editor == null || ev == null) {
            return;
        }
        try {
            int onLine = -1;
            for (ScriptGraph.Node n : ScriptGraph.build(Parser.parse(editor.def.script())).nodes()) {
                if (n.key().equals("on:" + ev.on())) {
                    onLine = n.line();
                    break;
                }
            }
            if (onLine < 0) {
                status = "图上找不到「on " + ev.on() + "」这个入口节点，没动";
                return;
            }
            ScriptEdit.Result r = ScriptCard.removeGuard(editor.def.script(), onLine, 0, ev.guard());
            String err = editor.applyScript(r.text());
            status = err.isEmpty() ? r.note() : err;
            DrawBoardMenuUi.msg("[" + label() + "] " + status);
            rebuild();
        } catch (Ast.ScriptError e) {
            status = "脚本有语法错，先去【脚本】页修：" + e.getMessage();
        }
    }

    /** 这一条对象现在的事件（现算；顺序 = 候选表顺序；空表 = 还没有事件）。 */
    private java.util.List<ScriptEdit.Event> eventsOfIn() {
        return editor == null ? java.util.List.of()
                : ScriptEdit.eventsOf(editor.def.script(), label(), base, id);
    }

    /**
     * 事件行点进去那一屏：**「事件编辑」** = 这个入口那个 `on X` 块的动作卡页
     * （{@link NodeEditScreen} 的入口页 —— 加卡 / 改参数 / 展开容器卡都在那儿）。
     * 行号与节点键**从图上现取**（与画布双击进去是同一个口径，不会指错块）；
     * **返回目标 = 本屏**（跳过去以后返回要回到这条对象的编辑页）。
     */
    private void openEventCards(ScriptEdit.Event ev) {
        if (ev == null || editor == null) {
            return;
        }
        try {
            for (ScriptGraph.Node n : ScriptGraph.build(Parser.parse(editor.def.script())).nodes()) {
                if (n.key().equals("on:" + ev.on())) {
                    Minecraft.getInstance().setScreen(
                            NodeEditScreen.of(editor, this, n.key(), n.label(), n.kind(), n.line(), ev));
                    return;
                }
            }
            status = "图上找不到「on " + ev.on() + "」这个入口节点";
        } catch (Ast.ScriptError e) {
            status = "脚本有语法错，先去【脚本】页修：" + e.getMessage();
        }
    }

    /**
     * 【＋事件】：**加一个这样的事件框**—— 取候选表里**还没建**的第一条
     * （方块 踩到它 → 看向它 → 右键它；物品 右键用它 → 潜行右键用它；实体 右键它 → 打死它），
     * 建完就地重排（新行出现在属性段里）；都用过了就给一句提示，不硬造重复的。
     */
    private void addEvent() {
        if (editor == null) {
            return;
        }
        java.util.List<ScriptEdit.Event> have = eventsOfIn();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<ScriptEdit.Event> left = new java.util.ArrayList<>();
        for (ScriptEdit.Event c : ScriptEdit.eventCandidates(label(), base, id)) {
            if (!have.contains(c)) {
                labels.add(c.label());
                left.add(c);
            }
        }
        if (left.isEmpty()) {
            status = "这一类的触发都用过了（" + have.size() + " 条）—— 要更多就在【脚本】里手写";
            rebuild();
            return;
        }
        // **自己选**要哪种触发（弹一列没建过的），不再「点一次给下一条」的轮换。
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        for (ScriptEdit.Event c : left) {
            acts.add(() -> {
                genTrigger(c.on(), c.guard(), c.label());   // 生成 + 落脚本 + 回执（唯一出口）
                rebuild();
            });
        }
        evMenu.open(width, height, 4, height - 120, labels, acts);
    }

    // ==================== ⏸ 保留：对象感知的「自动拼守卫」菜单 ====================
    // 属性段里一行事件 → 直接进「事件编辑」屏 ⇒ 这套菜单式选择（选择事件 / 条件 / 生成到脚本）暂时**不摆上屏**；
    // 触发清单与守卫的**唯一真源**在 {@link ScriptEdit#eventCandidates}（这套菜单也吃它，不会各写一份）。
    // ⚠ **不是死代码，别当死代码删** —— 要摆回来挂个按钮就行（openEventMenu / openCondMenu / genRule 的 never used 是故意的；`tools/deadcode/scan_dead.py` 照这条放行）。

    // ==================== 属性段 ====================

    /**
     * 摆一行属性：中文名（表里没有就显示 id）· 按类型给的内联小控件 · 删除。
     *
     * <p>三类简单值**就地编**（数字 = 小输入框 · 真假 = 开/关 · 枚举 = 点一下切下一个）；
     * 复杂值（附魔 / 属性修饰符 / 食物…）行里只显示摘要，点它开多行小窗改 —— 那种值本来就该带缩进地写。
     */
    private void buildPropRow(int x, int y, int i, int fw) {
        ComponentModel.Row row = propRows.get(i);
        ComponentModel.Spec spec = ComponentModel.specOf(row.id());
        // 分辨率自适应：名字列与控件宽都按内容列宽推（窄窗 ✕ 不会压到控件上）
        int nw = Math.max(56, Math.min(96, fw / 2));
        int cw = Math.max(48, fw - nw - 30);
        int cx = x + nw + 4;
        addRenderableWidget(DrawBoardMenuUi.label(x, y + 4, nw,
                DrawBoardMenuUi.ellipsis(spec.cn(), Math.max(4, nw / 6)), 0xFFC8C8D0));
        if (ComponentGroup.isGroup(row.id())) {
            // 附加类组件（值里套一串材料的那些）：这一行开「条目」小窗 —— 条目点开改、✕ 删、底下加
            java.util.List<java.util.List<String>> es = ComponentGroup.parse(ComponentGroup.specOf(row.id()), row.value());
            String lab = es == null ? "值认不出 ▸"
                    : (es.isEmpty() ? "还没条目 ＋ ▸"
                            : es.size() + " 条：" + ComponentGroup.summary(es.get(0)) + " ▸");
            addRenderableWidget(DrawBoardMenuUi.button(cx, y, cw, 18,
                    DrawBoardMenuUi.ellipsis(lab, Math.max(8, cw / 6)), () -> openGroup(i)));
        } else switch (spec.kind()) {
            case NUM -> {
                EditBox b = new EditBox(font, cx, y, Math.min(64, cw), 18, Component.literal(row.id()));
                b.setMaxLength(12);
                b.setValue(row.value().strip());
                b.setResponder(s -> setRowValue(i, s));
                addRenderableWidget(b);
            }
            case BOOL -> {
                boolean on = isTrue(row.value());
                addRenderableWidget(DrawBoardMenuUi.button(cx, y, Math.min(56, cw), 18, on ? "开" : "关",
                        () -> {
                            setRowValue(i, on ? "false" : "true");
                            rebuild();
                        }));
            }
            case ENUM -> addRenderableWidget(DrawBoardMenuUi.button(cx, y, Math.min(150, cw), 18,
                    DrawBoardMenuUi.ellipsis(enumCur(row.value()), 18), () -> {
                        setRowValue(i, "\"" + nextEnum(spec, enumCur(row.value())) + "\"");
                        rebuild();
                    }));
            default -> addRenderableWidget(DrawBoardMenuUi.button(cx, y, cw, 18,
                    DrawBoardMenuUi.ellipsis(row.value().strip().replace('\n', ' '), 28),
                    () -> openAttrText(i)));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x + fw - 22, y, 20, 18, "✕", () -> {
            propRows.remove(i);
            rebuild();
        }));
    }

    /** 改一行组件的值（就地换那一行）。 */
    private void setRowValue(int i, String value) {
        if (propRows != null && i >= 0 && i < propRows.size()) {
            propRows.set(i, new ComponentModel.Row(propRows.get(i).id(), value));
        }
    }

    private static boolean isTrue(String v) {
        return v != null && v.strip().equals("true");
    }

    /** 枚举那一行现在的值（声明里写 `"minecraft:rare"`，界面只显示 `rare`）。 */
    private static String enumCur(String v) {
        String t = v == null ? "" : v.strip();
        return t.length() > 1 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    /** 点一下切到下一个候选值（循环；都不匹配就从第一个开始）。 */
    private static String nextEnum(ComponentModel.Spec spec, String cur) {
        java.util.List<String> vs = spec.values();
        if (vs == null || vs.isEmpty()) {
            return cur;
        }
        for (int k = 0; k < vs.size(); k++) {
            if (vs.get(k).equals(cur)) {
                return vs.get((k + 1) % vs.size());
            }
        }
        return vs.get(0);
    }

    /** 【＋ 添加属性…】：开拾取屏（候选 = 注册表现读），选完加一行、重建。 */
    private void openAttrPicker() {
        Minecraft.getInstance().setScreen(IdPickScreen.components(this, picked -> {
            propRows.add(new ComponentModel.Row(picked, ComponentModel.defaultSource(picked)));
            rebuild();
        }));
    }

    /**
     * 附加类组件（`attribute_modifiers` 这种值里套一串材料的）：开「**条目**」小窗
     * （{@link ComponentListScreen}）—— 一条 = 一格一格的字段（数字 / 真假 / 枚举 / 引用 / 清单 / 文本），
     * 条目点开改、行尾 ✕ 删、底下【＋ 加一条】。换算全在 {@link ComponentGroup}（纯逻辑、进自检）。
     */
    private void openGroup(int i) {
        ComponentModel.Row row = propRows.get(i);
        Minecraft.getInstance().setScreen(new ComponentListScreen(this, row.id(), row.value(), v -> {
            setRowValue(i, v);
            rebuild();
        }));
    }

    /** 复杂值（可能多行）：开多行小窗改那一行。 */
    private void openAttrText(int i) {
        ComponentModel.Row row = propRows.get(i);
        Minecraft.getInstance().setScreen(new ComponentTextScreen(this,
                "组件：" + ComponentModel.cnOf(row.id()) + "（" + row.id() + "）", row.value(), v -> {
                    setRowValue(i, v);
                    rebuild();
                }));
    }

    /** 就地重建这一屏的控件（行增删 / 值换了类型之后）。 */
    private void rebuild() {
        clearWidgets();
        init();
    }

    // ---------- 属性段的滚动（滚轮 + 右侧滚动条）----------

    /**
     * 属性段滚轮：**只在鼠标落在列表区域时才吃**（指在描述框上时让原版那件自己滚——
     * 不然「滚描述」会变成「滚属性」）。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (lootMenu.isOpen() && lootMenu.scrollBy(dy)) {
            return true;
        }
        if (evMenu.isOpen() && evMenu.scrollBy(dy)) {
            return true;
        }
        if (condMenu.isOpen() && condMenu.scrollBy(dy)) {
            return true;
        }
        if (inPropArea(mx, my) && propScroll.scroll(dy)) {
            rebuild();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** 列表区域（含右侧滚动条那一条）。 */
    private boolean inPropArea(double mx, double my) {
        return propRows != null && mx >= propX && mx <= propBarX() + 6
                && my >= propTop && my <= propTop + propFits * ROW_H;
    }

    /** 滚动条那一条的 x（列表右外侧留 6px 空档）。 */
    private int propBarX() {
        return propX + propW + 6;
    }

    /** 轨道高（= 可见行数占的高度）。 */
    private int propTrackH() {
        return propFits * ROW_H;
    }

    /** 滑块高（最少 12px，行数多到看不见时也抓得住）。 */
    private int propThumbH() {
        int total = propRows == null ? 0 : propRows.size();
        return total <= propFits ? propTrackH() : Math.max(12, propTrackH() * propFits / total);
    }

    /** 滑块 y（按当前行比例）。 */
    private int propThumbY() {
        int total = propRows == null ? 0 : propRows.size();
        int free = Math.max(1, total - propFits);
        return propTop + (propTrackH() - propThumbH()) * propScroll.top() / free;
    }

    private boolean inPropBar(double mx, double my) {
        return propFits > 0 && propRows != null && propRows.size() > propFits
                && mx >= propBarX() - 2 && mx <= propBarX() + 6
                && my >= propTop && my <= propTop + propTrackH();
    }

    /** 点 / 拖滚动条：按比例定位（顶 = 第一行、底 = 最后一行）。 */
    private void dragPropTo(double my) {
        int total = propRows == null ? 0 : propRows.size();
        if (total <= propFits) {
            return;
        }
        int track = propTrackH() - propThumbH();
        int rel = (int) Math.max(0, Math.min(track, my - propTop - propThumbH() / 2.0));
        int want = track <= 0 ? 0 : (int) Math.round((double) rel * (total - propFits) / track);
        if (want != propScroll.top()) {
            propScroll.setTop(want);
            rebuild();
        }
    }

    /** 属性段右侧的滚动条（行数超一屏才画；杆透明不画，只画轨 + 块）。 */
    private void drawPropBar(GuiGraphicsExtractor g) {
        if (propFits <= 0 || propRows == null || propRows.size() <= propFits) {
            return;                                       // 不超一屏：不画（省得摆一个假条）
        }
        int bx = propBarX();
        g.fill(bx, propTop, bx + 3, propTop + propTrackH(), 0xFF21242B);
        g.fill(bx, propThumbY(), bx + 3, propThumbY() + propThumbH(), 0xFF8A9A6A);
    }

    /**
     * 事件菜单：按**这一条是哪一类**给候选。每一项 = 「入口 + 这一下是它的守卫」——
     * 守卫里判身份用的值都必须**真存在**（方块 {@code block} / 看向 {@code look_block} /
     * 实体 {@code eid}+{@code edead} / 物品 {@code hand_asset}），不吹牛。
     */
    private void openEventMenu(int mx, int my) {
        java.util.List<String> ls = new java.util.ArrayList<>();
        java.util.List<Runnable> as = new java.util.ArrayList<>();
        for (ScriptEdit.Event e : ScriptEdit.eventCandidates(label(), base, id)) {
            ls.add(e.label());
            as.add(() -> setEvent(e.on(), e.guard(), e.label()));
        }
        evMenu.open(width, height, mx, my, ls, as);
    }

    /**
     * 事件栏里「选择事件」选完**只记状态**（在栏里设置各种东西，
     * 生成留给栏里的【生成到脚本】—— 别点一下就把脚本改了）。
     */
    private void setEvent(String on, String guard, String label) {
        evOn = on;
        evGuard = guard;
        evLabel = label;
        clearWidgets();
        init();
    }

    /** 附加条件（都是**已有内建值**能表达的，不吹牛）：点一下只换条件，事件保持不动。 */
    private void openCondMenu(int mx, int my) {
        condMenu.open(width, height, mx, my,
                java.util.List.of("无条件", "潜行时", "不潜行时", "两只手都是空的"),
                java.util.List.of(
                        () -> setCond("", "无条件"),
                        () -> setCond(" && sneak == 1", "潜行时"),
                        () -> setCond(" && sneak == 0", "不潜行时"),
                        () -> setCond(" && hand == \"\" && offhand == \"\"", "两只手都是空的")));
    }

    private void setCond(String extra, String label) {
        condExtra = extra;
        clearWidgets();
        init();
    }

    /** 【生成到脚本】：事件守卫 + 附加条件 → 一段（幂等；已有同一句就一字不动）。 */
    private void genRule() {
        if (evGuard.isEmpty()) {
            status = "先点【事件…】挑一个";
            return;
        }
        genTrigger(evOn, evGuard + condExtra, evLabel);
    }

    /** 生成一段触发并落进脚本（唯一出口：`applyScript` + 回执）。 */
    private void genTrigger(String on, String guard, String label) {
        if (editor == null) {
            return;
        }
        ScriptEdit.Result r = ScriptEdit.genBlockTrigger(editor.def.script(), on, guard, label);
        String err = editor.applyScript(r.text());
        status = err.isEmpty() ? r.note() : err;
        DrawBoardMenuUi.msg("[" + label() + "] " + status);
    }

    /** 两个菜单的点击先吃掉；再先看属性滚动条（能看见的就能拖 —— 不做假滚动条）。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (lootMenu.click(event.x(), event.y())) {
            return true;
        }
        if (evMenu.click(event.x(), event.y()) || condMenu.click(event.x(), event.y())) {
            return true;
        }
        if (inPropBar(event.x(), event.y())) {
            propDrag = true;
            dragPropTo(event.y());
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (lootMenu.drag(event.x(), event.y())) {
            return true;
        }
        if (evMenu.drag(event.x(), event.y()) || condMenu.drag(event.x(), event.y())) {
            return true;                              // 菜单滚动条在拖
        }
        if (propDrag) {
            dragPropTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (lootMenu.release(event.x(), event.y())) {
            return true;
        }
        if (evMenu.release(event.x(), event.y()) || condMenu.release(event.x(), event.y())) {
            return true;
        }
        if (propDrag) {
            propDrag = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // ===== 菜单检索转发（AssetMenu）=====

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (lootMenu.type(event.codepointAsString())) {
            return true;
        }
        if (evMenu.type(event.codepointAsString()) || condMenu.type(event.codepointAsString())) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (lootMenu.isOpen() && event.key() == 259 && lootMenu.backspace()) {
            return true;
        }
        for (AssetMenu m : new AssetMenu[]{evMenu, condMenu}) {
            if (m.isOpen()) {
                if (event.key() == 259 && m.backspace()) {
                    return true;                      // 菜单检索行在退格
                }
                if (event.key() == 257 || event.key() == 335) {
                    return true;                      // 菜单开着：回车不落到底下的控件
                }
            }
        }
        return super.keyPressed(event);
    }

    // ==================== 动作 ====================

    /** 描述框当前文本（框不在 = 进页时的原值）—— 预览与提交都读它。 */
    private String loreText() {
        return loreBox == null ? liveLore : loreBox.getValue();
    }

    /**
     * 【完成】：**只发改过的那栏**，先回宿主屏再发包（回执按「此刻屏上是什么」分派）。
     * 名字空 = 拒收（名字就是游戏里显示的名，不能没有）。
     */
    // ---------- 掉落表：原版战利品表挂在**对象声明**的 `loot "表名"` 那一格上 ----------

    /** 这条对象挂得上原版表吗：只有**方块 / 实体**挂得上（物品没有掉落表这回事）。 */
    private boolean canHoldLoot() {
        String k = declKindOf();
        return "block".equals(k) || "entity".equals(k);
    }

    /** 脚本声明里那格 {@code loot "…"} 的当前值（没有 = 空）。 */
    private String currentLootTable() {
        if (editor == null) return "";
        try {
            for (Ast.AssetDecl d : Parser.parse(editor.def.script()).assetDecls()) {
                if (!d.name().equals(id)) continue;
                for (Ast.Field f : d.fields()) {
                    if ("loot".equals(f.key()) && f.value() instanceof Ast.Str sv) return sv.v();
                }
            }
        } catch (RuntimeException ignored) {
            // 脚本有语法错 = 看不到当前值，不影响挂表
        }
        return "";
    }

    /** 下拉能选哪些表：只列 **type 匹配**的（方块对象 ↔ minecraft:block，实体 ↔ minecraft:entity）。 */
    private java.util.List<String> lootChoices() {
        String want = "block".equals(declKindOf()) ? "minecraft:block" : "minecraft:entity";
        java.util.List<String> out = new java.util.ArrayList<>();
        if (editor == null) return out;
        var all = editor.def.lootTables();
        for (String nm : all.keySet()) {
            var t = all.get(nm);
            String ty = t != null && t.isJsonObject() && t.getAsJsonObject().has("type")
                    ? t.getAsJsonObject().get("type").getAsString() : "";
            if (want.equals(ty)) out.add(nm);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private String lootLabel() {
        return liveLootTable.isEmpty() ? "（不挂表）▾" : liveLootTable + " ▾";
    }

    private void openLootMenu(int ax, int ay) {
        java.util.List<String> ls = new java.util.ArrayList<>();
        java.util.List<Runnable> as = new java.util.ArrayList<>();
        ls.add(liveLootTable.isEmpty() ? "（不挂表）✔" : "（不挂表）");
        as.add(() -> setLoot(""));
        for (String nm : lootChoices()) {
            ls.add(nm.equals(liveLootTable) ? nm + "　✔" : nm);
            as.add(() -> setLoot(nm));
        }
        if (lootChoices().isEmpty()) {
            ls.add("（这个项目还没有 type 匹配的表 —— 去左栏【原版数据】建一张）");
            as.add(() -> { });
        }
        lootMenu.open(width, height, ax, ay, ls, as, null);
    }

    /**
     * 挂 / 摘表 = 写脚本声明里那一格（{@link ScriptEdit#setDeclField}，空值 = 删掉那格），再走编程器唯一出口。
     * 老条目（脚本里还没有它的声明）先提示按【完成】—— 不在这里造半成品状态。
     */
    private void setLoot(String tableName) {
        String code = editor.def.script();
        String kind = declKindOf();
        if (ScriptEdit.declOf(code, kind, id) == null) {
            status = "先按【完成】把这条存成脚本声明，再来挂表";
            return;
        }
        ScriptEdit.Result r = ScriptEdit.setDeclField(code, kind, id, "loot",
                tableName == null || tableName.isEmpty() ? "" : ScriptEdit.strCode(tableName));
        editor.applyScript(r.text());
        liveLootTable = tableName == null ? "" : tableName;
        if (lootBtn != null) lootBtn.setMessage(Component.literal(lootLabel()));
        status = r.note();
    }

    /** 跳到这张表的画布（本屏当返回目标）。 */
    private void openLootCanvas() {
        if (liveLootTable.isEmpty()) {
            status = "这条还没挂表 —— 先在上面的下拉里选一张";
            return;
        }
        Minecraft.getInstance().setScreen(new LootCanvasScreen(this, editor, liveLootTable));
    }

    private void commit() {
        String nn = nameBox == null ? liveName : nameBox.getValue().trim();
        if (nn.isEmpty()) {
            status = "名字不能为空";
            return;
        }
        List<String> nl = ItemText.splitLore(loreText());
        if (editor != null) {
            commitDecl(nn, nl);                            // 对象页入口：改的是**脚本里的对象声明**
            return;
        }
        boolean rename = !nn.equals(initName.trim());
        boolean lore = !nl.equals(initLore);
        back();                                            // ① 先回宿主屏（它才收得到回执）
        if (rename) {
            ClientPacketDistributor.sendToServer(new AssetPackets.RenameAssetPayload(pack, id, nn));
        }
        if (lore) {
            ClientPacketDistributor.sendToServer(new AssetPackets.SetItemLorePayload(pack, id, nl));
        }
        if (!rename && !lore) {
            DrawBoardMenuUi.msg("[物品] 没改动，没发包");       // ② 两栏都没动：一个包都不发
        }
    }

    /**
     * 对象页入口的保存：改的是**脚本里的对象声明** —— 资产名 / 显示名 / 描述三栏各自对账，
     * 只动改过的那几栏，最后**一次写回**（脚本页可见可改，不做隐形魔法）。
     */
    private void commitDecl(String nn, List<String> nl) {
        String code = editor.def.script();
        String kind = declKindOf();
        String newId = idBox == null ? liveId : idBox.getValue().trim();
        if (newId.isEmpty()) {
            status = "资产名不能为空";
            return;
        }
        String curId = id;
        boolean any = false;
        // ②c：**老条目**（脚本里没有它的声明、资产名=显示名）第一次保存 =
        // 补一条声明 —— 建 `kind 资产名 { name "原显示名" base "原基底" }`，之后各栏照常对账。
        // 这样它就有了资产名（对象页两栏齐全、候选里只剩资产名），老的档副本由 GameStore 的同显示名合并收编
        //（②b：老 bp/px 会在那次合并里带过来，components 不丢）。
        if (isLegacyEntry()) {
            if (newId.equals(curId)) {
                status = "老条目要先把它改成一条脚本声明：给它一个资产名（框里已给建议名）";
                return;
            }
            ScriptEdit.Result ra = ScriptEdit.addDecl(code, kind, newId);
            if (ra.text().equals(code)) {
                status = ra.note();
                return;
            }
            code = ra.text();
            curId = newId;
            any = true;
            if (!base.isEmpty()) {
                ScriptEdit.Result rb = ScriptEdit.setDeclField(code, kind, curId, "base", ScriptEdit.strCode(base));
                if (!rb.text().equals(code)) {
                    code = rb.text();
                }
            }
            // ⚠ 显示名要**显式写一次**：新建的声明只有段头，没写 name 就会退回资产名 ——
            //   不但显示变了，②b 的「同显示名合并」也就认不出它与老副本是一对（bp 带不过来）。
            ScriptEdit.Result rn = ScriptEdit.setDeclField(code, kind, curId, "name", ScriptEdit.strCode(nn));
            if (!rn.text().equals(code)) {
                code = rn.text();
            }
        }
        if (!newId.equals(curId)) {
            ScriptEdit.Result rr = ScriptEdit.renameDecl(code, kind, curId, newId);
            if (rr.text().equals(code)) {
                status = rr.note();                        // 拒收（重名 / 保留字…）：原样停在这，不写
                return;
            }
            code = rr.text();
            curId = newId;
            any = true;
        }
        if (!nn.equals(initName.trim())) {
            ScriptEdit.Result r2 = ScriptEdit.setDeclField(code, kind, curId, "name", ScriptEdit.strCode(nn));
            if (r2.text().equals(code)) {
                status = r2.note();
                return;
            }
            code = r2.text();
            any = true;
        }
        if (!nl.equals(initLore)) {
            // 文本对象：正文写的是声明里的 body（不是 lore —— toAssetDef 两条都收进 lore，但写对的那一条）
            ScriptEdit.Result r3 = ScriptEdit.setDeclField(code, kind, curId,
                    kind.equals("text") ? "body" : "lore", loreCode(nl));
            if (r3.text().equals(code)) {
                status = r3.note();
                return;
            }
            code = r3.text();
            any = true;
        }
        if (mark != null) {                            // 文本对象：标记那一栏独立对账（空 = 删掉 mark 这一段）
            String mw = liveMark.trim();
            if (!mw.equals(mark)) {
                ScriptEdit.Result r4 = ScriptEdit.setDeclField(code, "text", curId, "mark",
                        mw.isEmpty() ? "" : ScriptEdit.strCode(mw));
                if (r4.text().equals(code)) {
                    status = r4.note();
                    return;
                }
                code = r4.text();
                any = true;
            }
        }
        if (propRows != null) {                        // 属性段（2026-09-25 片2）：整段对账，只动改过的
            String nw = ComponentModel.toText(propRows);
            if (!nw.strip().equals(initRows.strip())) {
                ScriptEdit.Result r5 = ScriptEdit.setDeclBlock(code, kind, curId, "components", nw);
                if (r5.text().equals(code)) {
                    status = r5.note();                // 拒收（组件名/值不合法、或写坏了脚本）：原样停在这，不写
                    return;
                }
                code = r5.text();
                any = true;
            }
        }
        if (!any) {
            status = "没改动，没写脚本";
            return;
        }
        String err = editor.applyScript(code);
        status = err.isEmpty() ? "已写回脚本（脚本页可见）" : err;
        DrawBoardMenuUi.msg("[" + label() + "] " + status);
    }

    /** 描述行 → 脚本里的写法（一行写字符串，多行写 `["a", "b"]`；空表 = 删掉这一栏）。 */
    private static String loreCode(List<String> ls) {
        if (ls.isEmpty()) {
            return "";
        }
        if (ls.size() == 1) {
            return ScriptEdit.strCode(ls.get(0));
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ls.size(); i++) {
            b.append(i == 0 ? "" : ", ").append(ScriptEdit.strCode(ls.get(i)));
        }
        return b.append("]").toString();
    }

    /**
     * 挂在哪个编辑器上（{@link EditorToolScreen}）—— **这一条就是「保存回执别把我弹回总览」的钥匙**：
     * 回执路由认这个接口，工具屏在名单里就「就地刷新 + 原地重建」，不在名单里就会新开编辑器
     * （症状：新建对象点完保存 → 直接掉回总览）。对象页入口传的是编辑器；组件库 / 组件页传 null
     * （那两个入口改的是档里的条目、不发定义回执，按「都没开着」处理正好）。
     */
    @Override
    public GameEditorScreen editor() {
        return editor;
    }

    /**
     * 回上一屏。**组件库入口多一步**：顺手请服务端把那个库的内容重发一遍 —— 新建的条目是服务端写档的，
     * 而编辑页开着的时候那份回执被丢掉（回执只认组件库屏），不补一下、回去看列表会「少一条」。
     * （对象页入口不走这条：那边改的是脚本声明，回执路由认编辑页背后的编辑器，会就地刷新。）
     */
    private void back() {
        if (editor == null && pack != null && !pack.startsWith("@")) {
            ClientPacketDistributor.sendToServer(new AssetPackets.OpenPackPayload(pack));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    /** 预览图标 = 基底原版物品 / 方块（原版方块 id 与它的 BlockItem id 相同 ⇒ 同一个查询就够；认不出 = 空）。 */
    private ItemStack icon() {
        Identifier oid = Identifier.tryParse(base);
        Item it = oid == null ? null : BuiltInRegistries.ITEM.getValue(oid);
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    /**
     * 这条对象是哪一种（中文样）：**以脚本声明为准**（真源），没声明才按基底认。
     */
    private String label() {
        return switch (declKindOf()) {
            case "block" -> "方块";
            case "entity" -> "实体";
            case "text" -> "文本";
            default -> "物品";
        };
    }

    /**
     * 这条对象在脚本里是哪种声明（{@code block} / {@code item} / {@code entity} / {@code text}）。
     *
     */
    private String declKindOf() {
        if (mark != null) {
            return "text";                       // 期7 片4：文本对象（没有基底，按标记那一栏认）
        }
        if (editor != null) {
            try {
                for (Ast.AssetDecl d : Parser.parse(editor.def.script()).assetDecls()) {
                    if (d.name().equals(id)) {
                        return d.kind();
                    }
                }
            } catch (RuntimeException ignored) {
                // 脚本是半成品：退回按基底认（下面那条）
            }
        }
        return declKindFromBase();
    }

    /**
     * 没有声明可依时按基底认（组件库导入的老条目 / 预览）—— 判定一律走 `containsKey`。
     *
     * <p>⚠ 别用 `getValue(...) != null`：方块 / 物品两张表都是 `DefaultedRegistry`，查不到也给默认条目。
     */
    private String declKindFromBase() {
        Identifier bid = Identifier.tryParse(base);
        if (bid == null) {
            return "item";
        }
        if (BuiltInRegistries.BLOCK.containsKey(bid)) {
            return "block";
        }
        return BuiltInRegistries.ENTITY_TYPE.containsKey(bid) ? "entity" : "item";
    }

    /**
     * 这条是不是**老条目**（②c，）：在项目档 assets 里、但脚本里没有对应的对象声明
     * （老形状 —— 资产名=显示名，改不动、候选里只能以显示名出现）。文本对象（mark != null）不算。
     */
    private boolean isLegacyEntry() {
        if (editor == null || mark != null) {
            return false;
        }
        try {
            for (Ast.AssetDecl d : Parser.parse(editor.def.script()).assetDecls()) {
                if (d.name().equals(id)) {
                    return false;                          // 有声明 = 正常对象
                }
            }
        } catch (RuntimeException ignored) {
            // 脚本半成品：当老条目处理（给了建议名，保存时真源解析会再拦一次）
        }
        return true;
    }

    /** 建议资产名 = 基底路径 + 序号（wooden_shovel2 / stone1；序号从 2 起，避开裸基底名）。已占用就往后推。 */
    private String suggestId() {
        String stem = base;
        int slash = stem.indexOf(':');
        if (slash >= 0) {
            stem = stem.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : stem.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');   // 标识符安全
        }
        if (sb.isEmpty()) {
            sb.append("obj");
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        try {
            for (Ast.AssetDecl d : Parser.parse(editor.def.script()).assetDecls()) {
                used.add(d.name());
            }
        } catch (RuntimeException ignored) {
        }
        if (editor.def.assets() != null) {
            for (GameDefinition.AssetDef a : editor.def.assets()) {
                used.add(a.ref());
            }
        }
        int n = 1;
        String cand;
        do {
            n++;
            cand = sb.toString() + n;
        } while (used.contains(cand) && n < 1000);
        return cand;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    /** 「真行 / 折行」标记：原版多行框按**框宽自动折行**，屏上分不出哪是回车换的真行、哪是被折开的续行 —— 所以在框**后面（右边）**留一条 {@link #LORE_GUT}px 竖槽。
     *  行位置与原版逐行一致：同一个 {@code font.getSplitter().splitLines(值, 框内宽)}（框内宽 = 框宽 − totalInnerPadding(4×2)）、同样的行高 9、同样的滚动位移；出框的行不画（= 原版的裁剪）。 */
    private void drawLoreMarks(GuiGraphicsExtractor g) {
        if (loreBox == null || !loreBox.visible) {
            return;
        }
        String v = loreText();
        int innerW = loreBox.getWidth() - 8;              // 原版 totalInnerPadding = innerPadding(4) × 2
        if (innerW <= 0) {
            return;
        }
        java.util.List<int[]> lines = new java.util.ArrayList<>();
        font.getSplitter().splitLines(v, innerW, Style.EMPTY, false,
                (style, begin, end) -> lines.add(new int[] {begin, end}));
        if (v.endsWith("\n")) {                          // 末尾回车 = 原版再补一条空行
            lines.add(new int[] {v.length(), v.length()});
        }
        int top = loreBox.getY() + 4 - (int) loreBox.scrollAmount();
        int gx = loreBox.getX() + loreBox.getWidth() + LORE_GUT;   // 竖槽在框**后**(右)边
        for (int i = 0; i < lines.size(); i++) {
            int y = top + i * 9;
            if (y + 9 <= loreBox.getY() || y >= loreBox.getY() + loreBox.getHeight()) {
                continue;                                // 出框不画
            }
            int b = lines.get(i)[0];
            if (b == 0 || v.charAt(b - 1) == '\n') {     // 真行（回车换的那一条）
                g.fill(gx, y + 3, gx + 2, y + 5, 0xFFC8D0E0);
            } else {                                     // 折行的续行
                g.fill(gx, y, gx + 2, y + 9, 0xFF6A7A4A);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        drawLoreMarks(g);                                 // 描述框后面那条「真行 / 折行」标记竖槽
        int x = EditorRail.LEFT;
        // 顶部一行：只留「编辑方块 · 资产名」。
        g.text(font, Component.literal("编辑" + label() + " · " + (liveId.isBlank() ? id : liveId)), x, 8, 0xFFFFFFFF);

        // 自绘预览（挪到内容区**右边一列**）：图标（原版 16×16）+ 名字 + 描述行；& 上色当场生效
        // 分辨率自适应：窄窗整块不摆、名字按剩余宽度截断、描述行画到屏底就停（别冲出屏幕）
        if (previewOn) {
            ItemStack ico = icon();
            if (!ico.isEmpty()) {
                g.item(ico, previewX, previewY);
            }
            String nm = ColorText.mask(liveName.isEmpty() ? "（没名字）" : liveName);
            g.text(font, Component.literal(font.plainSubstrByWidth(nm, Math.max(20, width - previewX - 24))),
                    previewX + 22, previewY + 4, liveName.isEmpty() ? 0xFF808080 : 0xFFFFFFFF);
            List<String> ls = ItemText.splitLore(loreText());
            for (int i = 0; i < ls.size(); i++) {
                int ly = previewY + 22 + i * 10;
                if (ly > height - 14) {
                    break;                                   // 到底就不画了
                }
                g.text(font, Component.literal(ColorText.mask(ls.get(i))), previewX, ly, 0xFFB090D0);
            }
        }

        // 【属性】段分割线：段名写在线的左端，线身从内容区左边一直拉到屏幕右边
        // 线下面 = 属性段（一行一个组件）；这一行最下面还会写属性段自己的翻页小标
        g.text(font, Component.literal("属性"), x, sectY - 4, 0xFFC8D0E0);
        g.fill(x + font.width("属性") + 6, sectY, width, sectY + 1, 0xFF5A6070);
        drawPropBar(g);                                   // 属性段右侧的滚动条（行数超一屏时才画）

        if (!status.isEmpty()) {
            // 底部状态行（属性段行区下边界给它让路 ⇒ 能多摆几行）；窄窗按**像素**截断，别冲出屏幕
            String st = font.plainSubstrByWidth(status, contentW);
            g.text(font, Component.literal(st.length() < status.length() ? st + "…" : st),
                    x, height - 22, 0xFFE0E080);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        evMenu.draw(g, font, mouseX, mouseY);              // 两个菜单画在最上层
        condMenu.draw(g, font, mouseX, mouseY);
        lootMenu.draw(g, font, mouseX, mouseY);            // 掉落表下拉（2026-09-30）
    }
}
