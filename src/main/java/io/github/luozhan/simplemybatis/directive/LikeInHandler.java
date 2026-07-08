package io.github.luozhan.simplemybatis.directive;

import io.github.luozhan.simplemybatis.scan.SqlScanner;

/**
 * like / in 优化语法处理器。
 *
 * <ul>
 *   <li>like 三形式 {@code %:name%} / {@code :name%} / {@code %:name}：用 {@code <bind>} 在运行期把
 *       通配符拼接到<b>参数值</b>（绑定值即 {@code %张%}），规避跨库 {@code concat}/{@code ||} 差异，
 *       且不改动 parameterHandler；</li>
 *   <li>in 集合 {@code in (:list)}：用 {@code <foreach>} 展开，配合 {@code list != null and list.size() > 0} 判空。</li>
 * </ul>
 */
public final class LikeInHandler {

    private LikeInHandler() {
    }

    /**
     * like 匹配结果。
     */
    public static final class LikeMatch {
        public final String column;      // like 左侧列表达式（含结尾空格已 trim）
        public final String param;       // 参数名
        public final boolean leftWild;   // 是否有前置 %
        public final boolean rightWild;  // 是否有后置 %

        LikeMatch(String column, String param, boolean leftWild, boolean rightWild) {
            this.column = column;
            this.param = param;
            this.leftWild = leftWild;
            this.rightWild = rightWild;
        }
    }

    /**
     * 尝试把条件解析为 like 形式。返回 {@code null} 表示不是 like 优化语法。
     * 仅识别 “{@code <col> like [%]:param[%]}” 且通配符/参数为唯一 like 目标的情形。
     */
    public static LikeMatch matchLike(String cond) {
        int idx = indexOfKeyword(cond, "like");
        if (idx < 0) {
            return null;
        }
        String column = cond.substring(0, idx).trim();
        String right = cond.substring(idx + 4).trim();
        if (column.isEmpty() || right.isEmpty()) {
            return null;
        }
        boolean leftWild = right.startsWith("%");
        if (leftWild) {
            right = right.substring(1);
        }
        boolean rightWild = right.endsWith("%");
        if (rightWild) {
            right = right.substring(0, right.length() - 1);
        }
        // 剩余必须恰好是一个 :param
        if (right.length() < 2 || right.charAt(0) != ':' || !SqlScanner.isIdentStart(right.charAt(1))) {
            return null;
        }
        for (int i = 1; i < right.length(); i++) {
            if (!SqlScanner.isIdentPart(right.charAt(i))) {
                return null;
            }
        }
        String param = right.substring(1);
        return new LikeMatch(column, param, leftWild, rightWild);
    }

    /**
     * like 的绑定值表达式：把通配符拼到<b>参数值</b>（用于 {@code <bind>}）。
     */
    public static String likeValueExpr(LikeMatch m) {
        if (m.leftWild && m.rightWild) {
            return "'%' + " + m.param + " + '%'";
        } else if (m.rightWild) {
            return m.param + " + '%'";
        } else if (m.leftWild) {
            return "'%' + " + m.param;
        }
        // 无通配符：等价普通绑定
        return m.param;
    }

    /**
     * in 匹配结果。
     */
    public static final class InMatch {
        public final String column;  // in 左侧列表达式
        public final String param;   // 集合参数名

        InMatch(String column, String param) {
            this.column = column;
            this.param = param;
        }
    }

    /**
     * 尝试把条件解析为 {@code <col> in (:list)} 形式。返回 {@code null} 表示不是 in 优化语法。
     */
    public static InMatch matchIn(String cond) {
        int idx = indexOfKeyword(cond, "in");
        if (idx < 0) {
            return null;
        }
        String column = cond.substring(0, idx).trim();
        String right = cond.substring(idx + 2).trim();
        if (column.isEmpty() || !right.startsWith("(") || !right.endsWith(")")) {
            return null;
        }
        String inner = right.substring(1, right.length() - 1).trim();
        if (inner.length() < 2 || inner.charAt(0) != ':' || !SqlScanner.isIdentStart(inner.charAt(1))) {
            return null;
        }
        for (int i = 1; i < inner.length(); i++) {
            if (!SqlScanner.isIdentPart(inner.charAt(i))) {
                return null;
            }
        }
        return new InMatch(column, inner.substring(1));
    }

    /**
     * 生成 in 的 {@code <foreach>} 主体（不含外层 {@code <if>} 与连接词）。
     */
    public static String renderInBody(InMatch m, String itemName) {
        return m.column + " in <foreach collection=\"" + m.param + "\" item=\"" + itemName
                + "\" open=\"(\" separator=\",\" close=\")\">#{" + itemName + "}</foreach>";
    }

    /**
     * in 的判空 test。
     */
    public static String inTest(InMatch m) {
        return m.param + " != null and " + m.param + ".size() > 0";
    }

    /**
     * like 的判空 test。
     */
    public static String likeTest(LikeMatch m) {
        return m.param + " != null and " + m.param + " != ''";
    }

    /**
     * 在文本中查找关键字（如 {@code like}/{@code in}）的下标：要求前后为词边界，
     * 跳过字符串字面量，且仅在括号深度 0 处匹配。返回 -1 表示未找到。
     */
    static int indexOfKeyword(String text, String keyword) {
        int n = text.length();
        int kl = keyword.length();
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (text.charAt(i) == '\'') {
                        if (i + 1 < n && text.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && (c == keyword.charAt(0) || Character.toLowerCase(c) == keyword.charAt(0))
                    && i + kl <= n
                    && text.regionMatches(true, i, keyword, 0, kl)) {
                boolean leftBoundary = (i == 0) || !SqlScanner.isIdentPart(text.charAt(i - 1));
                boolean rightBoundary = (i + kl == n) || !SqlScanner.isIdentPart(text.charAt(i + kl));
                if (leftBoundary && rightBoundary) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }
}
