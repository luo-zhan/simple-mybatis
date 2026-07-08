package io.github.luozhan.simplemybatis.spi;

/**
 * 指令扩展点标记接口。增强语法的所有可扩展指令的共同父接口。
 *
 * <p>目前提供 {@link InlineDirective}（行内指令）作为主要扩展面；后续可新增行级指令等。
 * 内置语法（{@code #where}/{@code #set}/{@code :param}/like/in）由核心预处理器直接处理，
 * 扩展指令（如未来的 {@code {:query}}、其他框架接入）通过本 SPI 接入，核心无需改动。
 */
public interface Directive {

    /**
     * 派发优先级，数值越小越先匹配。默认 100。
     */
    default int order() {
        return 100;
    }
}
