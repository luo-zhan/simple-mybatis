package io.github.luozhan.simplemybatis;

import io.github.luozhan.simplemybatis.directive.HashConditionParser;
import io.github.luozhan.simplemybatis.scan.SqlScanner;
import io.github.luozhan.simplemybatis.spi.DirectiveRegistry;
import io.github.luozhan.simplemybatis.spi.InlineDirective;
import io.github.luozhan.simplemybatis.spi.PreprocessContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强语法预处理器：把增强语法文本翻译为标准 MyBatis 动态 SQL（{@code <if>}/{@code <where>}/{@code <set>}
 * /{@code <foreach>}/{@code <bind>} + {@code #{}}）。不做 SQL 结构化解析，采用<b>行级状态机</b>：
 *
 * <ul>
 *   <li>{@code #where} 开启 {@code <where>} 区域，其后连续的 {@code #} 行归入区域，遇首个非 {@code #} 行闭合；</li>
 *   <li>{@code #set} 开启 {@code <set>} 区域（逗号模式）；</li>
 *   <li>{@code NORMAL} 状态下的 {@code #and}/{@code #or}/{@code #}/{@code #(expr)} 直接渲染为 {@code <if>}
 *       （静态锚点写法）；</li>
 *   <li>静态行原样保留，仅做 {@code :param → #{param}} 转换；</li>
 *   <li>行首 {@code #{} 为原生占位符，不是指令。</li>
 * </ul>
 */
public class EnhancedSqlPreprocessor {

    private enum State {NORMAL, WHERE, SET}

    private enum Kind {STATIC, WHERE_START, SET_START, COND, EXPR}

    private final DirectiveRegistry registry;

    public EnhancedSqlPreprocessor() {
        this(new DirectiveRegistry());
    }

    public EnhancedSqlPreprocessor(DirectiveRegistry registry) {
        this.registry = registry != null ? registry : new DirectiveRegistry();
    }

    private static final class Directive {
        Kind kind;
        String connector = "";  // COND: "" / "and" / "or"
        String expr = "";       // EXPR
        String body = "";       // 指令后的作用域文本
    }

    /**
     * 处理增强语法文本，返回可放入 {@code <script>} 的标准动态 SQL 内容。
     */
    public String process(String sql, PreprocessContext ctx) {
        if (sql == null || sql.isEmpty()) {
            return sql == null ? "" : sql;
        }
        String sourceId = ctx != null ? ctx.getSourceId() : null;
        HashConditionParser parser = new HashConditionParser(sourceId);
        String[] lines = sql.split("\n", -1);
        StringBuilder out = new StringBuilder(sql.length() + 64);
        State state = State.NORMAL;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];

            // 空行：原样输出，不改变状态
            if (line.trim().isEmpty()) {
                appendLine(out, line);
                continue;
            }

            // 把一行切成有序段（静态段 / # 指令段）。# 作用域到「下一个 # / 顶层子句关键字 / 行尾」为止，
            // 因此 "#age = :age, where id = :id" 里的 where 会终止 #age 指令段，成为独立静态段。
            for (Seg seg : tokenizeLine(line)) {
                if (seg.directive) {
                    state = emitDirective(out, state, parser, seg.text.trim(), li + 1, sourceId);
                } else {
                    state = emitStatic(out, state, seg.text);
                }
            }
        }

        if (state == State.WHERE) {
            appendLine(out, "</where>");
        } else if (state == State.SET) {
            appendLine(out, "</set>");
        }
        return expandBracketDirectives(out.toString(), ctx);
    }

    /**
     * 输出静态段：若在区域内先闭合区域，再做 :param 转换后输出。
     */
    private State emitStatic(StringBuilder out, State state, String text) {
        if (state != State.NORMAL) {
            appendLine(out, state == State.WHERE ? "</where>" : "</set>");
            state = State.NORMAL;
        }
        appendLine(out, SqlScanner.convertColonParams(text).getText());
        return state;
    }

    /**
     * 处理一个 # 指令段，返回新状态。
     */
    private State emitDirective(StringBuilder out, State state, HashConditionParser parser,
                                String seg, int lineNo, String sourceId) {
        Directive d = parseDirective(seg, lineNo, sourceId);
        if (d.kind == Kind.STATIC) {
            return emitStatic(out, state, seg);
        }
        switch (d.kind) {
            case WHERE_START:
                if (state == State.SET) {
                    appendLine(out, "</set>");
                }
                if (state != State.WHERE) {
                    appendLine(out, "<where>");
                    state = State.WHERE;
                    appendLine(out, parser.renderScope("", d.body));
                } else {
                    // 区域内重复 #where：按 #and 处理，避免条件粘连
                    appendLine(out, parser.renderScope("and", d.body));
                }
                break;
            case SET_START:
                if (state == State.WHERE) {
                    appendLine(out, "</where>");
                }
                if (state != State.SET) {
                    appendLine(out, "<set>");
                    state = State.SET;
                }
                appendLine(out, parser.renderScope("", d.body));
                break;
            case EXPR:
                appendLine(out, parser.renderCustomExpr(d.expr, d.body));
                break;
            case COND:
            default:
                appendLine(out, parser.renderScope(d.connector, d.body));
                break;
        }
        return state;
    }

    /**
     * 词法段：静态段或 # 指令段。
     */
    private static final class Seg {
        final boolean directive;
        final String text;

        Seg(boolean directive, String text) {
            this.directive = directive;
            this.text = text;
        }
    }

    /**
     * 终止 # 作用域的顶层 SQL 子句关键字。
     */
    private static final String[] CLAUSE_KEYWORDS =
            {"where", "group", "order", "having", "limit", "offset", "union", "fetch"};

    /**
     * 把一行切成有序的静态段 / # 指令段。规则：
     * <ul>
     *   <li>{@code #}（非 {@code #{}}、不在字符串字面量内、括号深度 0）开启一个指令段；</li>
     *   <li>指令段延伸到「下一个 {@code #} / 顶层 SQL 子句关键字 / 行尾」；</li>
     *   <li>子句关键字仅在括号深度 0 才终止（子查询内的 where 不终止）；</li>
     *   <li>指令自身的 marker（where/set/and/or 或 {@code (expr)}）会被跳过，不当作终止关键字。</li>
     * </ul>
     */
    private List<Seg> tokenizeLine(String line) {
        List<Seg> segs = new ArrayList<>();
        int n = line.length();
        int i = 0;
        int start = 0;
        boolean directive = false;
        int depth = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\'') {
                i = skipStringLiteral(line, i);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth >= 0 && c == '#' && !(i + 1 < n && line.charAt(i + 1) == '{')) {
                addSeg(segs, directive, line.substring(start, i));
                start = i;
                directive = true;
                i = skipMarker(line, i);
                continue;
            }
            if (directive && depth == 0) {
                int kwLen = clauseKeywordLengthAt(line, i);
                if (kwLen > 0) {
                    addSeg(segs, true, line.substring(start, i));
                    start = i;
                    directive = false;
                    i += kwLen;
                    continue;
                }
            }
            i++;
        }
        addSeg(segs, directive, line.substring(start));
        return segs;
    }

    /**
     * 非空白段才加入（静态与指令段均如此，避免空白段干扰区域状态）。
     */
    private static void addSeg(List<Seg> segs, boolean directive, String text) {
        if (!text.trim().isEmpty()) {
            segs.add(new Seg(directive, text));
        }
    }

    /**
     * 从 # 处跳过 marker：# + 可选空白 + (expr) 或首个词，返回 body 起点下标。
     */
    private static int skipMarker(String line, int hash) {
        int n = line.length();
        int j = hash + 1;
        while (j < n && Character.isWhitespace(line.charAt(j))) {
            j++;
        }
        if (j < n && line.charAt(j) == '(') {
            int end = matchingParen(line, j);
            return end < 0 ? n : end + 1;
        }
        while (j < n && SqlScanner.isIdentPart(line.charAt(j))) {
            j++;
        }
        return j;
    }

    /**
     * 若下标 i 处是顶层子句关键字（词边界），返回其长度；否则 0。
     */
    private static int clauseKeywordLengthAt(String line, int i) {
        if (i > 0 && SqlScanner.isIdentPart(line.charAt(i - 1))) {
            return 0;
        }
        for (String kw : CLAUSE_KEYWORDS) {
            int kl = kw.length();
            if (i + kl <= line.length()
                    && line.regionMatches(true, i, kw, 0, kl)
                    && (i + kl == line.length() || !SqlScanner.isIdentPart(line.charAt(i + kl)))) {
                return kl;
            }
        }
        return 0;
    }

    private static int skipStringLiteral(String line, int start) {
        int n = line.length();
        int i = start + 1;
        while (i < n) {
            if (line.charAt(i) == '\'') {
                if (i + 1 < n && line.charAt(i + 1) == '\'') {
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
     * 后处理：展开 {@code {:name ...}} 扩展记号，派发给注册的 {@link InlineDirective}。
     * 未注册对应指令时抛出友好错误。
     */
    private String expandBracketDirectives(String text, PreprocessContext ctx) {
        if (text.indexOf("{:") < 0) {
            return text;
        }
        String sourceId = ctx != null ? ctx.getSourceId() : null;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (i + 1 < n && text.charAt(i) == '{' && text.charAt(i + 1) == ':') {
                int close = matchingBrace(text, i);
                if (close < 0) {
                    throw new IllegalStateException("增强语法解析失败" + sourceHint(sourceId) + "：{:...} 大括号不配平：" + text.substring(i));
                }
                String inner = text.substring(i + 2, close).trim();
                String name = leadingIdentifier(inner);
                if (name.isEmpty()) {
                    throw new IllegalStateException("增强语法解析失败" + sourceHint(sourceId) + "：{:...} 缺少指令名：" + text.substring(i, close + 1));
                }
                InlineDirective dir = registry.findInline(name);
                if (dir == null) {
                    throw new IllegalStateException("增强语法解析失败" + sourceHint(sourceId) + "：未注册的行内指令 {:" + name + " ...}（需通过 DirectiveRegistry 注册）");
                }
                out.append(dir.expand(name, inner, ctx));
                i = close + 1;
                continue;
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * 错误信息的来源提示：有 Mapper 方法名（如 XML 语句 id）时附上；注解入口无 id 时为空。
     */
    private static String sourceHint(String sourceId) {
        return (sourceId != null && !sourceId.isEmpty()) ? "（Mapper 方法: " + sourceId + "）" : "";
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String leadingIdentifier(String s) {
        int i = 0;
        while (i < s.length() && SqlScanner.isIdentPart(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    private static void appendLine(StringBuilder out, String content) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(content);
    }

    /**
     * 解析一行（已 trim）的指令类型与作用域文本。
     */
    private Directive parseDirective(String s, int lineNo, String sourceId) {
        Directive d = new Directive();
        // 非指令：不以 # 开头，或以 #{ 开头（原生占位符）
        if (s.charAt(0) != '#' || (s.length() > 1 && s.charAt(1) == '{')) {
            d.kind = Kind.STATIC;
            return d;
        }
        String rest = ltrim(s.substring(1));

        // #(expr) 自定义表达式
        if (rest.startsWith("(")) {
            int end = matchingParen(rest, 0);
            if (end < 0) {
                throw new IllegalStateException(
                        "增强语法解析失败" + sourceHint(sourceId) + "：第 " + lineNo + " 行 #(...) 括号不配平：" + s);
            }
            d.kind = Kind.EXPR;
            d.expr = rest.substring(1, end);
            d.body = rest.substring(end + 1).trim();
            return d;
        }

        if (matchKeyword(rest, "where")) {
            d.kind = Kind.WHERE_START;
            d.body = rest.substring(5).trim();
            return d;
        }
        if (matchKeyword(rest, "set")) {
            d.kind = Kind.SET_START;
            d.body = rest.substring(3).trim();
            return d;
        }
        if (matchKeyword(rest, "and")) {
            d.kind = Kind.COND;
            d.connector = "and";
            d.body = rest.substring(3).trim();
            return d;
        }
        if (matchKeyword(rest, "or")) {
            d.kind = Kind.COND;
            d.connector = "or";
            d.body = rest.substring(2).trim();
            return d;
        }
        // 裸 #
        d.kind = Kind.COND;
        d.connector = "";
        d.body = rest.trim();
        return d;
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /**
     * rest 是否以关键字 kw 开头且构成词（其后为空白/结束/'('）。
     */
    private static boolean matchKeyword(String rest, String kw) {
        int kl = kw.length();
        if (rest.length() < kl) {
            return false;
        }
        if (!rest.regionMatches(true, 0, kw, 0, kl)) {
            return false;
        }
        if (rest.length() == kl) {
            return true;
        }
        char after = rest.charAt(kl);
        return Character.isWhitespace(after) || after == '(';
    }

    /**
     * 从下标 open（指向 '('）找匹配的 ')'，跳过字符串字面量；返回 ')' 下标或 -1。
     */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        int i = open;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < n && s.charAt(i + 1) == '\'') {
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
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }
}
