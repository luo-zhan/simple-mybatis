package io.github.luozhan.simplemybatis.spi;

/**
 * 行内指令扩展点：识别并展开形如 <code>{:name ...}</code> 的行内扩展记号。
 *
 * <p>{@code {:...}} 记号被保留给扩展指令使用，内置语法不占用。典型用例是未来的 {@code {:query}}
 * ——把一个 QueryEntity 参数展开为一组动态 {@code <if>} 条件；其他框架的增强语法也遵循同一协议。
 *
 * <p>实现类通过 {@link DirectiveRegistry} 注册（编程式或 {@code ServiceLoader} 自动发现），
 * 核心预处理器在遇到 {@code {:...}} 记号时按 {@link Directive#order()} 顺序询问各实现。
 */
public interface InlineDirective extends Directive {

    /**
     * 是否处理该记号名。
     *
     * @param name {@code {:name ...}} 中冒号后的首个标识符（如 {@code query}）
     */
    boolean supports(String name);

    /**
     * 把整个记号展开为合法的 MyBatis 动态 SQL 片段（如 {@code <if>} 组合）。
     *
     * @param name    记号名（冒号后的首个标识符）
     * @param content {@code {:...}} 大括号内的完整内容（已去除首尾空白，含 name 及其后的参数）
     * @param ctx     预处理上下文，可据此获取 {@link PreprocessContext#getParameterType()} 等元数据
     * @return 展开后的标准 MyBatis 动态 SQL 片段；不得引入运行时执行逻辑
     */
    String expand(String name, String content, PreprocessContext ctx);
}
