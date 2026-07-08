package io.github.luozhan.simplemybatis;

import io.github.luozhan.simplemybatis.spi.PreprocessContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预处理器翻译单元测试：断言“增强语法 → 标准 MyBatis 动态 SQL 文本”的转换结果。
 * 运行期行为（如全空省略 where、like 绑定值 %x%）由端到端测试覆盖。
 */
@DisplayName("增强语法预处理器")
class EnhancedSqlPreprocessorTest {

    private String process(String sql) {
        String out = new EnhancedSqlPreprocessor().process(sql, new PreprocessContext(null, null, sql));
        System.out.println("---------- 输入 ----------");
        System.out.println(sql);
        System.out.println("---------- 生成 ----------");
        System.out.println(out);
        System.out.println();
        return out;
    }

    @Test
    @DisplayName("#where 生成 <where> 与独立 <if> 块")
    void whereRegion_generatesWhereTagAndIfBlocks() {
        String out = process("select * from user\n# where id = :id\n#and name = :name");
        assertTrue(out.contains("<where>"), out);
        assertTrue(out.contains("</where>"), out);
        assertTrue(out.contains("<if test=\"id != null and id != ''\">id = #{id}</if>"), out);
        assertTrue(out.contains("<if test=\"name != null and name != ''\">and name = #{name}</if>"), out);
    }

    @Test
    @DisplayName("<where> 在尾部子句(group by)前闭合")
    void whereRegion_closesBeforeTrailingClause() {
        String out = process("select dept_id, count(*) from user\n#where status = :status\ngroup by dept_id");
        // <where> 必须在 group by 之前闭合
        int whereClose = out.indexOf("</where>");
        int groupBy = out.indexOf("group by dept_id");
        assertTrue(whereClose >= 0 && groupBy >= 0 && whereClose < groupBy, out);
    }

    @Test
    @DisplayName("内联多条件与分行写法结果一致")
    void inlineMultiCondition_equalsSplitForm() {
        String inline = process("select * from user\n#where id = :id and name = :name");
        String split = process("select * from user\n#where id = :id\n#and name = :name");
        assertEquals(split, inline);
    }

    @Test
    @DisplayName("like %:name% 用 <bind> 绑定 %name% 值")
    void like_usesBindWithWrappedValue() {
        String out = process("select * from user\n#where name like %:name%");
        assertTrue(out.contains("value=\"'%' + name + '%'\""), out);
        assertTrue(out.contains("like #{__name_like}"), out);
        assertTrue(out.contains("<if test=\"name != null and name != ''\">"), out);
    }

    @Test
    @DisplayName("like 前缀/后缀通配形式")
    void like_prefixAndSuffixForms() {
        assertTrue(process("#where name like :name%").contains("value=\"name + '%'\""));
        assertTrue(process("#where name like %:name").contains("value=\"'%' + name\""));
    }

    @Test
    @DisplayName("in 集合生成 <foreach> 并带空集合判空")
    void in_generatesForeachWithSizeGuard() {
        String out = process("select * from user\n#where status in (:statusList)");
        assertTrue(out.contains("statusList != null and statusList.size() > 0"), out);
        assertTrue(out.contains("<foreach collection=\"statusList\" item=\"__item_statusList\" open=\"(\" separator=\",\" close=\")\">#{__item_statusList}</foreach>"), out);
    }

    @Test
    @DisplayName("between 复合条件按两个参数整体判空")
    void between_omitsWholeConditionViaBothParams() {
        String out = process("select * from user\n#where created_at between :startDate and :endDate");
        assertTrue(out.contains("startDate != null and startDate != '' and endDate != null and endDate != ''"), out);
        assertTrue(out.contains("created_at between #{startDate} and #{endDate}"), out);
        // between 内部的 and 不应被拆成两个 <if>
        assertEquals(1, countOccurrences(out, "<if "), out);
    }

    @Test
    @DisplayName("静态锚点写法不生成 <where> 标签")
    void staticAnchor_noWhereTag() {
        String out = process("select * from user\nwhere deleted = 0\n#and id = :id");
        assertFalse(out.contains("<where>"), out);
        assertTrue(out.contains("where deleted = 0"), out);
        assertTrue(out.contains("<if test=\"id != null and id != ''\">and id = #{id}</if>"), out);
    }

    @Test
    @DisplayName("#set 逗号模式生成 <set> 标签")
    void setRegion_commaModeGeneratesSetTag() {
        String out = process("update user\n#set name = :name,\n#age = :age,\nwhere id = :id");
        assertTrue(out.contains("<set>"), out);
        assertTrue(out.contains("</set>"), out);
        assertTrue(out.contains("<if test=\"name != null and name != ''\">name = #{name},</if>"), out);
        assertTrue(out.contains("<if test=\"age != null and age != ''\">age = #{age},</if>"), out);
        assertTrue(out.contains("where id = #{id}"), out);
    }

    @Test
    @DisplayName("#(expr) 中 && 与 and 统一输出为 and")
    void customExpr_unifiesOperatorsToAnd() {
        String amp = process("select * from user\n#(id != null && id != '') id = :id");
        String kw = process("select * from user\n#(id != null and id != '') id = :id");
        assertTrue(amp.contains("<if test=\"id != null and id != ''\">id = #{id}</if>"), amp);
        assertEquals(kw, amp);
        assertFalse(amp.contains("&&"), amp);
        assertFalse(amp.contains("&amp;"), amp);
    }

    @Test
    @DisplayName(":param 扫描跳过 ::cast 与字符串字面量")
    void colonParam_skipsCastAndStringLiteral() {
        String out = process("select * from user where col::int = #{v} and note = ':literal' and id = :id");
        assertTrue(out.contains("col::int"), out);
        assertTrue(out.contains("':literal'"), out);
        assertTrue(out.contains("id = #{id}"), out);
        // 原生 #{v} 保持不变
        assertTrue(out.contains("#{v}"), out);
    }

    @Test
    @DisplayName("原生 SQL 原样透传")
    void nativeSql_passesThroughUnchanged() {
        String sql = "select * from user where id in #{id}";
        assertEquals(sql, process(sql));
    }

    @Test
    @DisplayName("未注册的 {:指令} 抛出友好错误")
    void unknownBracketDirective_throwsFriendlyError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> process("select * from user\n#where {:query}"));
        assertTrue(ex.getMessage().contains("未注册的行内指令"), ex.getMessage());
    }

    @Test
    @DisplayName("#(...) 括号不配平抛出带行号异常")
    void unbalancedExprParen_throwsWithLineNumber() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> process("select * from user\n#(id != null id = :id"));
        assertTrue(ex.getMessage().contains("括号不配平"), ex.getMessage());
    }

    @Test
    @DisplayName("单行内联 # 指令被正确识别")
    void inlineDirectives_onSingleLine() {
        // 单行注解形式：# 指令出现在行中间，应被正确识别
        String out = process("select * from t_user #where id = :id #and name like %:name%");
        assertTrue(out.contains("select * from t_user"), out);
        assertTrue(out.contains("<where>"), out);
        assertTrue(out.contains("<if test=\"id != null and id != ''\">id = #{id}</if>"), out);
        assertTrue(out.contains("like #{__name_like}"), out);
        assertTrue(out.contains("</where>"), out);
    }

    @Test
    @DisplayName("单行 #(expr) 输出不含裸 &")
    void customExpr_onSingleLineNoBareAmpersand() {
        // 单行 #(expr)：&& 必须被转为 and，输出不得含裸 &（否则 <script> XML 解析报错）
        String out = process("select * from t_user where 1=1 #(id != null && id > 0) and id = :id");
        assertTrue(out.contains("<if test=\"id != null and id &gt; 0\">and id = #{id}</if>"), out);
        assertFalse(out.contains("&&"), out);
    }

    @Test
    @DisplayName("单行 #set 后的 where 终止 set 区域")
    void setRegion_singleLineTrailingWhere() {
        // 单行 #set...where：where 应终止 set 区域，不被吞入 <set>
        String out = process("update t_user #set name = :name, #age = :age, where id = :id");
        assertTrue(out.contains("<set>"), out);
        assertTrue(out.contains("</set>"), out);
        assertTrue(out.contains("<if test=\"name != null and name != ''\">name = #{name},</if>"), out);
        assertTrue(out.contains("<if test=\"age != null and age != ''\">age = #{age},</if>"), out);
        // where id = :id 在 </set> 之后
        assertTrue(out.indexOf("</set>") < out.indexOf("where id = #{id}"), out);
    }

    @Test
    @DisplayName("条件体中的 < 运算符被 XML 转义")
    void lessThanOperator_xmlEscapedInContent() {
        // <= 的 < 在 <if> 内容中必须转义，否则 <script> XML 解析报错
        String out = process("select * from t_user\n#where created_at <= :endDate");
        assertTrue(out.contains("created_at &lt;= #{endDate}"), out);
        assertFalse(out.contains("<= #{endDate}"), out);
    }

    @Test
    @DisplayName("解析异常提示 Mapper 方法名（有 sourceId 时）")
    void parseError_includesMapperMethodName() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EnhancedSqlPreprocessor().process(
                        "select * from user\n#(id != null id = :id",
                        new PreprocessContext(null, null, "x", "findUsers")));
        assertTrue(ex.getMessage().contains("findUsers"), ex.getMessage());
        assertTrue(ex.getMessage().contains("括号不配平"), ex.getMessage());
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
