package io.github.luozhan.simplemybatis;

import io.github.luozhan.simplemybatis.spi.DirectiveRegistry;
import io.github.luozhan.simplemybatis.spi.InlineDirective;
import io.github.luozhan.simplemybatis.spi.PreprocessContext;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * 增强语法 LanguageDriver：MyBatis 双入口（XML {@code <select|insert|update|delete>} 与
 * {@code @Select/@Insert/@Update/@Delete} 注解）的统一接管点。
 *
 * <p>工作方式：在启动期取出原始 SQL 文本 → {@link EnhancedSqlPreprocessor} 翻译为标准 MyBatis 动态 SQL
 * → 包上 {@code <script>} 交给原生 {@link XMLLanguageDriver} 构建 {@link SqlSource}。运行期完全走原生 MyBatis。
 *
 * <p>逃生舱：需要跳过增强预处理时，用标准 {@code lang="org.apache.ibatis.scripting.xmltags.XMLLanguageDriver"}
 * 或 {@code @Lang(XMLLanguageDriver.class)} —— MyBatis 会直接选用原生 driver，根本不进入本类。
 *
 * <p>扩展：通过 {@link #registerDirective(InlineDirective)} 或 classpath 上的 {@code ServiceLoader}
 * 注册 {@link InlineDirective}，即可支持 {@code {:name ...}} 形式的扩展记号（如未来的 {@code {:query}}）。
 *
 * @author luozhan
 */
public class EnhancedLanguageDriver extends XMLLanguageDriver {

    private final DirectiveRegistry directiveRegistry;
    private final EnhancedSqlPreprocessor preprocessor;

    public EnhancedLanguageDriver() {
        this.directiveRegistry = new DirectiveRegistry().loadFromServiceLoader();
        this.preprocessor = new EnhancedSqlPreprocessor(directiveRegistry);
    }

    /**
     * 编程式注册扩展行内指令。
     */
    public EnhancedLanguageDriver registerDirective(InlineDirective directive) {
        directiveRegistry.register(directive);
        return this;
    }

    public DirectiveRegistry getDirectiveRegistry() {
        return directiveRegistry;
    }

    @Override
    public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
        String original = script.getNode().getTextContent();
        // XML 入口：<select|update|...> 的 id 即 Mapper 方法名，用于错误定位
        String sourceId = script.getStringAttribute("id");
        String processed = preprocessor.process(original,
                new PreprocessContext(configuration, parameterType, original, sourceId));
        return build(configuration, processed, parameterType);
    }

    @Override
    public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
        String processed = preprocessor.process(script,
                new PreprocessContext(configuration, parameterType, script));
        return build(configuration, processed, parameterType);
    }

    /**
     * 把预处理结果包成 {@code <script>} 节点后交给原生 XNode 实现构建 SqlSource。
     * 这里显式解析为 XNode 并调用 {@code super} 的 XNode 重载，避免调用 {@code super} 的 String 重载时
     * 因 MyBatis 内部多态回调本类 XNode 重载而造成无限递归。
     */
    private SqlSource build(Configuration configuration, String processed, Class<?> parameterType) {
        String wrapped = wrap(processed);
        XPathParser parser = new XPathParser(wrapped, false,
                configuration.getVariables(), new XMLMapperEntityResolver());
        return super.createSqlSource(configuration, parser.evalNode("/script"), parameterType);
    }

    /**
     * 包上 {@code <script>} 根节点，使生成的 {@code <if>}/{@code <where>} 等被原生解析。
     */
    private static String wrap(String processed) {
        String trimmed = processed == null ? "" : processed.trim();
        if (trimmed.startsWith("<script>")) {
            return processed;
        }
        return "<script>" + (processed == null ? "" : processed) + "</script>";
    }
}
