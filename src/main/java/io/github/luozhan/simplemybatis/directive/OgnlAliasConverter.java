package io.github.luozhan.simplemybatis.directive;

/**
 * OGNL 表达式工具：处理 {@code #(expression)} 中的自定义 test 表达式。
 *
 * <p>职责：
 * <ol>
 *   <li>把表达式内的 {@code &&} / {@code ||} 统一转换为 OGNL 关键字 {@code and} / {@code or}
 *       （跳过字符串字面量）——OGNL 原生识别 {@code and}/{@code or}，且输出不含 {@code &}，
 *       从而生成的 {@code <if test="...">} 被 MyBatis 按 XML 再解析时无需转义 {@code &}；</li>
 *   <li>把最终 test 文本作为 XML 属性值转义（{@code &}、{@code <}、{@code >}、{@code "}），
 *       兜底处理表达式里可能残留的特殊字符。</li>
 * </ol>
 */
public final class OgnlAliasConverter {

    private OgnlAliasConverter() {
    }

    /**
     * 把表达式内的 {@code &&} → {@code and}、{@code ||} → {@code or}，跳过字符串字面量。
     */
    public static String normalizeOperators(String expr) {
        StringBuilder out = new StringBuilder(expr.length());
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\'') {
                // 字符串字面量：原样拷贝到闭合引号（含 '' 转义）
                out.append(c);
                i++;
                while (i < n) {
                    char ch = expr.charAt(i);
                    out.append(ch);
                    if (ch == '\'') {
                        if (i + 1 < n && expr.charAt(i + 1) == '\'') {
                            out.append('\'');
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
            if (c == '&' && i + 1 < n && expr.charAt(i + 1) == '&') {
                out.append("and");
                i += 2;
                continue;
            }
            if (c == '|' && i + 1 < n && expr.charAt(i + 1) == '|') {
                out.append("or");
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 把 test 表达式转义为可安全嵌入 {@code <if test="...">} 双引号属性的形式。
     */
    public static String escapeForXmlAttribute(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 归一化 + XML 属性转义的组合：把用户 {@code #(expr)} 直接变为可用的 test 属性值。
     */
    public static String toTestAttribute(String expr) {
        return escapeForXmlAttribute(normalizeOperators(expr).trim());
    }
}
