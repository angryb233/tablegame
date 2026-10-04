package com.tablegame.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import com.tablegame.core.ComponentGroup;
import com.tablegame.core.ComponentModel;

/**
 * 「挑一个 id」拾取屏：候选从注册表现读，能搜也能选。
 *
 * <p>两个入口：{@link #components}（挑数据组件，候选 = DATA_COMPONENT_TYPE）、{@link #of}
 * （引用格子右边那枚 ▾，候选 = 对应注册表，短名见 {@link #KINDS}）。
 * <p>静态注册表直接读 `BuiltInRegistries`；动态注册表（附魔 / 伤害类型 / 旗帜图案）从当前存档的
 * `registryAccess()` 取 —— 不在世界里就取不到，给空表并写「没有候选」（不崩、不给假的）；名字能给中文就给。
 */
public final class IdPickScreen extends Screen {

    /** 字段表 `pick` 里写的短名 → 哪张注册表（取不到就空表）。唯一真源是 {@link ComponentGroup#PICK_KINDS}。 */
    public static final List<String> KINDS = ComponentGroup.PICK_KINDS;

    private final Screen back;
    private final String[] ids;
    private final Function<String, String> nameOf;
    private final String searchHint;
    private final String emptyNote;
    private final Consumer<String> onPick;
    private final IdPickUi ui = new IdPickUi();
    private final String title;

    private IdPickScreen(Screen back, String title, String searchHint, String emptyNote,
                         String[] ids, Function<String, String> nameOf, Consumer<String> onPick) {
        super(Component.literal(title));
        this.back = back;
        this.title = title;
        this.searchHint = searchHint;
        this.emptyNote = emptyNote;
        this.ids = ids;
        this.nameOf = nameOf;
        this.onPick = onPick;
    }

    /** 挑一个数据组件（属性段【＋组件】）：候选 = 组件注册表现读、中文名走 ComponentModel。 */
    public static IdPickScreen components(Screen back, Consumer<String> onPick) {
        return new IdPickScreen(back, "挑一个组件", "搜中文名或 id（如 堆叠 / max_stack_size）",
                "（没有匹配的组件）", componentIds(), ComponentModel::cnOf, onPick);
    }

    /**
     * 组件拾取的候选：全部原版 / 模组数据组件（保留键 name / base / lore / body / mark 不列，已是声明结构键）。
     * 非 minecraft 命名空间写全名（免得与模组同名撞车），原版写短名；有规格的排前面，其余按 id。
     * ⚠ 候选要读真注册表 ⇒ 只能在客户端类里（ComponentModel 进自检 javac 清单，那边的 BuiltInRegistries 是桩、没有 DATA_COMPONENT_TYPE）。
     */
    private static String[] componentIds() {
        List<String> idl = new ArrayList<>();
        for (Identifier key : BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()) {
            if (!ComponentModel.addable(key.getPath())) {
                continue;
            }
            // 瞬态组件（只有网络同步、没有持久化 codec 的那些：`creative_slot_lock` / `additional_trade_cost`…）
            // 进不了组件补丁 —— 选了也只能得一句「… is not a persistent component」，候选里就不摆。
            var ct = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(key);
            if (ct.isPresent() && ct.get().isTransient()) {
                continue;
            }
            idl.add("minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString());
        }
        idl.sort((a, b) -> {
            int ka = ComponentModel.known(a) ? 0 : 1;
            int kb = ComponentModel.known(b) ? 0 : 1;
            return ka != kb ? ka - kb : a.compareTo(b);
        });
        return idl.toArray(new String[0]);
    }

    /**
     * 挑一个注册表 id（条目里引用格子右边那枚 ▾）。
     *
     * @param kind  {@link #KINDS} 里的短名（字段表 `pick` 写的就是它）
     * @param label 这一格叫什么（拼标题用：「挑一个」+ label）
     */
    public static IdPickScreen of(Screen back, String kind, String label, Consumer<String> onPick) {
        return new IdPickScreen(back, "挑一个" + label + "（点一行即填入 · Esc 放弃）",
                "搜 id（如 " + example(kind) + "）",
                "（没有候选 —— 这张注册表本版取不到 / 存档里没有）",
                idsOf(kind), s -> s, onPick);
    }

    private static String example(String kind) {
        return switch (kind) {
            case "attribute" -> "attack_damage";
            case "item" -> "diamond_sword";
            case "block" -> "stone";
            case "enchantment" -> "sharpness";
            case "mob_effect" -> "speed";
            case "damage_type" -> "fall";
            case "banner_pattern" -> "stripe_bottom";
            case "map_decoration_type" -> "target_point";
            case "sound_event" -> "block.stone.break";
            case "data_component_type" -> "max_stack_size";
            default -> "xxx";
        };
    }

    /** 短名 → 那张表的全部 id（原版写短名、模组写全名 —— 与组件拾取同一口径）。 */
    private static String[] idsOf(String kind) {
        Collection<Identifier> keys = keysOf(kind);
        List<String> out = new ArrayList<>();
        for (Identifier k : keys) {
            out.add("minecraft".equals(k.getNamespace()) ? k.getPath() : k.toString());
        }
        out.sort(String::compareTo);
        return out.toArray(new String[0]);
    }

    private static Collection<Identifier> keysOf(String kind) {
        Registry<?> r = switch (kind) {
            case "attribute" -> BuiltInRegistries.ATTRIBUTE;
            case "item" -> BuiltInRegistries.ITEM;
            case "block" -> BuiltInRegistries.BLOCK;
            case "mob_effect" -> BuiltInRegistries.MOB_EFFECT;
            case "map_decoration_type" -> BuiltInRegistries.MAP_DECORATION_TYPE;
            case "sound_event" -> BuiltInRegistries.SOUND_EVENT;
            case "data_component_type" -> BuiltInRegistries.DATA_COMPONENT_TYPE;
            // 动态注册表：附魔 / 伤害类型 / 旗帜图案 —— 从当前存档的 registryAccess 取（不在世界里 ⇒ 空表）
            case "enchantment" -> dyn(Registries.ENCHANTMENT);
            case "damage_type" -> dyn(Registries.DAMAGE_TYPE);
            case "banner_pattern" -> dyn(Registries.BANNER_PATTERN);
            default -> null;
        };
        return r == null ? List.of() : r.keySet();
    }

    /** 取一张动态注册表（客户端已同步的那份）；不在世界里 ⇒ null（界面会写一句「没有候选」，不崩也不给假的）。 */
    private static Registry<?> dyn(ResourceKey<? extends Registry<?>> key) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return level.registryAccess().lookup(key).map(r -> (Registry<?>) r).orElse(null);
    }

    @Override
    protected void init() {
        clearWidgets();
        int w = Math.min(380, width - 60);
        int x = (width - w) / 2;
        ui.hint(searchHint);
        ui.emptyNote(emptyNote);
        ui.setRows(ids, nameOf);
        ui.build(x, 48, w, height - 48 - 40, this::addRenderableWidget, null);
        if (ui.box() != null) {
            setFocused(ui.box());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (ui.click(event.x(), event.y(), id -> {
            onPick.accept(id);
            Minecraft.getInstance().setScreen(back);              // 选完回上一屏
        })) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (ui.scroll(dy)) {
            return true;                                          // 行是自绘的：滚完不用重建控件
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                                 // Esc = 放弃
            Minecraft.getInstance().setScreen(back);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xE0101218);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, Component.literal(title), width / 2, 20, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        ui.draw(g, font, mouseX, mouseY);
    }
}
