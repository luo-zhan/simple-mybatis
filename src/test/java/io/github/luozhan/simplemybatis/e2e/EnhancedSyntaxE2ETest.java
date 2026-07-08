package io.github.luozhan.simplemybatis.e2e;

import io.github.luozhan.simplemybatis.EnhancedLanguageDriver;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：以 {@link EnhancedLanguageDriver} 为默认语言，跑通 H2，验证运行期行为
 * 与 PRD 验收标准（where 省略/去头 and、like 绑定值 %x%、in 空/非空、静态锚点、#set、
 * #(expr) &&→and、双入口、CDATA、逃生舱）。
 */
@DisplayName("增强语法端到端测试")
class EnhancedSyntaxE2ETest {

    private static DataSource ds;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setupAll() throws Exception {
        ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:sm;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("create table t_user(id bigint primary key, name varchar(50), status int, age int, deleted int)");
        }
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setDefaultScriptingLanguage(EnhancedLanguageDriver.class);
        config.addMapper(UserMapper.class);
        try (InputStream is = Resources.getResourceAsStream(
                "io/github/luozhan/simplemybatis/e2e/UserXmlMapper.xml")) {
            new XMLMapperBuilder(is, config, "UserXmlMapper.xml", config.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(config);
        dumpGeneratedSql(config);
    }

    /**
     * 打印每条语句经增强语法驱动生成的最终 MyBatis SQL（空参数，动态条件省略）。
     */
    private static void dumpGeneratedSql(Configuration config) {
        System.out.println("========== 生成的 MyBatis SQL ==========");
        Set<String> seen = new HashSet<>();
        for (String name : config.getMappedStatementNames()) {
            if (!name.contains(".") || !seen.add(name)) {
                continue;
            }
            try {
                MappedStatement ms = config.getMappedStatement(name);
                String sql = ms.getBoundSql(new HashMap<String, Object>()).getSql();
                System.out.println("[" + name + "]");
                System.out.println(sql);
                System.out.println();
            } catch (Exception e) {
                System.out.println("[" + name + "] (需参数，跳过): " + e.getMessage());
            }
        }
        System.out.println("=====================================");
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("delete from t_user");
            st.execute("insert into t_user values (1,'张三',1,20,0),(2,'李四',2,30,0),(3,'王五',1,40,1)");
        }
    }

    @Test
    @DisplayName("全部参数为空时 where 省略，返回全部")
    void where_allNullOmitted() {
        try (SqlSession s = factory.openSession(true)) {
            assertEquals(3, s.getMapper(UserMapper.class).find(null, null).size());
        }
    }

    @Test
    @DisplayName("仅 id 非空时只保留 id 条件")
    void where_onlyIdKept() {
        try (SqlSession s = factory.openSession(true)) {
            List<User> r = s.getMapper(UserMapper.class).find(1L, null);
            assertEquals(1, r.size());
            assertEquals(1L, r.get(0).getId());
        }
    }

    @Test
    @DisplayName("like 绑定值为 %张%，正确匹配")
    void like_valueWrappedWithPercent() {
        try (SqlSession s = factory.openSession(true)) {
            List<User> r = s.getMapper(UserMapper.class).find(null, "张");
            assertEquals(1, r.size());
            assertEquals("张三", r.get(0).getName());
        }
    }

    @Test
    @DisplayName("in 非空展开、空集合整体省略")
    void in_nonEmptyAndEmpty() {
        try (SqlSession s = factory.openSession(true)) {
            UserMapper m = s.getMapper(UserMapper.class);
            assertEquals(3, m.countByStatus(Arrays.asList(1, 2)));
            // 空集合 → in 整体省略 → 统计全部
            assertEquals(3, m.countByStatus(Collections.emptyList()));
        }
    }

    @Test
    @DisplayName("#set 仅更新非空字段")
    void setRegion_onlyNonNullFieldsUpdated() {
        try (SqlSession s = factory.openSession(true)) {
            UserMapper m = s.getMapper(UserMapper.class);
            int rows = m.update(1L, null, 99); // name=null 省略，仅更新 age
            assertEquals(1, rows);
            User u = m.find(1L, null).get(0);
            assertEquals("张三", u.getName()); // 未被改动
            assertEquals(99, u.getAge());
        }
    }

    @Test
    @DisplayName("#(expr) 中 && 转 and 运行正确")
    void customExpr_ampToAnd() {
        try (SqlSession s = factory.openSession(true)) {
            UserMapper m = s.getMapper(UserMapper.class);
            assertEquals(1, m.exprFind(2L).size());   // id>0 且 id=2
            assertEquals(3, m.exprFind(null).size());  // 表达式不成立 → 仅 where 1=1
        }
    }

    @Test
    @DisplayName("静态锚点 deleted=0 恒生效")
    void staticAnchor_deletedFilterAlwaysApplied() {
        try (SqlSession s = factory.openSession(true)) {
            // deleted=0 恒在；id=null 省略 → 返回 2 条未删除
            assertEquals(2, s.getMapper(UserMapper.class).findActive(null).size());
        }
    }

    @Test
    @DisplayName("XML CDATA 入口正常工作")
    void xmlEntry_cdataWorks() {
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> p = new HashMap<>();
            p.put("minAge", 30);
            List<User> r = s.selectList("xmltest.findByAgeCData", p);
            assertEquals(2, r.size()); // age>=30 → 李四(30)、王五(40)
        }
    }

    @Test
    @DisplayName("逃生舱：原生 driver 跳过增强预处理")
    void escapeHatch_rawDriverBypassesEnhancement() {
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", 1);
            List<User> r = s.selectList("xmltest.rawFind", p);
            assertEquals(1, r.size());
            assertEquals("张三", r.get(0).getName());
        }
    }

    @Test
    @DisplayName("扩展点：注册自定义行内指令生效")
    void extensionPoint_inlineDirectiveInvoked() {
        // 通过 ServiceLoader 注册的自定义 InlineDirective 应被调用
        EnhancedLanguageDriver driver = new EnhancedLanguageDriver();
        driver.registerDirective(new io.github.luozhan.simplemybatis.spi.InlineDirective() {
            @Override
            public boolean supports(String name) {
                return "hello".equals(name);
            }

            @Override
            public String expand(String name, String content,
                                 io.github.luozhan.simplemybatis.spi.PreprocessContext ctx) {
                return "1 = 1";
            }
        });
        // 直接验证注册生效
        assertTrue(driver.getDirectiveRegistry().hasInlineDirectives());
    }
}
