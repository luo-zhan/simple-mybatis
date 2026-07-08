package io.github.luozhan.simplemybatis;

import io.github.luozhan.simplemybatis.spi.PreprocessContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复杂 SQL 场景测试：联表、子查询、分组/聚合、嵌套括号、多条件混合、运算符转义、union 终止等，
 * 断言增强语法生成的标准 MyBatis 动态 SQL 结构正确。
 */
@DisplayName("增强语法·复杂 SQL 场景")
class EnhancedComplexSqlTest {

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
    @DisplayName("#(expr) body 内 in 糖展开为 <foreach>")
    void customExprSupportsIn() {
        String out = process(
                "select * from t_user\n"
                        + "where 1=1\n"
                        + "#(type != 'a' && type != 'b') and id in (:ids)");
        assertTrue(out.contains("<if test=\"type != 'a' and type != 'b'\">and id in <foreach collection=\"ids\" item=\"__item_ids\" open=\"(\" separator=\",\" close=\")\">#{__item_ids}</foreach></if>"), out);
        assertFalse(out.contains("in (#{ids})"), out);
    }

    @Test
    @DisplayName("#(expr) body 内 like 糖用 <bind> 展开")
    void customExprSupportsLike() {
        String out = process(
                "select * from t_user\n"
                        + "where 1=1\n"
                        + "#(type == 'a') and name like %:name%");
        assertTrue(out.contains("<if test=\"type == 'a'\">and <bind name=\"__name_like\" value=\"'%' + name + '%'\"/>name like #{__name_like}</if>"), out);
        assertFalse(out.contains("like %#{name}%"), out);
    }

    @Test
    @DisplayName("联表 + 子查询 + group by/order by：<where> 在 group by 前闭合，子查询内 where 不受影响")
    void joinSubqueryGroupOrder() {
        String out = process(
                "select o.status, count(*) cnt\n"
                        + "from orders o join t_user u on o.user_id = u.id\n"
                        + "#where o.user_id in (select id from t_user where deleted = 0)\n"
                        + "#and o.amount >= :minAmount\n"
                        + "group by o.status\n"
                        + "order by cnt desc");
        assertTrue(out.contains("<where>"), out);
        assertTrue(out.contains("</where>"), out);
        // 子查询整体作为静态条件保留（内部 where 不被当作区域终止）
        assertTrue(out.contains("o.user_id in (select id from t_user where deleted = 0)"), out);
        // >= 被 XML 转义
        assertTrue(out.contains("<if test=\"minAmount != null and minAmount != ''\">and o.amount &gt;= #{minAmount}</if>"), out);
        // <where> 必须在 group by 之前闭合，且 order by 在最后
        assertTrue(out.indexOf("</where>") < out.indexOf("group by o.status"), out);
        assertTrue(out.contains("order by cnt desc"), out);
    }

    @Test
    @DisplayName("嵌套括号 OR 分组：括号内 or 不被拆分为多个 <if>")
    void nestedParenOrGroup() {
        String out = process(
                "select * from t_user\n"
                        + "#where (status = :status or type = :type)\n"
                        + "#and name = :name");
        assertTrue(out.contains("<if test=\"status != null and status != '' and type != null and type != ''\">(status = #{status} or type = #{type})</if>"), out);
        assertTrue(out.contains("<if test=\"name != null and name != ''\">and name = #{name}</if>"), out);
    }

    @Test
    @DisplayName("比较运算符 >= / <= 在条件体内被 XML 转义")
    void comparisonOperatorsEscaped() {
        String out = process(
                "select * from t_user\n"
                        + "#where age >= :minAge\n"
                        + "#and age <= :maxAge");
        assertTrue(out.contains("age &gt;= #{minAge}"), out);
        assertTrue(out.contains("age &lt;= #{maxAge}"), out);
        assertFalse(out.contains("<= #{maxAge}"), out);
    }

    @Test
    @DisplayName("单行 union：顶层 union 终止 #where 区域")
    void unionTerminatesWhere() {
        String out = process("select * from a #where x = :x union select * from b");
        assertTrue(out.contains("<where>"), out);
        assertTrue(out.contains("</where>"), out);
        assertTrue(out.contains("union select * from b"), out);
        assertTrue(out.indexOf("</where>") < out.indexOf("union select * from b"), out);
    }

    @Test
    @DisplayName("#(expr) 模拟 choose：多个互斥表达式分支")
    void chooseEmulationWithExpr() {
        String out = process(
                "select * from t_user\n"
                        + "where 1=1\n"
                        + "#(type == 'a') and name = :name\n"
                        + "#(type == 'b') and status = :status\n"
                        + "#(type != 'a' && type != 'b') and id = :id");
        assertTrue(out.contains("<if test=\"type == 'a'\">and name = #{name}</if>"), out);
        assertTrue(out.contains("<if test=\"type == 'b'\">and status = #{status}</if>"), out);
        // && 统一转 and，!= 保留
        assertTrue(out.contains("<if test=\"type != 'a' and type != 'b'\">and id = #{id}</if>"), out);
        assertFalse(out.contains("&&"), out);
    }

    @Test
    @DisplayName("静态锚点 + in + between + like 混合，不生成 <where>")
    void mixedAnchorInBetweenLike() {
        String out = process(
                "select * from t_user\n"
                        + "where deleted = 0\n"
                        + "#and status in (:statusList)\n"
                        + "#and created_at between :start and :end\n"
                        + "#and name like %:name%");
        assertFalse(out.contains("<where>"), out);
        assertTrue(out.contains("where deleted = 0"), out);
        assertTrue(out.contains("and status in <foreach collection=\"statusList\" item=\"__item_statusList\" open=\"(\" separator=\",\" close=\")\">#{__item_statusList}</foreach>"), out);
        assertTrue(out.contains("<if test=\"start != null and start != '' and end != null and end != ''\">and created_at between #{start} and #{end}</if>"), out);
        assertTrue(out.contains("<bind name=\"__name_like\" value=\"'%' + name + '%'\"/>and name like #{__name_like}"), out);
    }

    @Test
    @DisplayName("多个 #where 分行 + 尾部 having/order by 全链路")
    void fullChainWithHaving() {
        String out = process(
                "select dept_id, count(*) cnt from t_user\n"
                        + "#where status = :status\n"
                        + "#and name like %:name%\n"
                        + "group by dept_id\n"
                        + "having count(*) > :minCnt\n"
                        + "order by cnt desc");
        assertTrue(out.contains("<where>"), out);
        assertTrue(out.indexOf("</where>") < out.indexOf("group by dept_id"), out);
        // having 是静态行，:minCnt 转换、原生 > 在元素内容中合法
        assertTrue(out.contains("having count(*) > #{minCnt}"), out);
        assertTrue(out.contains("order by cnt desc"), out);
    }
}
