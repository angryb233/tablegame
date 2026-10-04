package com.tablegame.drawboard;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.tablegame.TableGame;

    /**
     * 画板功能的自定义网络载荷（26.x 新 API）。项目化后 key = {@code 组/项目名} 复合键（组内唯一）。
     *
     * <p>S2C：{@link OpenBoardPayload} 打开/刷新（canEdit=false = 只读）、{@link BoardCellsPayload} 格子增量、
     * {@link ProjectsListPayload} 项目列表、{@link OpenMenuPayload} 弹主菜单、{@link GroupsListPayload} /
     * {@link GroupsUiPayload} 组信息。
     * C2S：{@link StrokeCellsPayload} / {@link UndoPayload} / {@link FillPayload} 编辑、{@link CreateProjectPayload}
     * 新建、{@link OpenProjectPayload} 打开、{@link RequestProjectsPayload} 请求列表、{@link ExportPayload} 导出等。
     */
public final class BoardPackets {
    private BoardPackets() {}

    // ===== S2C =====

    public record OpenBoardPayload(String key, String ownerName, int width, int height, byte[] cells, boolean canEdit)
            implements CustomPacketPayload {
        public static final Type<OpenBoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "open_board"));
        public static final StreamCodec<ByteBuf, OpenBoardPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenBoardPayload::key,
                ByteBufCodecs.STRING_UTF8, OpenBoardPayload::ownerName,
                ByteBufCodecs.VAR_INT, OpenBoardPayload::width,
                ByteBufCodecs.VAR_INT, OpenBoardPayload::height,
                ByteBufCodecs.BYTE_ARRAY, OpenBoardPayload::cells,
                ByteBufCodecs.BOOL, OpenBoardPayload::canEdit,
                OpenBoardPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoardCellsPayload(String key, byte[] cells) implements CustomPacketPayload {
        public static final Type<BoardCellsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "board_cells"));
        public static final StreamCodec<ByteBuf, BoardCellsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BoardCellsPayload::key,
                ByteBufCodecs.BYTE_ARRAY, BoardCellsPayload::cells,
                BoardCellsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 项目列表条目（菜单「打开已有」）。canEdit=false 表示该行对当前玩家只读。 */
    public record ProjectInfoPayload(String group, String name, String ownerName, int width, int height, boolean canEdit, boolean canDelete)
            implements CustomPacketPayload {
        public static final Type<ProjectInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "project_info"));
        public static final StreamCodec<ByteBuf, ProjectInfoPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ProjectInfoPayload::group,
                ByteBufCodecs.STRING_UTF8, ProjectInfoPayload::name,
                ByteBufCodecs.STRING_UTF8, ProjectInfoPayload::ownerName,
                ByteBufCodecs.VAR_INT, ProjectInfoPayload::width,
                ByteBufCodecs.VAR_INT, ProjectInfoPayload::height,
                ByteBufCodecs.BOOL, ProjectInfoPayload::canEdit,
                ByteBufCodecs.BOOL, ProjectInfoPayload::canDelete,
                ProjectInfoPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ProjectsListPayload(List<ProjectInfoPayload> projects) implements CustomPacketPayload {
        public static final Type<ProjectsListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "projects_list"));
        public static final StreamCodec<ByteBuf, ProjectsListPayload> CODEC = StreamCodec.composite(
                ProjectInfoPayload.CODEC.apply(ByteBufCodecs.list(512)), ProjectsListPayload::projects,
                ProjectsListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }



    /** 空载荷：指示客户端弹出主菜单（/tablegame drawboard 的入口）。 */
    public record OpenMenuPayload() implements CustomPacketPayload {
        public static final Type<OpenMenuPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "open_menu"));
        public static final StreamCodec<ByteBuf, OpenMenuPayload> CODEC = StreamCodec.unit(new OpenMenuPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 我的组名列表（新建项目界面「创建到」下拉用；单人组永远第一）。 */
    public record GroupsListPayload(List<String> groups) implements CustomPacketPayload {
        public static final Type<GroupsListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "groups_list"));
        public static final StreamCodec<ByteBuf, GroupsListPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), GroupsListPayload::groups,
                GroupsListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 单个组的结构化信息（组管理 GUI 用）。 */
    public record GroupUiPayload(String name, String owner, List<String> members, boolean open, List<String> readOnly, List<String> admins)
            implements CustomPacketPayload {
        public static final Type<GroupUiPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "group_ui"));
        public static final StreamCodec<ByteBuf, GroupUiPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GroupUiPayload::name,
                ByteBufCodecs.STRING_UTF8, GroupUiPayload::owner,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(128)), GroupUiPayload::members,
                ByteBufCodecs.BOOL, GroupUiPayload::open,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(128)), GroupUiPayload::readOnly,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(128)), GroupUiPayload::admins,
                GroupUiPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 组管理 GUI 快照：我的组（不含单人组，客户端自行拼首行）+ 我可加入的公开组。 */
    public record GroupsUiPayload(List<GroupUiPayload> mine, List<GroupUiPayload> joinable)
            implements CustomPacketPayload {
        public static final Type<GroupsUiPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "groups_ui"));
        public static final StreamCodec<ByteBuf, GroupsUiPayload> CODEC = StreamCodec.composite(
                GroupUiPayload.CODEC.apply(ByteBufCodecs.list(64)), GroupsUiPayload::mine,
                GroupUiPayload.CODEC.apply(ByteBufCodecs.list(64)), GroupsUiPayload::joinable,
                GroupsUiPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求我的组名列表（客户端在点「新建项目」时发，等回复再进新建界面）。 */
    public record RequestGroupsPayload() implements CustomPacketPayload {
        public static final Type<RequestGroupsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "groups_list_request"));
        public static final StreamCodec<ByteBuf, RequestGroupsPayload> CODEC = StreamCodec.unit(new RequestGroupsPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求组管理 GUI 快照（主菜单「组管理」与列表刷新共用）。 */
    public record RequestGroupUiPayload() implements CustomPacketPayload {
        public static final Type<RequestGroupUiPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "group_ui_request"));
        public static final StreamCodec<ByteBuf, RequestGroupUiPayload> CODEC = StreamCodec.unit(new RequestGroupUiPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 组管理 GUI 的通用操作请求：action 决定语义，其余字段按需取用：
     * <ul>
     *   <li>create   → group=新组名（其余忽略）
     *   <li>invite   → group=组名, player=被邀玩家
     *   <li>join / leave → group=组名
     *   <li>rule     → group=组名, flag=公开/私有
     *   <li>readonly → group=组名, player=目标成员, flag=只读/可写
     *   <li>admin    → group=组名, player=目标成员, flag=设为管理员/撤销
     *   <li>kick     → group=组名, player=被踢成员
     *   <li>disband  → group=组名（组内无项目才允许）
     * </ul>
     * 服务端执行后总是重发 {@link GroupsUiPayload} 快照供 GUI 刷新。
     */
    public record GroupActionPayload(String action, String group, String player, boolean flag)
            implements CustomPacketPayload {
        public static final Type<GroupActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "group_action"));
        public static final StreamCodec<ByteBuf, GroupActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GroupActionPayload::action,
                ByteBufCodecs.STRING_UTF8, GroupActionPayload::group,
                ByteBufCodecs.STRING_UTF8, GroupActionPayload::player,
                ByteBufCodecs.BOOL, GroupActionPayload::flag,
                GroupActionPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== C2S =====

    public record StrokeCellsPayload(String key, long strokeId, byte[] cells, boolean end)
            implements CustomPacketPayload {
        public static final Type<StrokeCellsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "stroke_cells"));
        public static final StreamCodec<ByteBuf, StrokeCellsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, StrokeCellsPayload::key,
                ByteBufCodecs.VAR_LONG, StrokeCellsPayload::strokeId,
                ByteBufCodecs.BYTE_ARRAY, StrokeCellsPayload::cells,
                ByteBufCodecs.BOOL, StrokeCellsPayload::end,
                StrokeCellsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UndoPayload(String key) implements CustomPacketPayload {
        public static final Type<UndoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "board_undo"));
        public static final StreamCodec<ByteBuf, UndoPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, UndoPayload::key,
                UndoPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 填充桶：点 (x,y) 起洪水填充成 color。 */
    public record FillPayload(String key, int x, int y, int color) implements CustomPacketPayload {
        public static final Type<FillPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "board_fill"));
        public static final StreamCodec<ByteBuf, FillPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FillPayload::key,
                ByteBufCodecs.VAR_INT, FillPayload::x,
                ByteBufCodecs.VAR_INT, FillPayload::y,
                ByteBufCodecs.VAR_INT, FillPayload::color,
                FillPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求项目列表（无参数）。 */
    public record RequestProjectsPayload() implements CustomPacketPayload {
        public static final Type<RequestProjectsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "projects_list_request"));
        public static final StreamCodec<ByteBuf, RequestProjectsPayload> CODEC = StreamCodec.unit(new RequestProjectsPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 新建项目：目标组 + 项目名 + 画布宽高（尺寸创建时定死；组由客户端「创建到」选择，空=单人组）。 */
    public record CreateProjectPayload(String group, String name, int width, int height) implements CustomPacketPayload {
        public static final Type<CreateProjectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "create_project"));
        public static final StreamCodec<ByteBuf, CreateProjectPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CreateProjectPayload::group,
                ByteBufCodecs.STRING_UTF8, CreateProjectPayload::name,
                ByteBufCodecs.VAR_INT, CreateProjectPayload::width,
                ByteBufCodecs.VAR_INT, CreateProjectPayload::height,
                CreateProjectPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 打开已有项目（key = 组/项目名）。 */
    public record OpenProjectPayload(String key) implements CustomPacketPayload {
        public static final Type<OpenProjectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "open_project"));
        public static final StreamCodec<ByteBuf, OpenProjectPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenProjectPayload::key,
                OpenProjectPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删除项目（key = 组/项目名）。权限与处置由服务端决定：真实组=组长/管理员，单人组=仅本人；单机直接删文件，专用服务器移入垃圾桶。 */
    public record DeleteProjectPayload(String key) implements CustomPacketPayload {
        public static final Type<DeleteProjectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "delete_project"));
        public static final StreamCodec<ByteBuf, DeleteProjectPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DeleteProjectPayload::key,
                DeleteProjectPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 导出当前画板（服务端写 JSON + PNG）。 */
    public record ExportPayload(String key) implements CustomPacketPayload {
        public static final Type<ExportPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "board_export"));
        public static final StreamCodec<ByteBuf, ExportPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ExportPayload::key,
                ExportPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }



    // ===== 注册（common 侧） =====

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(OpenBoardPayload.TYPE, OpenBoardPayload.CODEC);
        registrar.playToClient(BoardCellsPayload.TYPE, BoardCellsPayload.CODEC);
        registrar.playToClient(ProjectsListPayload.TYPE, ProjectsListPayload.CODEC);
        registrar.playToClient(OpenMenuPayload.TYPE, OpenMenuPayload.CODEC);
        registrar.playToClient(GroupsListPayload.TYPE, GroupsListPayload.CODEC);
        registrar.playToClient(GroupsUiPayload.TYPE, GroupsUiPayload.CODEC);
        registrar.playToServer(StrokeCellsPayload.TYPE, StrokeCellsPayload.CODEC, ServerHandler::handleStroke);
        registrar.playToServer(UndoPayload.TYPE, UndoPayload.CODEC, ServerHandler::handleUndo);
        registrar.playToServer(FillPayload.TYPE, FillPayload.CODEC, ServerHandler::handleFill);
        registrar.playToServer(RequestProjectsPayload.TYPE, RequestProjectsPayload.CODEC, ServerHandler::handleRequestProjects);
        registrar.playToServer(RequestGroupsPayload.TYPE, RequestGroupsPayload.CODEC, ServerHandler::handleRequestGroups);
        registrar.playToServer(RequestGroupUiPayload.TYPE, RequestGroupUiPayload.CODEC, ServerHandler::handleRequestGroupUi);
        registrar.playToServer(GroupActionPayload.TYPE, GroupActionPayload.CODEC, ServerHandler::handleGroupAction);
        registrar.playToServer(CreateProjectPayload.TYPE, CreateProjectPayload.CODEC, ServerHandler::handleCreate);
        registrar.playToServer(OpenProjectPayload.TYPE, OpenProjectPayload.CODEC, ServerHandler::handleOpen);
        registrar.playToServer(DeleteProjectPayload.TYPE, DeleteProjectPayload.CODEC, ServerHandler::handleDelete);
        registrar.playToServer(ExportPayload.TYPE, ExportPayload.CODEC, ServerHandler::handleExport);
    }

    // ===== 服务端处理器 =====

    static final class ServerHandler {
        static void handleStroke(StrokeCellsPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.stroke(sp, p.key(), p.strokeId(), p.cells(), p.end());
        }

        static void handleUndo(UndoPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            if (!bm.undo(sp, p.key())) {
                sp.sendSystemMessage(Component.literal("[画板] 没有可撤销的笔画（只撤销你自己画的）"));
            }
        }

        static void handleFill(FillPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.fill(sp, p.key(), p.x(), p.y(), p.color());
        }

        static void handleRequestProjects(RequestProjectsPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.sendProjectList(sp);
        }

        static void handleCreate(CreateProjectPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.createProject(sp, p.group(), p.name(), p.width(), p.height());
        }

        static void handleRequestGroups(RequestGroupsPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.sendMyGroups(sp);
        }

        static void handleRequestGroupUi(RequestGroupUiPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.sendGroupUi(sp);
        }

        static void handleGroupAction(GroupActionPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.groupAction(sp, p.action(), p.group(), p.player(), p.flag());
        }

        static void handleOpen(OpenProjectPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.openProject(sp, p.key());
        }

        static void handleDelete(DeleteProjectPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.deleteProject(sp, p.key());
        }

        static void handleExport(ExportPayload p, IPayloadContext ctx) {
            ServerPlayer sp = asServerPlayer(ctx);
            BoardManager bm = BoardManager.get();
            if (sp == null || bm == null) return;
            bm.exportBoard(sp, p.key());
        }

        private static ServerPlayer asServerPlayer(IPayloadContext ctx) {
            Player player = ctx.player();
            return player instanceof ServerPlayer sp ? sp : null;
        }
    }
}
