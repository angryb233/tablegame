package com.tablegame;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tablegame.drawboard.BoardManager;
import com.tablegame.drawboard.BoardPackets;
import com.tablegame.drawboard.DrawBoardCommand;
import com.tablegame.table.DealerBlock;
import com.tablegame.net.DealerPackets;
import com.tablegame.host.GameCommand;
import com.tablegame.host.GameManager;
import com.tablegame.net.GamePackets;
import com.tablegame.net.AreaCapturePackets;
import com.tablegame.area.AreaCaptureServer;
import com.tablegame.net.AreaGhostPackets;
import com.tablegame.net.AreaPlacePackets;
import com.tablegame.net.AreaSyncPackets;
import com.tablegame.net.AssetPackets;
import com.tablegame.host.HostManager;
import com.tablegame.net.HostPackets;
import com.tablegame.piece.BlueprintItem;
import com.tablegame.piece.BlueprintPackets;
import com.tablegame.piece.GamePieceEntity;
import com.tablegame.piece.GamePieceItem;
import com.tablegame.piece.ModelMakerBlock;
import com.tablegame.piece.ModelMakerBlockEntity;
import com.tablegame.piece.ModelMakerMenu;
import com.tablegame.piece.ModelMakerPackets;
import com.tablegame.piece.PieceData;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.tablegame.area.AreaCapturer;
import com.tablegame.area.AreaPlacer;
import com.tablegame.block.ChairBlock;
import com.tablegame.block.GameTableBlock;
import com.tablegame.block.SeatEntity;
import com.tablegame.card.CardEntity;
import com.tablegame.core.GamePack;

/**
 * 模组主类，common 侧（客户端与专用服务器都加载）。
 *
 * <p>NeoForge 扫描 {@code @Mod} 后实例化本类，构造器把所有 DeferredRegister 挂到 mod 事件
 * 总线上完成注册。客户端专属代码放 {@code TableGameClient}（{@code dist = Dist.CLIENT} 限定）。
 */
@Mod(TableGame.MODID)
public class TableGame {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "tablegame";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // ===== 延迟注册（DeferredRegister）机制 =====
    // 先声明待注册对象，等模组加载时由 NeoForge 统一注册，避免类加载顺序问题。
    // BLOCKS / ITEMS / CREATIVE_MODE_TABS / ENTITIES 各对应一种注册表。
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // 实体注册表：registerEntityType 简写自动生成 EntityType（含 ResourceKey），免手写 Builder。
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(MODID);

    // 方块实体注册表：26.x 无 BlockEntityType.Builder，直接 new BlockEntityType<>(供应商, 方块...)。
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    // 容器 Menu 注册表：IMenuTypeExtension.create 接收 IContainerFactory（能读打开包里的 BlockPos）。
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);

    // 棋牌桌方块：registerBlock 注册进 BLOCKS，返回 DeferredBlock 占位符。
    //   mapColor(Wood) / strength(2.0, 3.0) / sound(Wood)   地图色 / 硬度抗爆 / 音效
    //   noOcclusion()  ⚠ 非满方块必须加，否则相邻面被错误剔除 →「透过桌子看到后面」的透视 bug
    public static final DeferredBlock<GameTableBlock> GAME_TABLE = BLOCKS.registerBlock("game_table",
            props -> new GameTableBlock(props
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f, 3.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    // 物品：registerSimpleBlockItem 给方块生成一个放它用的 BlockItem（物品栏才有得摆）。
    public static final DeferredItem<BlockItem> GAME_TABLE_ITEM = ITEMS.registerSimpleBlockItem("game_table", GAME_TABLE);

    // ==================== 游戏台（dealer）====================
    // 对局从它开：右键选游戏开一桌，别人右键进游戏界面当观众、点准备才入座。
    // 外观暂用原版制箭台（美术期换）；满方块 → 不用 noOcclusion，硬度跟木机器类走。
    public static final DeferredBlock<DealerBlock> DEALER = BLOCKS.registerBlock("dealer",
            props -> new DealerBlock(props
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f, 3.0f)
                    .sound(SoundType.WOOD)));

    public static final DeferredItem<BlockItem> DEALER_ITEM = ITEMS.registerSimpleBlockItem("dealer", DEALER);

    // 椅子方块：属性与棋牌桌一致（木质、硬度 2.0、抗爆 3.0），非满方块照例 noOcclusion。
    public static final DeferredBlock<ChairBlock> CHAIR = BLOCKS.registerBlock("chair",
            props -> new ChairBlock(props
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f, 3.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> CHAIR_ITEM = ITEMS.registerSimpleBlockItem("chair", CHAIR);

    // ==================== 自定义棋子 ====================

    // 蓝图物品：选区工具 + 体素数据载体（stacksTo(1)，一张蓝图存一个结构，不可堆叠）。
    // ⚠ 必须用 registerItem（内部 setId）；通用 register() 不设 id → 注册期 NPE「Item id not set」。
    public static final DeferredItem<BlueprintItem> BLUEPRINT = ITEMS.registerItem("blueprint",
            props -> new BlueprintItem(props.stacksTo(1)));

    // 区域工具：进「选取 / 摆放」模式时发给玩家一枚，手里拿着才在模式里（状态条 / 拦手势 /
    // Shift+Enter 生效）；不拿或丢掉 = 回原版交互。状态全在栈上两个组件里（CUSTOM_DATA 标记 +
    // CUSTOM_NAME 显示名）⇒ 不需要专门物品类。
    public static final DeferredItem<Item> AREA_TOOL = ITEMS.registerItem("area_tool",
            props -> new Item(props.stacksTo(1)));

    // 模型制作器方块：功能方块，非满 → noOcclusion；硬度低（石头机器类）
    public static final DeferredBlock<ModelMakerBlock> MODEL_MAKER = BLOCKS.registerBlock("model_maker",
            props -> new ModelMakerBlock(props
                    .mapColor(MapColor.STONE)
                    .strength(1.5f, 3.0f)
                    .sound(SoundType.STONE)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> MODEL_MAKER_ITEM = ITEMS.registerSimpleBlockItem("model_maker", MODEL_MAKER);

    // 方块实体类型：26.x 无 Builder，直接 new BlockEntityType<>(供应商, 绑定的方块...)。
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ModelMakerBlockEntity>> MODEL_MAKER_BE =
            BLOCK_ENTITY_TYPES.register("model_maker",
                    () -> new BlockEntityType<>(ModelMakerBlockEntity::new, MODEL_MAKER.get()));

    // 容器 Menu 类型：IContainerFactory 能读打开包的 BlockPos（服务端 openMenu 写入）。
    public static final DeferredHolder<MenuType<?>, MenuType<ModelMakerMenu>> MODEL_MAKER_MENU =
            MENU_TYPES.register("model_maker",
                    () -> IMenuTypeExtension.create(ModelMakerMenu::clientMenu));

    // 棋子物品：默认堆叠 16（规格 4），挂 PieceData 组件。
    public static final DeferredItem<GamePieceItem> GAME_PIECE = ITEMS.registerItem("game_piece",
            props -> new GamePieceItem(props.stacksTo(16)));

    // 座位实体（椅子坐下的骑乘点）：
    //   MISC + sized(0.001)  完全隐形、不挡路、不自然生成、不吃刷怪上限
    //   noSummon()           禁止 /summon 刷出
    //   可存档：座位随椅子常驻，退出重进还在
    public static final DeferredHolder<EntityType<?>, EntityType<SeatEntity>> SEAT = ENTITIES.registerEntityType(
            "seat", SeatEntity::new, MobCategory.MISC,
            builder -> builder.sized(0.001f, 0.001f).noSummon());

    // 棋子实体：放在世界里的隐形承载体，体素由渲染器画。
    //   sized(0.3125f)  碰撞箱 5px，isPickable 让准星点得到（Shift+右键取回）、无碰撞不挡路，一格可放多个
    //   fireImmune()    火焰/岩浆烧不掉
    //   noSummon()      禁止 /summon 刷出空数据棋子
    //   可存档：退出重进棋子还在（数据随实体 NBT 落盘）
    public static final DeferredHolder<EntityType<?>, EntityType<GamePieceEntity>> GAME_PIECE_ENTITY =
            ENTITIES.registerEntityType("game_piece_entity", GamePieceEntity::new, MobCategory.MISC,
                    builder -> builder.sized(0.3125f, 0.3125f).fireImmune().noSummon());

    // 卡牌实体：一张卡在世界里的隐形承载体，双面纸牌由渲染器画。
    //   sized(0.3125f) 同棋子：准星点得到、无碰撞不挡路；可存档（牌面/背面/尺寸随实体 NBT）。
    public static final DeferredHolder<EntityType<?>, EntityType<com.tablegame.card.CardEntity>> CARD_ENTITY =
            ENTITIES.registerEntityType("card_entity", com.tablegame.card.CardEntity::new, MobCategory.MISC,
                    builder -> builder.sized(0.3125f, 0.3125f).fireImmune().noSummon());

    // 模组专属创造标签页：放在「战斗」之后，图标 = 桌子物品。
    // ⚠ 本模组物品只出现在这个标签页里，不额外塞进原版标签页。
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TABLEGAME_TAB = CREATIVE_MODE_TABS.register("tablegame",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tablegame")) // The language key for the title of your CreativeModeTab
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> GAME_TABLE_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(GAME_TABLE_ITEM.get());
                        output.accept(CHAIR_ITEM.get());
                        output.accept(BLUEPRINT.get()); // 蓝图
                        output.accept(MODEL_MAKER_ITEM.get()); // 模型制作器
                        output.accept(GAME_PIECE.get());       // 棋子
                        output.accept(DEALER_ITEM.get());      // 游戏台：对局从它开
                        output.accept(AREA_TOOL.get());        // 区域工具：选取 / 摆放模式用
                    }).build());

    /**
     * 模组构造器：模组加载时第一个执行。把注册动作挂到事件总线上 —— modEventBus 管生命周期
     * （注册 / payload），NeoForge.EVENT_BUS 管运行期（起服、玩家、指令）。
     */
    public TableGame(IEventBus modEventBus, ModContainer modContainer) {
        // 游戏定义网络载荷
        modEventBus.addListener(GamePackets::register);

        // 画板网络载荷
        modEventBus.addListener(BoardPackets::register);

        // 蓝图网络载荷（Shift+Enter 确认捕获）
        modEventBus.addListener(BlueprintPackets::register);

        // 模型制作器网络载荷（选比例 / 制作）
        modEventBus.addListener(ModelMakerPackets::register);

        // 主持人网络载荷（对局状态快照 S2C）
        modEventBus.addListener(HostPackets::register);

        // 游戏台网络载荷（运行 / 进入界面 / 中断，C2S）
        modEventBus.addListener(DealerPackets::register);
        modEventBus.addListener(AssetPackets::register);   // 组件库
        modEventBus.addListener(AreaCapturePackets::register);  // 世界区域捕获
        modEventBus.addListener(AreaPlacePackets::register);      // 区域落地
        modEventBus.addListener(AreaSyncPackets::register);       // 区域 ↔ 场景双向同步
        modEventBus.addListener(AreaGhostPackets::register);      // 蓝图幽灵预览（S2C）

        // 把各 DeferredRegister 挂到模组总线，触发真正注册
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ENTITIES.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus); // 方块实体类型
        MENU_TYPES.register(modEventBus);          // 容器 Menu 类型
        PieceData.DATA_COMPONENTS.register(modEventBus); // 棋子/蓝图数据组件

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);
    }

    // ===== 游戏运行期事件（NeoForge.EVENT_BUS，本类已 register(this)） =====

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 服务器启动：初始化画板总管
        BoardManager.init(event.getServer());
        // 初始化游戏定义总管
        GameManager.init(event.getServer());
        // 初始化对局主持人
        HostManager.init(event.getServer());
        LOGGER.info("HELLO from server starting");
    }

    /**
     * 每服务器 tick（20 次/秒）：把时间喂给各局的 {@code GameInstance}。只有正在计时的阶段才干活。
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        HostManager hm = HostManager.get();
        if (hm != null) hm.tick();
        com.tablegame.area.AreaPlacer.tick();   // 区域落地：每 tick 推进一个 chunk 相
        com.tablegame.area.AreaCapturer.tick(); // 区域收集：同分相法
        // 第一个 tick 把项目里声明的配方同步进存档数据包（幂等：内容没变就什么都不写）。
        // ⚠ 放在第一个 tick 而非 ServerStartingEvent：那会儿 server 还在起，重载易踩启动顺序。
        if (!recipePackSynced) {
            recipePackSynced = true;
            com.tablegame.core.GamePack.sync(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
        }
    }

    /** 配方同步只在起服的第一个 tick 跑一次（见 {@link #onServerTick}）。 */
    private boolean recipePackSynced = false;

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 服务器停止：脏画板落盘后销毁实例
        BoardManager.shutdown();
        GameManager.shutdown();
        HostManager.shutdown();
    }

    /**
     * 玩家右键空气（举着物品点空气）：只发 {@code on use}，不吃这一下 —— 原版照常（纸没手感、
     * 食物照吃）；引擎只是把「他用了手里的东西」报给脚本。原版两只手各发一次 ⇒ 只认主手。
     */
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        HostManager hm = HostManager.get();
        if (hm != null) hm.itemUsed(sp);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;                 // 只认服务端那一次
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;  // 副手那次 = 原版兜底重发
        if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof DealerBlock) {
            return;                    // 游戏台自己的界面（开对局菜单的入口）：不抢，脚本也不吃这一下
        }
        HostManager hm = HostManager.get();
        if (hm == null) return;
        // 建造优先于脚本手势：手持会放置的方块（`BlockItem`）⇒ 这一下是建造，一律不进 `on world`
        // （不管那格有没有保护）。取消还有关键好处：RightClickBlock 早于 BlockItem.useOn，物品还没扣 ——
        // 老实现挂在放置之后的 EntityPlaceEvent 上，取消时物品已扣，生存玩家会白丢一个方块。
        if (sp.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem) {
            if (hm.protectedLevelFor(sp, event.getPos()) > 0 && !hm.mayPlace(sp, event.getPos())) {
                event.setCanceled(true);
                // ⚠ 取消只挡服务端：客户端会跑同一段预测且判定进不来（LocalPlayer、保护表在服务端）
                // ⇒ 客户端预测「放成功 + 手上少一个」而服务端没放没扣 → 两行推回权威状态。
                sp.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
                        sp.level(), event.getPos()));         // 真方块状态 ⇒ 客户端撤销预测放下的方块
                sp.inventoryMenu.sendAllDataToRemote();       // 手里那份 ⇒ 客户端预测扣掉的还回来
                sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§7[对局] 这块被保护了"));
            }
            return;                    // 建造不当棋步，交给原版照常放置
        }
        if (hm.worldClick(sp, event.getPos(), sp.isShiftKeyDown())) {
            event.setCanceled(true);
        }
    }

    /**
     * 脚本保护的区域里玩家挖不动方块（protect / unprotect 的消费方）。
     *
     * <p>直接取消破坏事件：方块不掉落、保持原样。按保护级别拦：一级里创造与带 canDestroy 组件的
     * 工具照挖，二级里连创造也拦。⚠ 26.x 事件类叫 {@code BreakBlockEvent}。
     */
    @SubscribeEvent
    public void onBreakBlock(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp)) return;  // 只认服务端真人
        // 能不能挖只有 mayBreak 一个判据：没保护 → 能；一级 → 创造与带 canDestroy 组件的工具能挖；
        // 二级 → 连创造也不能（与放置同族）。
        HostManager hm = HostManager.get();
        if (hm == null) return;
        if (hm.mayBreak(sp, event.getPos())) {
            // on break 的普通那次（无掉落清单，带清单的那次在 BlockDropsEvent）
            hm.blockBroken(sp, event.getPos(), event.getState(), null, null);
            return;
        }
        TableGame.LOGGER.info("[保护] 拒绝 {} 破坏 ({},{},{})：{} 级保护",
                sp.getName().getString(), event.getPos().getX(), event.getPos().getY(),
                event.getPos().getZ(), hm.protectedLevelFor(sp, event.getPos()));
        event.setCanceled(true);
        event.setNotifyClient(false);            // 不把取消动画发给客户端，免得方块闪一下
        // 26.x：sendOverlayMessage = actionbar 提示（旧的 displayClientMessage 没了）
        sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§7[对局] 这块被保护了"));
    }

    /**
     * 脚本保护的区域里玩家放不下方块（{@code protect} 的另一半）。
     *
     * <p>一个监听器管两个事件：床 / 门这种一次占两格的走 {@code EntityMultiPlaceEvent}，它
     * {@code extends EntityPlaceEvent}，NeoForge 总线挂了父类监听表 ⇒ 注册一次两边都收得到。
     *
     * <p>创造豁免按保护级别：一级里创造照放，二级里连创造也挡。
     */
    @SubscribeEvent
    public void onPlaceBlock(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        HostManager hm = HostManager.get();
        if (hm == null) return;
        if (hm.mayPlace(sp, event.getPos())) {
            hm.blockPlaced(sp, event.getPos(), event.getPlacedBlock());   // on place
            return;                                    // 放行
        }
        TableGame.LOGGER.info("[保护] 拒绝 {} 放置 ({},{},{})：{} 级保护",
                sp.getName().getString(), event.getPos().getX(), event.getPos().getY(),
                event.getPos().getZ(), hm.protectedLevelFor(sp, event.getPos()));
        event.setCanceled(true);
        // 兜底（不走右键的放置：别的模组 / 发射器 / 命令）：取消时物品可能已扣，还他一个。
        // 主路（右键）已在 onRightClickBlock 拦下、物品没扣 ⇒ 不会重复还。
        if (!sp.getAbilities().instabuild) {
            var held = sp.getMainHandItem();
            if (!held.isEmpty() && held.getItem() == event.getPlacedBlock().getBlock().asItem()) {
                sp.getInventory().add(held.copyWithCount(1));
            }
        }
        sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§7[对局] 这块被保护了"));
    }

    /**
     * 局内席位玩家右键实体交给脚本一次 {@code on entity}，并吃掉这一下。不要求潜行 —— 右键实体
     * 在原版多是喂 / 骑 / 开界面，对局里这些误触更烦。局外 / 观众 / 非棋盘维度在
     * {@code entityInteract} 里原样放行。
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;                 // 只认服务端那一次
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        HostManager hm = HostManager.get();
        if (hm != null && hm.entityInteract(sp, event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    /**
     * 玩家左键（攻击）实体交给脚本一次 {@code on entity}（内建值 {@code hit} = 1）。与
     * {@link #onEntityInteract}（右键）共用入口，靠 {@code hit} 分左右键。不取消这一下：原版伤害照旧结算。
     */
    @SubscribeEvent
    public void onAttackEntity(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide()) return;        // 只认服务端那一次
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        HostManager hm = HostManager.get();
        if (hm != null) hm.leftClickEntity(sp, event.getTarget());
    }

    /**
     * 脚本放出来的原版生物被打死交给脚本一次 {@code on entity}（内建值 {@code edead} = 1，与右键
     * 实体共用入口）。世界里野生生物被打死不会惊动脚本 —— 是不是脚本放的在 HostManager 按本局账判断。
     */
    @SubscribeEvent
    public void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;        // 只认服务端那一次
        HostManager hm = HostManager.get();
        if (hm == null) return;
        if (!(event.getEntity() instanceof net.minecraft.world.entity.LivingEntity le)) return;
        ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        hm.mobDead(le, killer);
        // 玩家死亡也在这报一声：开了 keep_items 的，这一下是抓背包快照的唯一时机
        // （再往下原版就 dropAll 倒空了）。
        if (le instanceof ServerPlayer dead) hm.playerDied(dead);
    }

    /**
     * 开了 {@code keep_items} 的玩家不掉落：清掉掉落物 + cancel。
     *
     * <p>⚠ 不动 {@code GameRules.KEEP_INVENTORY}（全服一条、分不了人）；背包靠 {@code LivingDeathEvent}
     * 的快照在重生时回填（{@code HostManager.playerDied/playerRespawned}）。
     */
    @SubscribeEvent
    public void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        HostManager hm = HostManager.get();
        if (hm == null) return;
        if (event.getEntity() instanceof ServerPlayer sp) {
            // 玩家：keep_items → 清掉落 + cancel
            if (!hm.keepDrops(sp)) return;
            event.getDrops().clear();
            event.setCanceled(true);
            return;
        }
        // 生物：挂了原版战利品表的用原版算，没挂的照旧
        hm.applyLootTable(event.getEntity(), event.getSource(), event.getDrops());
    }

    /** 经验也不掉：按快照在重生时回填（只截掉落物保不住经验）。 */
    @SubscribeEvent
    public void onExperienceDrop(net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        HostManager hm = HostManager.get();
        if (hm == null || !hm.keepDrops(sp)) return;
        event.setDroppedExperience(0);
        event.setCanceled(true);
    }

    /**
     * 玩家重生回来：把死亡那份背包 + 经验还给他。只在真死时动手（{@code isWasDeath}）——
     * 换维度也走这个事件，那次一个东西都不该动。
     */
    @SubscribeEvent
    public void onPlayerClone(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        HostManager hm = HostManager.get();
        if (hm == null) return;
        if (event.getEntity() instanceof ServerPlayer np) hm.playerRespawned(np);
    }

    /**
     * 破坏方块那一下补发带掉落清单的 {@code on break}（清单要等掉落结算完才有）。挂点在
     * {@code BlockDropsEvent}：掉落物、谁挖的、拿什么挖的都在这一处，一个钩子够用。
     */
    @SubscribeEvent
    public void onBlockDrops(net.neoforged.neoforge.event.level.BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof ServerPlayer sp)) return;   // 只认服务端真人（爆炸 / 别的生物不算）
        HostManager hm = HostManager.get();
        if (hm == null) return;
        // 掉落：挂了原版战利品表的方块用原版算（时运/附魔/谓词全在里面），没挂的不动。
        // ⚠ 必须在取清单之前跑，on break 的 drops 才是替换后那份。
        hm.applyLootTable(sp, event.getLevel(), event.getPos(), event.getState(), event.getTool(), event.getDrops());
        // on break 的带清单那次
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var e : event.getDrops()) {
            var k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getItem().getItem());
            if (k != null) ids.add(k.toString());
        }
        hm.blockBroken(sp, event.getPos(), event.getState(), event.getTool(), ids);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // /tablegame games ... 指令树
        GameCommand.register(event.getDispatcher());
        // /tablegame drawboard ... 指令树
        DrawBoardCommand.register(event.getDispatcher());
    }

    /**
     * 丢掉区域工具 = 直接消失。取消「丢出」这次事件：不让它进世界、也不阻止它从背包移除 ——
     * 与「工具离开背包 = 模式结束」那条判定对齐。只认服务端（原版丢物品本来就是服务端结算）。
     */
    @SubscribeEvent
    public void onItemToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        if (event.getEntity().getItem().getItem() != AREA_TOOL.get()) return;
        event.setCanceled(true);
    }

    /**
     * 玩家进世界（含重进存档）：他还在某一局里 → 把对局状态补给他。客户端状态是「开局 / 加入 /
     * 状态变化」时推下去的，重进的客户端手上什么都没有，不补的话点【进入游戏界面】只会开出空屏。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && HostManager.get() != null) {
            HostManager.get().playerJoined(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 玩家退出：清画板登记与未封存笔画；所在对局终止（中断 = 终止整局）
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (BoardManager.get() != null) BoardManager.get().playerLoggedOut(sp.getUUID());
            if (HostManager.get() != null) HostManager.get().playerLoggedOut(sp);
            AreaCaptureServer.drop(sp.getUUID());       // 世界区域捕获：退出即清账
        }
    }
}