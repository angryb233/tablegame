package com.tablegame.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 编辑器历史：撤销 / 重做。**只存脚本文本**（真源就是脚本，模型 / 舞台 / 卡牌都是它的视图）—— 撤销 = 把上一份文本再走一遍 {@code applyScript}。
 * 三条纪律：新改动**清空重做链** · 栈深上限 30（ponytail: 定长就够，真要几百步再换环形缓冲）·
 * 撤销 / 重做自己触发的写回**不再压栈**（调用方用 {@code inHistory} 挡住）。
 */
public final class EditHistory {

    private static final int MAX = 30;

    /** 撤销栈（最近在上）：存「改之前」那一份。 */
    private final Deque<String> undoStack = new ArrayDeque<>();
    /** 重做栈（最近在上）。 */
    private final Deque<String> redoStack = new ArrayDeque<>();

    /** 记一笔「改之前」的文本；<b>新的改动把重做链断掉</b>（同所有编辑器）。 */
    public void push(String before) {
        if (before == null) return;
        undoStack.push(before);
        while (undoStack.size() > MAX) undoStack.removeLast();
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * 撤销：拿出上一份文本，把当前这份收进重做栈。
     *
     * @return 要写回的文本；没得撤销 = null
     */
    public String undo(String current) {
        if (undoStack.isEmpty()) return null;
        redoStack.push(current == null ? "" : current);
        return undoStack.pop();
    }

    /** 重做：与 {@link #undo} 对称。 */
    public String redo(String current) {
        if (redoStack.isEmpty()) return null;
        undoStack.push(current == null ? "" : current);
        return redoStack.pop();
    }

    /** 撤销失败（写回被真源解析拒收）时把那一份还回去 —— 别把历史吃掉。 */
    public void giveBack(String text) {
        if (text != null) undoStack.push(text);
    }

    /** 还一份给重做栈（重做失败时用）。 */
    public void giveBackRedo(String text) {
        if (text != null) redoStack.push(text);
    }

    /** 清空（换了一份档 / 重进编辑器时用）。 */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    /** 栈深（自检用）。 */
    public int depth() {
        return undoStack.size();
    }
}
