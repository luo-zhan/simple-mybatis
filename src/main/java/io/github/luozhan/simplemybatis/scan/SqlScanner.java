package io.github.luozhan.simplemybatis.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 SQL 字符扫描器：单趟扫描完成 {@code :param} → {@code #{param}} 的安全转换，
 * 并在扫描时避让以下场景，保证不误伤：
 * <ul>
 *   <li>字符串字面量 {@code '...'}（含 {@code ''} 转义）；</li>
 *   <li>原生占位符 {@code #{...}} / {@code ${...}}；</li>
 *   <li>PostgreSQL 类型转换 {@code ::cast}；</li>
 *   <li>{@code :'literal'} 形式。</li>
 * </ul>
 *
 * <p>不做 SQL 结构化解析——仅按字符规则工作，因此对复杂/方言 SQL 同样健壮。
 */
public final class SqlScanner {

    private SqlScanner() {
    }

    /**
     * {@code :param} 扫描结果：转换后的文本 + 出现的参数名（按出现顺序，可能重复）。
     */
    public static final class ColonScanResult {
        private final String text;
        private final List<String> params;

        ColonScanResult(String text, List<String> params) {
            this.text = text;
            this.params = params;
        }

        public String getText() {
            return text;
        }

        public List<String> getParams() {
            return params;
        }
    }

    /**
     * 把文本中的 {@code :标识符} 转换为 {@code #{标识符}}，并收集参数名。
     */
    public static ColonScanResult convertColonParams(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        List<String> params = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = copyStringLiteral(text, i, out);
                continue;
            }
            if ((c == '#' || c == '$') && i + 1 < n && text.charAt(i + 1) == '{') {
                i = copyBraced(text, i, out);
                continue;
            }
            if (c == ':') {
                // {:name ...} 扩展记号：保留给 InlineDirective，不做 :param 转换
                if (i > 0 && text.charAt(i - 1) == '{') {
                    out.append(c);
                    i++;
                    continue;
                }
                // ::cast —— 原样保留两个冒号
                if (i + 1 < n && text.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }
                // :'literal' —— 保留冒号，字符串字面量交给下一轮处理
                if (i + 1 < n && text.charAt(i + 1) == '\'') {
                    out.append(c);
                    i++;
                    continue;
                }
                // :标识符 —— 转换为 #{标识符}
                if (i + 1 < n && isIdentStart(text.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < n && isIdentPart(text.charAt(j))) {
                        j++;
                    }
                    String name = text.substring(i + 1, j);
                    out.append("#{").append(name).append("}");
                    params.add(name);
                    i = j;
                    continue;
                }
                // 其他情况：普通冒号
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return new ColonScanResult(out.toString(), params);
    }

    /**
     * 仅收集 {@code :标识符} 参数名（不改动文本），扫描规则与转换一致。
     */
    public static List<String> collectParams(String text) {
        return convertColonParams(text).getParams();
    }

    /**
     * 从下标 {@code start}（指向起始引号）拷贝一个字符串字面量到 out，返回结束后的下标。
     */
    private static int copyStringLiteral(String text, int start, StringBuilder out) {
        int n = text.length();
        int i = start;
        out.append(text.charAt(i)); // 起始 '
        i++;
        while (i < n) {
            char ch = text.charAt(i);
            out.append(ch);
            if (ch == '\'') {
                // '' 转义
                if (i + 1 < n && text.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i += 2;
                    continue;
                }
                i++;
                return i;
            }
            i++;
        }
        return i;
    }

    /**
     * 从下标 {@code start}（指向 # 或 $）拷贝一个 {@code #{...}}/{@code ${...}} 到 out，返回结束后的下标。
     */
    private static int copyBraced(String text, int start, StringBuilder out) {
        int n = text.length();
        int i = start;
        out.append(text.charAt(i)); // # 或 $
        out.append('{');
        i += 2;
        int depth = 1;
        while (i < n && depth > 0) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            }
            out.append(ch);
            i++;
        }
        return i;
    }

    public static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    public static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }
}
