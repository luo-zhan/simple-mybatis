package io.github.luozhan.simplemybatis.spi;

import org.apache.ibatis.session.Configuration;

/**
 * 预处理上下文：在把增强语法翻译为标准 MyBatis 动态 SQL 期间，传递给各指令的信息载体。
 *
 * <p>这是增强语法的扩展点之一：自定义指令（如未来的 {@code {:query}}、其他框架接入）通过本上下文
 * 获取 {@link Configuration}、参数类型等元数据，而无需与核心预处理器耦合。
 */
public class PreprocessContext {

    /**
     * MyBatis 全局配置；注解入口或早期阶段可能为 {@code null}。
     */
    private final Configuration configuration;

    /**
     * 当前语句的参数类型；可能为 {@code null}。
     */
    private final Class<?> parameterType;

    /**
     * 原始 SQL 模板文本。
     */
    private final String originalSql;

    /**
     * 来源标识（如 XML 语句的 id / Mapper 方法名），用于错误定位；注解入口可能为 {@code null}。
     */
    private final String sourceId;

    public PreprocessContext(Configuration configuration, Class<?> parameterType, String originalSql) {
        this(configuration, parameterType, originalSql, null);
    }

    public PreprocessContext(Configuration configuration, Class<?> parameterType,
                             String originalSql, String sourceId) {
        this.configuration = configuration;
        this.parameterType = parameterType;
        this.originalSql = originalSql;
        this.sourceId = sourceId;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public Class<?> getParameterType() {
        return parameterType;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    /**
     * 来源标识（XML 语句 id / Mapper 方法名）；可能为 {@code null}。
     */
    public String getSourceId() {
        return sourceId;
    }
}
