package com.tablegame.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 词法分析：脚本文本 → token 序列（语言三层的第一层：文本 →（本类）→ token →（{@link Parser}）→ AST →（{@link Interp}）→ 跑）。
 *
 * <p>每个 token 带行号 ⇒ 后面任何一层报错都能指到行。规则（见 KB《引擎脚本语言规格》§一）：
 * 标识符 {@code [A-Za-z_][A-Za-z0-9_]*} 只用英文（写中文直接报错指行）· 数字十进制含小数 ·
 * 文本 {@code "…"} 带 {@code \" \\ \n \t} 转义 · 注释 {@code // 行} 与 {@code /* 块 *}{@code /} ·
 * 运算符 {@code + - * / % == != < <= > >= && || ! = += -= *= ++ -- [ ] { }, : ;. #}
 */
public final class Lexer {

    /** token 种类：数字 / 文本 / 标识符（含关键字）/ 符号 / 到头。 */
    public enum Kind { NUM, STR, ID, SYM, EOF }

    /** 一个 token：种类 + 原文 + 行号。文本 token 的 text 已经是"转义解开后"的真值。 */
    public record Token(Kind kind, String text, int line) {
        /** 是不是某个确定的符号或标识符（如 {@code "+"} / {@code "var"}）；数字/文本 token 不算。 */
        public boolean is(String s) {
            return (kind == Kind.SYM || kind == Kind.ID) && text.equals(s);
        }
    }

    /**
     * 关键字表（不能拿来当变量名 / 函数名）。
     *
     * <p>{@code box} / {@code text} 故意不在表里：{@code text(...)} 是内建函数（进了表表达式里就没法调它），
     * 它们是**画块里才认**的语句（见 {@link Parser#drawBlock}）。
     *
     * <p>⚠ {@code npc} / {@code players} / {@code card} / {@code piece} 是**软关键字**：不进表（名字位置上照旧能用），
     * 由 {@link Parser#script} 顶层那一处按源码文本认 —— 老档在那里给一句带指引的报错。
     */
    public static final Set<String> KEYWORDS = Set.of(
            "var", "if", "else", "while", "for", "func", "return", "break", "continue",
            "on", "stage", "goto", "say", "wait", "timer", "end", "show", "hide",
            "true", "false", "and", "or", "not",
            "part", "screen", "click", "world", "look", "entity", "dim", "allow_replace", "resident", "area",
            "offline");
    // ⚠ 软关键字就这四个（npc / players / card / piece）：名字位置上必须能用（`var players = []` /
    //  `part card { }`），所以不能进表；识别放在 Parser.script 顶层那一处。

    /** 运算符/分隔符表：<b>两个字符的排前面</b>，见 {@link #symbol} 的顺序匹配。 */
    private static final String[] SYMBOLS = {
            "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "++", "--",
            "+", "-", "*", "/", "%", "<", ">", "=", "!", "?",
            "(", ")", "[", "]", "{", "}", ",", ":", ";", ".", "@", "#"};

    private final String src;
    private final List<Token> out = new ArrayList<>();
    private int i;
    private int line = 1;

    private Lexer(String src) {
        this.src = src == null ? "" : src;
    }

    /** 切一段脚本。语法/词法错 → {@link Ast.ScriptError}（带行号）。 */
    public static List<Token> lex(String src) {
        return new Lexer(src).run();
    }

    private List<Token> run() {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                i++;
            } else if (c == '/' && peek(1) == '/') {                 // 行注释
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && peek(1) == '*') {                 // 块注释
                blockComment();
            } else if (c == '"') {
                string();
            } else if (c >= '0' && c <= '9') {
                number();
            } else if (isIdentStart(c)) {
                ident();
            } else if (!symbol()) {
                throw new Ast.ScriptError(line, "不认识的字符 “" + c + "”（标识符只能用英文；文本要包在双引号里）");
            }
        }
        out.add(new Token(Kind.EOF, "", line));
        return out;
    }

    /** 块注释：跨行；没关上的 {@code /*} 在起始行报错。 */
    private void blockComment() {
        int start = line;
        i += 2;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') line++;
            if (c == '*' && peek(1) == '/') {
                i += 2;
                return;
            }
            i++;
        }
        throw new Ast.ScriptError(start, "块注释没有关上（少了 */）");
    }

    /** 文本字面量：{@code "…"}，支持 {@code \" \\ \n \t}；不允许跨行。 */
    private void string() {
        int start = line;
        i++;                                                          // 跳过开头的 "
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= src.length()) throw new Ast.ScriptError(start, "文本没有关上的双引号");
            char c = src.charAt(i++);
            if (c == '"') break;
            if (c == '\n') throw new Ast.ScriptError(start, "文本不能跨行（双引号要写在同一行里）");
            if (c == '\\') {
                if (i >= src.length()) throw new Ast.ScriptError(line, "转义符后面没有字符");
                char e = src.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> throw new Ast.ScriptError(line, "不认识的转义 \\" + e + "（只认 \\\" \\\\ \\n \\t）");
                }
            } else {
                sb.append(c);
            }
        }
        out.add(new Token(Kind.STR, sb.toString(), start));
    }

    /** 数字：十进制，{@code 1} / {@code 1.5}（{@code 1.} 这种尾巴不带数字的当两段处理）。 */
    private void number() {
        int start = i;
        while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
        if (i + 1 < src.length() && src.charAt(i) == '.' && Character.isDigit(src.charAt(i + 1))) {
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
        }
        out.add(new Token(Kind.NUM, src.substring(start, i), line));
    }

    /** 标识符（含关键字原文；是不是关键字由 {@link #KEYWORDS} 判定）。 */
    private void ident() {
        int start = i;
        while (i < src.length() && isIdentPart(src.charAt(i))) i++;
        out.add(new Token(Kind.ID, src.substring(start, i), line));
    }

    /** 匹配一个运算符/分隔符（两个字符的先试）。认不出返回 false。 */
    private boolean symbol() {
        for (String s : SYMBOLS) {
            if (src.startsWith(s, i)) {
                out.add(new Token(Kind.SYM, s, line));
                i += s.length();
                return true;
            }
        }
        return false;
    }

    private char peek(int k) {
        return i + k < src.length() ? src.charAt(i + k) : '\0';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
