# simple-mybatis

> **告别 `<if>`/`<where>`/`<foreach>` 标签地狱——用最自然的写法，写最干净的动态 SQL。**

一个 MyBatis `LanguageDriver` 增强：在**启动期**把简洁语法翻译为标准 MyBatis 动态 SQL，运行期**零额外开销**。仅依赖 MyBatis 核心，无 jsqlparser 等第三方库，即插即用。

---

## 先看效果



###  xml 对比
两边等价
<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis** 🚀

```xml
<select id="findUsers" resultType="User"><![CDATA[
    select * from t_user
    # where id = :id
    # and name like %:name%
    # and age > :minAge
    # and status in (:statusList)
    order by id
]]></select>
```
1. 代码少60%，可读性更高
2. 拷贝到DB软件中直接可运行，#变注释
3. CDATA全包裹，转义简单

</td>
<td width="50%" valign="top">

**原生 MyBatis** 😩

```xml
<select id="findUsers" resultType="User">
    select * from t_user
   
    <where>
        <if test="id != null">
            and id = #{id}
        </if>
        <if test="name != null and name != ''">
            <bind name="namePattern" value="'%' + name + '%'"/>
            and name like #{namePattern}
        </if>
        <if test="minAge != null">
             and age <![CDATA[ > ]]> #{minAge}
        </if>
        <if test="statusList != null and statusList.size() > 0">
            and status in
            <foreach collection="statusList" item="i"
                     open="(" separator="," close=")">
                #{i}
            </foreach>
        </if>
    </where>
    order by id
</select>
```


</td>
</tr>
</table>

### 注解对比
两边等价：
<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis** 🚀

```java
@Select("select * from t_user #where id = :id and name like %:name%")
List<User> find(@Param("id") Long id, @Param("name") String name);
```
动态sql在原生注解中几乎是没眼看的状态，而simple-mybatis只需要一个`#`则将where后的条件自动转动态条件

</td>
<td width="50%" valign="top">

**原生 MyBatis** 😩

```java
@Select("""
        <script>
        select * from t_user
        <where>
            <if test='id != null'>AND id = #{id}</if>
            <if test='name != null and name != ""'>
                <bind name='namePattern' value="'%' + name + '%'"/>
                AND name LIKE #{namePattern}
            </if>
        </where>
        </script>
        """)
List<User> find(@Param("id") Long id, @Param("name") String name);
```


</td>
</tr>
</table>


**而且——这条 SQL 可以直接复制到数据库工具里执行。** `#` 开头的行在数据库看来就是注释，自动忽略，剩下的 `select * from t_user order by id` 直接能跑。再也不用手动删 `<if>` 标签了。

---

## 为什么选 simple-mybatis

| 痛点 | 原生 MyBatis | simple-mybatis |
|------|-------------|----------------|
| 动态条件 | `<if test="...">` 包裹，嵌套深 | `#and col = :col`，一行搞定 |
| 参数占位 | `#{name}` | `:name`（更短，少敲键） |
| LIKE 模糊查询 | `<bind>` + `<if>` 多行 | `like %:name%`（直觉写法） |
| IN 集合 | `<foreach>` 标签 5 行 | `in (:list)` 一行 |
| 动态 UPDATE SET | `<set>` + `<if>` 嵌套 | `#set col = :val,` 逗号模式 |
| 自定义条件 | `<if test="a != null and a > 0">` | `#(a != null && a > 0)` 支持 `&&`/`\|\|` |
| XML 转义 | `<`、`>`、`&` 要转义，CDATA 里标签又失效 | CDATA 全包裹，**零转义** |
| 拷到 DB 工具调试 | 手动删标签改参数 | 直接粘贴，`#` 行自动当注释 |
| 运行时性能 | 原生 | **完全一致**（启动期翻译，运行期走原生） |
| 第三方依赖 | — | **零**（仅 MyBatis 核心，`provided`） |

---

## 30 秒上手

**1. 添加依赖**

```xml
<dependency>
    <groupId>io.github.luo-zhan</groupId>
    <artifactId>simple-mybatis</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**2. 全局启用**（一行配置，所有 SQL 自动增强）

```xml
<!-- mybatis-config.xml -->
<settings>
    <setting name="defaultScriptingLanguage"
             value="io.github.luozhan.simplemybatis.EnhancedLanguageDriver"/>
</settings>
```

**3. 开始写 SQL**——注解和 XML 都支持，已有的原生 SQL 完全兼容，不需要改动。

---

## 语法速览

### `#where` / `#and` / `#or` — 动态条件，参数为空自动省略

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
select * from t_user
#where id = :id
#and name = :name
#and status in (:statusList)
```

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<select id="find" resultType="User">
    select * from t_user
    <where>
        <if test="id != null">and id = #{id}</if>
        <if test="name != null and name != ''">
            and name = #{name}
        </if>
        <if test="statusList != null
                  and statusList.size() > 0">
            and status in
            <foreach collection="statusList" item="i"
                     open="(" separator="," close=")">
                #{i}
            </foreach>
        </if>
    </where>
</select>
```

</td>
</tr>
</table>

### 静态锚点 — 有固定过滤条件时更优雅

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
select * from t_user
where deleted = 0          -- 恒在的静态条件
#and id = :id              -- 动态条件跟随
#and name like %:name%
```

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<select id="find" resultType="User">
    select * from t_user
    where deleted = 0
    <bind name="namePattern" value="'%' + name + '%'"/>
    <if test="id != null">and id = #{id}</if>
    <if test="name != null and name != ''">
        and name like #{namePattern}
    </if>
</select>
```

</td>
</tr>
</table>

### `#set` — 动态 UPDATE，与 `#where` 对称

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
update t_user
#set name = :name,
#age = :age,
where id = :id
```

`name=null`、`age=20` → `update t_user set age = ? where id = ?`

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<update id="update">
    update t_user
    <set>
        <if test="name != null">name = #{name},</if>
        <if test="age != null">age = #{age},</if>
    </set>
    where id = #{id}
</update>
```

</td>
</tr>
</table>

### `like %:name%` — 通配符自动拼到参数值

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
#and name like %:name%     -- 绑定值 %name%
#and code like :code%      -- 绑定值 code%
#and tail like %:tail      -- 绑定值 %tail
```

用 `<bind>` 实现，跨数据库安全

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<bind name="namePattern" value="'%' + name + '%'"/>
<if test="name != null and name != ''">
    and name like #{namePattern}
</if>
<bind name="codePattern" value="code + '%'"/>
<if test="code != null and code != ''">
    and code like #{codePattern}
</if>
```

每个 like 都要 `<bind>` + `<if>` 两行

</td>
</tr>
</table>

### `in (:list)` — 集合自动展开，空集合整体省略

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
#and status in (:statusList)
```

一行搞定，空集合自动省略整个 IN 条件

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<if test="statusList != null
          and statusList.size() > 0">
    and status in
    <foreach collection="statusList" item="i"
             open="(" separator="," close=")">
        #{i}
    </foreach>
</if>
```

判空 + `foreach` 标签，6 行才写完一个 IN

</td>
</tr>
</table>

### `#(expr)` — 自定义 OGNL 条件，支持 `&&`/`||` 和 `and`/`or`

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```sql
#(id != null && id > 0) and id = :id
```

`&&`/`||` 和 `and`/`or` 都支持，框架统一转换

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<if test="id != null and id > 0">
    and id = #{id}
</if>
```

`&&` 要写成 `and`，`>` 在 XML 属性里也得留意

</td>
</tr>
</table>

### CDATA 全包裹 — 告别 XML 转义

<table>
<tr>
<td width="50%" valign="top">

**simple-mybatis**

```xml
<select id="find" resultType="User"><![CDATA[
    select * from t_user
    where age > 10
      and type <> 'a'
      and score >= :minScore
]]></select>
```

CDATA 全包裹，`<` `>` `&` 直接写，零转义

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<select id="find" resultType="User">
    select * from t_user
    where age &gt; 10
      and type &lt;&gt; 'a'
      and score &gt;= #{minScore}
</select>
```

`<if>` 标签不能用 CDATA 包裹，
`<` `>` `&` 只能手动转义

</td>
</tr>
</table>

---

## 设计理念

- **超集兼容**：不使用新语法的 SQL 行为完全不变，完全兼容原生语法（甚至可以混用，但不推荐）
- **启动期翻译**：所有增强语法在 MyBatis 初始化时翻译为标准 `<if>`/`<where>`/`<set>`/`<foreach>`/`<bind>` + `#{}`，运行期与原生 MyBatis 完全一致
- **行级边界**：`#`动态标记的作用范围是到行尾，简单易懂
- **DMS 友好**：`#` 行在数据库工具中被视为注释，SQL 可直接粘贴调试
- **可扩展**：`Directive` SPI + `ServiceLoader`，支持接入自定义指令

---

## 兼容性

| 项 | 值 |
|---|---|
| Java | 8+ |
| MyBatis | 3.2.0 ~ 3.5.x（实测 3.5.x，建议 3.4.6+） |
| 第三方依赖 | 无（MyBatis 核心 `provided`） |
| 入口 | XML Mapper · `@Select`/`@Update` 注解 |
| 逃生舱 | 单条语句可用 `lang` / `@Lang` 跳过增强 |

---

## 详细语法

完整的语法说明、最佳实践和进阶用法见 **[syntax-guide.md](syntax-guide.md)**。

## License

[Apache License 2.0](LICENSE)
