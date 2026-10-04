package com.tablegame.editor;

    /**
     * 编辑器工具屏的公共标注：挂在某个 {@link GameEditorScreen} 上的工具屏
     * （棋子 / 卡牌 / 数值 / 脚本 / 画布 / 界面 / 组件 / 世界 / 节点编辑等）实现它，交出背后的编辑器。
     */
public interface EditorToolScreen {
    /** 这个工具屏挂在哪个编辑器上（null = 不属于任何编辑器，按「都没开着」处理）。 */
    GameEditorScreen editor();
}
