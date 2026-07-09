# simple-mybatis

> **告别 `<if>`/`<where>`/`<foreach>` 标签地狱——用最自然的方式，写最干净的动态 SQL。**

一个 MyBatis `LanguageDriver` 增强插件：在**启动期**渲染成原生语法，运行期**零额外开销**。仅依赖 MyBatis 核心，无第三方库，即插即用，兼容其他mybatis框架。

---

## 先看效果

> 下面对比的 simple-mybatis 语法和 MyBatis 原生语法完全等价

###  xml 对比

#### simple-mybatis 🚀

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



#### 原生 MyBatis 🐢


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




### 注解对比


#### simple-mybatis 🚀

```java
@Select("select * from t_user #where id = :id and name like %:name%")
List<User> find(@Param("id") Long id, @Param("name") String name);
```
只需要一个`#`则将where后的sql条件自动转动态条件



#### 原生 MyBatis 🐢

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
几乎没人会用注解写动态sql



## 为什么选 simple-mybatis
如果你也有这些痛点：
1. **书写&阅读困难**： xml 动态sql代码冗余，一个in语句写 5 行
2. **sql调试不便**：sql拷贝到DB软件中调试要挨个删标签，很不方便
3. **XML转义恶心**: 遇到 `<`、`>` 要么用CDATA挨个转义，要么用&号转义，繁琐

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

### **1. 添加依赖**

```xml
<dependency>
    <groupId>io.github.luo-zhan</groupId>
    <artifactId>simple-mybatis</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### **2. 全局启用**（一行配置，所有 SQL 自动增强）

```xml
<!-- mybatis-config.xml -->
<settings>
    <setting name="defaultScriptingLanguage"
             value="io.github.luozhan.simplemybatis.EnhancedLanguageDriver"/>
</settings>
```

### **3. 开始写 SQL**
注解和 XML 都支持，存量原生 SQL 完全兼容，不改动也能正常运行，也兼容其他 MyBatis 框架


## 语法速览
### `:param` - 参数简化
代替`#{param}`，简化常用写法，如`= :param`、`> :param`、`in (:param)`、`like %:param%`


###  `#` - 动态条件
简化 `<if>`、`<where>`

<table>
<tr>
<td width="40%" valign="top">

**simple-mybatis 🚀**

```sql
select * from t_user
# where id = :id
# and name = :name
# and status in (:statusList)
```
>1.参数为空自动省略一行   
>2.自动区分判空是判空字符串还是空集合  
>2.`#where` 自动转换成`<where>`   
>3.拷贝到DB软件中直接可运行
</td>
<td width="60%" valign="top">

**原生 MyBatis 🐢**

```xml
select * from t_user
<where>
    <if test="id != null">
        and id = #{id}
    </if>
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
```

</td>
</tr>
</table>




### `like` - 自动处理`%` 通配符
简化`<bind>`或 `concat()`

<table>
<tr>
<td width="40%" valign="top">

**simple-mybatis 🚀**

```sql
# and name like %:name%    
# and code like :code%      
# and tail like %:tail    
```

> 1.语法简单直观   
> 2.自动转换成 `<bind>` ，跨数据库安全

</td>
<td width="60%" valign="top">

**原生 MyBatis 🐢**

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

</td>
</tr>
</table>

### `in` - 自动处理参数

<table>
<tr>
<td width="40%" valign="top">

**simple-mybatis 🚀**

```sql
# and status in (:statusList)
```

一行搞定，null或空集合自动省略整个 IN 条件

</td>
<td width="60%" valign="top">

**原生 MyBatis 🐢**

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
    # and type > :type
]]></select>
```

CDATA 全包裹，不用考虑sql中有特殊字符

</td>
<td width="50%" valign="top">

**原生 MyBatis**

```xml
<select id="find" resultType="User">
    select * from t_user
    where age <![CDATA[ > ]]> 10 
    <if test="type != null">
      and type <![CDATA[ > ]]> #{type}
    </if>
</select>
```

`<if>` 标签不能用 CDATA 包裹， 特殊字符只能挨个转义

</td>
</tr>
</table>

---

## 设计理念

- **超集兼容**：完全兼容原生语法，不用增强语法也能运行（甚至可以混用，但不推荐）
- **启动期翻译**：所有增强语法在 MyBatis 初始化时翻译为标准 `<if>`/`<where>`/`<set>`/`<foreach>`/`<bind>` + `#{}`，运行期与原生 MyBatis 完全一致
- **行级边界**：`#`动态标记的作用范围是到行尾，简单易懂，
- **DMS 友好**：`#` 行在数据库工具中被视为注释，SQL 可直接粘贴调试
- **可扩展**：`Directive` SPI + `ServiceLoader`，支持扩展自定义语法

---

## 兼容性

| 项 | 值                                  |
|---|------------------------------------|
| Java | 8+                                 |
| MyBatis | 3.2.0 ~ 3.5.x（实测 3.5.x，建议 3.4.6+）  |
| 第三方依赖 | 无（ 仅MyBatis  `provided`）           |
| 入口 | XML  · `@Select`/`@Update` 注解      |
| 逃生舱 | 单条语句可用 `lang` / `@Lang` 强制忽略增强语法解析 |

---

## 详细语法

完整的语法说明、最佳实践和进阶用法见 **[syntax-guide.md](syntax-guide.md)**。

## License

[Apache License 2.0](LICENSE)
