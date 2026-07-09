package io.github.luozhan.simplemybatis.directive;

import io.github.luozhan.simplemybatis.scan.SqlScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一个 {@code #} 作用域的内容渲染为若干标准 MyBatis {@code <if>} 片段。
 *
 * <p>职责：
 * <ul>
 *   <li>按顶层 {@code and}/{@code or} 把作用域拆成独立条件（保护 {@code between ... and ...} 内部的 and、
 *       跳过字符串字面量、忽略括号内的连接词）；</li>
 *   <li>每个条件识别 like / in 优化语法，否则按通用规则处理；</li>
 *   <li>通用判空：条件内每个 {@code :param} 生成 {@code p != null and p != ''}，用 {@code and} 连接；
 *       无参数的条件视为静态，不包 {@code <if>}；</li>
 *   <li>处理连接词：作用域首条件用传入的 marker 连接词（{@code #where} 为空，{@code #and}/{@code #or} 为对应词），
 *       其余条件用拆分处得到的 {@code and}/{@code or}。</li>
 * </ul>
 *
 * <p>本类为“每条语句一个实例”，内部维护唯一命名计数器（用于 like 的 {@code <bind>} 变量）。
 */
public class HashConditionParser {

    private final String sourceId;

    public HashConditionParser() {
        this(null);
    }

    public HashConditionParser(String sourceId) {
        this.sourceId = sourceId;
    }

    /**
     * 作用域内拆分出的一个片段。
     */
    private static final class Seg {
        final String op;   // 连接词：首片段为 null（使用 marker 连接词），其余为 "and"/"or"
        final String text;

        Seg(String op, String text) {
            this.op = op;
            this.text = text;
        }
    }

    /**
     * 渲染默认作用域（{@code #where}/{@code #and}/{@code #or}/{@code #}）。
     *
     * @param markerConnector marker 连接词：{@code ""}（#where/#）、{@code "and"}（#and）、{@code "or"}（#or）
     * @param body            marker 之后的作用域文本
     * @return 若干 {@code <if>} 片段拼接的字符串
     */
    public String renderScope(String markerConnector, String body) {
        List<Seg> segs = split(body);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            Seg seg = segs.get(i);
            String op = (i == 0) ? markerConnector : seg.op;
            String rendered = renderOne(op, seg.text.trim());
            if (rendered.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(rendered);
        }
        return out.toString();
    }

    /**
     * 渲染自定义表达式作用域 {@code #(expr) body}：整段共用一个 {@code <if test="expr">}。
     * body 内保留用户书写的连接词；{@code :param} 转换、like/in 糖在 body 内照常生效。
     */
    public String renderCustomExpr(String expr, String body) {
        String test = OgnlAliasConverter.toTestAttribute(expr);
        String rendered = renderExprBody(body.trim());
        return "<if test=\"" + test + "\">" + rendered + "</if>";
    }

    /**
     * 渲染 {@code #(expr)} 的 body：按顶层 and/or 拆分，逐条件展开 like/in 糖（不包 {@code <if>}），
     * 保留原有连接词，整体置于外层自定义 {@code <if test>} 之下。
     */
    private String renderExprBody(String body) {
        StringBuilder sb = new StringBuilder();
        for (Seg seg : split(body)) {
            String text = seg.text.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (seg.op != null) {
                sb.append(seg.op).append(' ');
            }
            sb.append(renderConditionClause(text));
        }
        return sb.toString();
    }

    /**
     * 渲染单个条件的 SQL 子句（不包 {@code <if>}、不含连接词）：
     * in → {@code <foreach>}；like → {@code <bind>} + like；其余 → :param 转换 + XML 转义。
     */
    private String renderConditionClause(String cond) {
        LikeInHandler.InMatch in = LikeInHandler.matchIn(cond);
        if (in != null) {
            String item = "__item_" + in.param.replace('.', '_');
            return LikeInHandler.renderInBody(in, item);
        }
        // in :param 缺少括号 —— 友好报错，避免生成非法 SQL
        checkInvalidInSyntax(cond);

        LikeInHandler.LikeMatch like = LikeInHandler.matchLike(cond);
        if (like != null) {
            String bindName = "__" + like.param.replace('.', '_') + "_like";
            return "<bind name=\"" + bindName + "\" value=\"" + LikeInHandler.likeValueExpr(like) + "\"/>"
                    + escapeContent(like.column) + " like #{" + bindName + "}";
        }
        return escapeContent(SqlScanner.convertColonParams(cond).getText());
    }

    /**
     * 渲染单个条件为一个 {@code <if>}（或无参时的静态片段）。
     */
    private String renderOne(String op, String cond) {
        if (cond.isEmpty()) {
            return "";
        }
        String prefix = (op == null || op.isEmpty()) ? "" : op + " ";

        // in (:list)
        LikeInHandler.InMatch in = LikeInHandler.matchIn(cond);
        if (in != null) {
            // 按集合参数名派生 item 名（foreach 块级作用域，无需计数器）
            String item = "__item_" + in.param.replace('.', '_');
            String bodyText = prefix + LikeInHandler.renderInBody(in, item);
            return "<if test=\"" + LikeInHandler.inTest(in) + "\">" + bodyText + "</if>";
        }

        // in :param 缺少括号 —— 友好报错，避免生成非法 SQL
        checkInvalidInSyntax(cond);

        // like %:name% / :name% / %:name
        LikeInHandler.LikeMatch like = LikeInHandler.matchLike(cond);
        if (like != null) {
            // 按参数名派生 bind 名，可读且按参数天然区分
            String bindName = "__" + like.param.replace('.', '_') + "_like";
            String bind = "<bind name=\"" + bindName + "\" value=\"" + LikeInHandler.likeValueExpr(like) + "\"/>";
            String clause = escapeContent(like.column) + " like #{" + bindName + "}";
            return "<if test=\"" + LikeInHandler.likeTest(like) + "\">" + bind + prefix + clause + "</if>";
        }

        // 通用：between 也走此路（其两个 :param 均被收集，任一为空整体省略）
        List<String> params = SqlScanner.collectParams(cond);
        // 兼容原生 #{expr} / ${expr} 占位符的判空支持
        for (String p : SqlScanner.collectNativeParams(cond)) {
            if (!params.contains(p)) {
                params.add(p);
            }
        }
        String converted = escapeContent(SqlScanner.convertColonParams(cond).getText());
        if (params.isEmpty()) {
            // 无参数 —— 视为静态条件，不包 <if>
            return prefix + converted;
        }
        String test = buildNullTest(params);
        return "<if test=\"" + test + "\">" + prefix + converted + "</if>";
    }

    /**
     * 检测 {@code in :param}（缺少括号）的非法用法并抛出友好异常。
     * <p>当 {@link LikeInHandler#matchIn} 返回 {@code null} 后调用：
     * 若条件中存在 {@code in} 关键字且其后紧跟 {@code :param}，说明用户意图使用 in 糖语法但漏写了括号，
     * 此时生成的 SQL（{@code in #{param}}）在数据库端必然报语法错误，应在预处理阶段提前拦截。
     */
    private void checkInvalidInSyntax(String cond) {
        int idx = LikeInHandler.indexOfKeyword(cond, "in");
        if (idx < 0) {
            return;
        }
        String right = cond.substring(idx + 2).trim();
        if (right.startsWith(":")) {
            String param = right.substring(1);
            int end = 0;
            while (end < param.length() && SqlScanner.isIdentPart(param.charAt(end))) {
                end++;
            }
            param = param.substring(0, end);
            throw new IllegalStateException(
                    "增强语法解析失败" + sourceHint() + "：in 糖语法需用括号包裹参数，请使用 \"in (:" + param + ")\" 而非 \"in :" + param + "\"");
        }
    }

    private String sourceHint() {
        return (sourceId != null && !sourceId.isEmpty()) ? "（Mapper 方法: " + sourceId + "）" : "";
    }

    /**
     * 把用户 SQL 文本转义为可安全放入 {@code <if>} 元素内容的形式（{@code &}/{@code <}/{@code >}）。生成的 #{}/标签不含这些字符。
     */
    private static String escapeContent(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 由参数名列表构建判空 test：每个 {@code p != null and p != ''}，用 and 连接。去重保持顺序。
     */
    private String buildNullTest(List<String> params) {
        StringBuilder sb = new StringBuilder();
        List<String> seen = new ArrayList<>();
        for (String p : params) {
            if (seen.contains(p)) {
                continue;
            }
            seen.add(p);
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            sb.append(p).append(" != null and ").append(p).append(" != ''");
        }
        return sb.toString();
    }

    /**
     * 按顶层 {@code and}/{@code or} 拆分作用域文本。
     * 规则：跳过字符串字面量；仅在括号深度 0 处拆分；{@code between} 吸收其后的第一个 {@code and} 不拆分。
     */
    private List<Seg> split(String body) {
        List<Seg> segs = new ArrayList<>();
        int n = body.length();
        int last = 0;
        String pendingOp = null;
        boolean betweenPending = false;
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '\'') {
                i = skipString(body, i);
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
            if (depth == 0 && isWordAt(body, i)) {
                if (matchKeyword(body, i, "between")) {
                    betweenPending = true;
                    i += 7;
                    continue;
                }
                if (matchKeyword(body, i, "and")) {
                    if (betweenPending) {
                        betweenPending = false;
                        i += 3;
                        continue;
                    }
                    segs.add(new Seg(pendingOp, body.substring(last, i)));
                    pendingOp = "and";
                    i += 3;
                    last = i;
                    continue;
                }
                if (matchKeyword(body, i, "or")) {
                    segs.add(new Seg(pendingOp, body.substring(last, i)));
                    pendingOp = "or";
                    i += 2;
                    last = i;
                    continue;
                }
            }
            i++;
        }
        segs.add(new Seg(pendingOp, body.substring(last)));
        return segs;
    }

    private static int skipString(String text, int start) {
        int n = text.length();
        int i = start + 1;
        while (i < n) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < n && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * 当前位置是否是一个词的起点（左边界）。
     */
    private static boolean isWordAt(String text, int i) {
        return i == 0 || !SqlScanner.isIdentPart(text.charAt(i - 1));
    }

    private static boolean matchKeyword(String text, int i, String kw) {
        int kl = kw.length();
        if (i + kl > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, i, kw, 0, kl)) {
            return false;
        }
        int after = i + kl;
        return after == text.length() || !SqlScanner.isIdentPart(text.charAt(after));
    }
}
