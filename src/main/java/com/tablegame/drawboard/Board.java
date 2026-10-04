package com.tablegame.drawboard;

import java.util.ArrayDeque;

import java.util.Deque;
import java.util.LinkedHashMap;

    /**
     * 一块「画板项目」在服务端的数据模型。
     *
     * <p>以命名项目为键（非玩家 UUID）：归属某组（{@link #group}）、有项目名（{@link #name}）、画布宽×高。
     * 文件落在 {@code <游戏目录>/tablegame/projects/<组>/<项目名>.json}。
     * pixels 是唯一像素真源（服务端权威）：下标 = y * width + x，每格一个 ARGB int。
     * 撤销栈双上限（条数 + 总格数）防填充吃爆内存；同一笔画以 strokeId 分组、松手才封存成一步。
     */
public class Board {
    /** 组名（文件路径第一段；单人组 = 玩家名）。 */
    public String group;
    /** 项目名（组内唯一）。 */
    public String name;
    /** 作者/创建者显示名。 */
    public String ownerName;
    /** 画布宽（格）。 */
    public int width;
    /** 画布高（格）。 */
    public int height;
    /** 像素真源，长度 = width*height，ARGB。 */
    public int[] pixels;

    /** 撤销栈：ArrayDeque 当双端队列用，头部最旧、尾部最新。 */
    final Deque<UndoEntry> undoStack = new ArrayDeque<>();
    private int undoCellCount = 0;
    static final int MAX_UNDO = 100;
    static final int MAX_UNDO_CELLS = 400_000;
    /** 数据被改过（需要落盘）的脏标记。 */
    boolean dirty;

    /** 撤销条目。 */
    static class UndoEntry {
        final String authorId;
        final byte[] oldCells;

        UndoEntry(String authorId, byte[] oldCells) {
            this.authorId = authorId;
            this.oldCells = oldCells;
        }
    }

    /** 进行中的笔画（同一 strokeId 连续增量，end 才封存）。 */
    static class OpenStroke {
        final String authorId;
        long strokeId;
        final LinkedHashMap<Integer, Integer> changed = new LinkedHashMap<>();

        OpenStroke(String authorId, long strokeId) {
            this.authorId = authorId;
            this.strokeId = strokeId;
        }
    }

    final java.util.Map<String, OpenStroke> openStrokes = new java.util.HashMap<>();

    public Board(String group, String name, String ownerName, int width, int height) {
        this.group = group;
        this.name = name;
        this.ownerName = ownerName;
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    /** 服务端内部复合键（网络消息/缓存都用它）：组/项目名。 */
    public String key() {
        return group + "/" + name;
    }

    int index(int x, int y) {
        return y * width + x;
    }

    boolean inBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    // ===== 笔画 =====

    /** 应用一段笔画增量；@return 实际变化的格子数。 */
    int applyStroke(byte[] cells, String authorId, long strokeId, boolean end) {
        if (cells.length % 8 != 0) return 0;
        OpenStroke open = openStrokes.get(authorId);
        if (open == null || open.strokeId != strokeId) {
            seal(authorId);
            open = new OpenStroke(authorId, strokeId);
            openStrokes.put(authorId, open);
        }
        int changed = 0;
        for (int off = 0; off < cells.length; off += 8) {
            int x = Cells.u16(cells, off);
            int y = Cells.u16(cells, off + 2);
            int argb = Cells.argbFromBytes(cells, off + 4);
            if (!inBounds(x, y)) continue;
            int idx = index(x, y);
            if (pixels[idx] == argb) continue;
            open.changed.putIfAbsent(idx, pixels[idx]);
            pixels[idx] = argb;
            changed++;
        }
        if (end) seal(authorId);
        if (changed > 0) dirty = true;
        return changed;
    }

    /** 洪水填充；@return 被改格子新色的单元格字节流，无需改返回 null。 */
    byte[] applyFill(String authorId, int sx, int sy, int color) {
        if (!inBounds(sx, sy)) return null;
        int startColor = pixels[index(sx, sy)];
        if (startColor == color) return null;
        seal(authorId);
        LinkedHashMap<Integer, Integer> changed = new LinkedHashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(index(sx, sy));
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            if (pixels[idx] != startColor) continue;
            int x = idx % width, y = idx / width;
            changed.putIfAbsent(idx, pixels[idx]);
            pixels[idx] = color;
            if (x > 0 && pixels[idx - 1] == startColor) queue.add(idx - 1);
            if (x + 1 < width && pixels[idx + 1] == startColor) queue.add(idx + 1);
            if (y > 0 && pixels[idx - width] == startColor) queue.add(idx - width);
            if (y + 1 < height && pixels[idx + width] == startColor) queue.add(idx + width);
        }
        if (changed.isEmpty()) return null;
        byte[] newCells = new byte[changed.size() * 8];
        byte[] oldCells = new byte[changed.size() * 8];
        int off = 0;
        for (var e : changed.entrySet()) {
            int idx = e.getKey();
            Cells.write(newCells, off, idx % width, idx / width, pixels[idx]);
            Cells.write(oldCells, off, idx % width, idx / width, e.getValue());
            off += 8;
        }
        pushUndo(authorId, oldCells);
        dirty = true;
        return newCells;
    }



    // ===== 撤销 =====

    /** 撤销某作者「最近一笔已封存」的操作；@return 还原用的旧色单元格流或 null。 */
    byte[] undoLatestOf(String authorId) {
        seal(authorId);
        var it = undoStack.descendingIterator();
        while (it.hasNext()) {
            UndoEntry e = it.next();
            if (e.authorId.equals(authorId)) {
                it.remove();
                undoCellCount -= e.oldCells.length / 8;
                for (int off = 0; off < e.oldCells.length; off += 8) {
                    int x = Cells.u16(e.oldCells, off);
                    int y = Cells.u16(e.oldCells, off + 2);
                    if (!inBounds(x, y)) continue;
                    pixels[index(x, y)] = Cells.argbFromBytes(e.oldCells, off + 4);
                }
                dirty = true;
                return e.oldCells;
            }
        }
        return null;
    }

    private void seal(String authorId) {
        OpenStroke open = openStrokes.remove(authorId);
        if (open == null || open.changed.isEmpty()) return;
        byte[] old = new byte[open.changed.size() * 8];
        int off = 0;
        for (var e : open.changed.entrySet()) {
            int idx = e.getKey();
            Cells.write(old, off, idx % width, idx / width, e.getValue());
            off += 8;
        }
        pushUndo(authorId, old);
    }

    private void pushUndo(String authorId, byte[] oldCells) {
        if (oldCells.length == 0) return;
        undoStack.addLast(new UndoEntry(authorId, oldCells));
        undoCellCount += oldCells.length / 8;
        while (undoStack.size() > MAX_UNDO || undoCellCount > MAX_UNDO_CELLS) {
            UndoEntry drop = undoStack.removeFirst();
            undoCellCount -= drop.oldCells.length / 8;
        }
    }

    /** 丢弃某作者所有未封存笔画（玩家断线等）。 */
    void dropOpenStroke(String authorId) {
        openStrokes.remove(authorId);
    }
}
