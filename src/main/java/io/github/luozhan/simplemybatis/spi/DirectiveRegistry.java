package io.github.luozhan.simplemybatis.spi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 指令注册表：持有有序的 {@link InlineDirective} 列表，供核心预处理器派发。
 *
 * <p>三种注册途径：
 * <ol>
 *   <li>编程式：{@link #register(InlineDirective)}；</li>
 *   <li>{@code ServiceLoader} 自动发现：{@link #loadFromServiceLoader()}，
 *       第三方 jar 在 {@code META-INF/services/io.github.luozhan.simplemybatis.spi.InlineDirective}
 *       中声明实现即可接入；</li>
 *   <li>内置默认集：由核心在初始化时注册（首期内置语法不走本注册表，此处主要面向扩展）。</li>
 * </ol>
 */
public class DirectiveRegistry {

    private final List<InlineDirective> inlineDirectives = new ArrayList<>();

    /**
     * 注册一个行内指令，注册后按 {@link Directive#order()} 重新排序。
     */
    public DirectiveRegistry register(InlineDirective directive) {
        if (directive != null) {
            inlineDirectives.add(directive);
            inlineDirectives.sort(Comparator.comparingInt(Directive::order));
        }
        return this;
    }

    /**
     * 通过 {@code ServiceLoader} 自动发现并注册 classpath 上的所有 {@link InlineDirective} 实现。
     */
    public DirectiveRegistry loadFromServiceLoader() {
        for (InlineDirective directive : ServiceLoader.load(InlineDirective.class)) {
            register(directive);
        }
        return this;
    }

    /**
     * 查找第一个支持该记号名的行内指令。
     *
     * @return 匹配的指令；无匹配时返回 {@code null}
     */
    public InlineDirective findInline(String name) {
        for (InlineDirective directive : inlineDirectives) {
            if (directive.supports(name)) {
                return directive;
            }
        }
        return null;
    }

    /**
     * 是否注册了任何行内指令。
     */
    public boolean hasInlineDirectives() {
        return !inlineDirectives.isEmpty();
    }
}
