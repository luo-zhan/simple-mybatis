# simple-mybatis语法指南

## 1. 一句话介绍



### 原生 MyBatis
原生语法太过冗长，sql拷贝到DB工具中还需要挨个修改条件才能运行
```xml
<select id="findUsers" resultType="User">
    select * from user
    <where>
        <if test="id != null">
            id = #{id}
        </if>
        <if test="name != null and name != ''">
            and name like concat('%', #{name}, '%')
        </if>
        <if test="statusList != null and statusList.size > 0">
            and status in
            <foreach collection="statusList" item="item" open="(" separator="," close=")">
                #{item}
            </foreach>
        </if>
    </where>
</select>
```

### 简化语法
语法符合直觉，可以直接代替原myabtis语法，拷贝到db可以直接运行

```xml
<select id="findUsers" resultType="User">
    select * from user
    # where id = :id
    # and name like %:name%
    # and status in (:statusList)
</select>
```
注：上面两条sql完全等价

---

## 2. 最简示例
使用`#`开启动态条件
```xml
<select id="findById" resultType="User">
    select * from user
    # where id = :id
    # and name like %:name%
</select>
```

在`@Select`等Mybatis原生注解中也天然支持

```java
@Select("select * from user #where id = :id or name like %:name%")
User findByIdOrName(Long id, String name);
```
> 使用符号#的原因，是整条sql拷贝到DB工具中会自动忽略动态条件，不需要修改查询条件就能直接运行
---

## 3. 基础语法

### 3.1 `#` 动态条件

`#` 标记从#开始，到行尾，都为动态条件。如果 `#` 后面有多个条件（用 `and`/`or` 连接），每个条件都是独立的动态条件，各自判断是否为空：


**空值的默认定义**：

- `null`
- 空字符串 `""`
- 空集合、空数组、空 Map
- 参数 Map 中缺失的 key

```sql
select * from user
# where id = :id
```

`#`后多个条件时，每个条件独立生效：
```sql
select * from user
# where id = :id and name = :name 
```
等价于：
```sql
select * from user
# where id = :id
# and name = :name
```
即：
```xml
<where>
    <if test="id != null">and id = #{id}</if>
    <if test="name != null and name != ''">and name = #{name}</if>
</where>
```
注：这样的设计使得在注解中写动态sql非常便利
```sql
@Select("select * from t_user #where id = :id and name = %:name%")
List<User> find(@Param("id") Long id, @Param("name") String name);
```


### 3.2 `:param` 占位符

`:name` 是 `#{name}` 的简写，仅生成 PreparedStatement 占位符，**不会**引入不安全的 `${}`。

```sql
# id = :id
# and name = :name
```

等价于：

```xml
<if test="id != null"> id = #{id} </if>
<if test="name != null and name != ''"> and name = #{name} </if>
```
当然，如果你不习惯这种写法，也可以用原生的`#{param}`
### 3.3 where 自动处理

你只需写自然 SQL，`where`、`and`、`or` 关键字和由框架自动处理：

- 所有查询条件都省略时，`where` 不会出现；
- 首个条件省略时，会自动将后续第一个`and`变成`where`

```sql
select * from user
# where id = :id
# and name = :name
```

如果 `id = null`、`name = "Tom"`，生成：

```sql
select * from user 
where name = 'Tom'
```

注意 `and` 被自动去掉了。

---

## 4. 进阶语法

### 4.1 like 优化

支持三种通配形式，通配符会合并到参数值里：

```sql
# name like %:name%    -- 参数值自动包装成 %name%
# name like :name%     -- 参数值自动包装成 name%
# name like %:name     -- 参数值自动包装成 %name
```

示例：

```sql
select * from user 
# where name like :name%
```
等价于
```sql
select * from user
<where>
    <if test="name != null and name != ''">
        <bind name="namePattern" value="name + '%'"/>
        and name like #{namePattern}
    </if>
</where>
```

传入 `name = "张"`，生成：

```sql
select * from user where name like '张%'
```


### 4.2 in 优化

```sql
select * from user
# where status in (:statusList)
```
等价于：
```sql
select * from user
<where>
    <if test="statusList != null and statusList.size > 0">
        and status in
        <foreach collection="statusList" item="item" open="(" separator="," close=")">
            #{item}
        </foreach>
    </if>
</where>
```


### 4.3 between

```sql
select * from user
#where created_at between :startDate and :endDate
```

注意`startDate` 或 `endDate` 任一为空，整个 `between` 条件整体省略

如果想实现"只填开始时间就查 >=，只填结束时间就查 <="，推荐拆成两个条件：

```sql
select * from user
# where created_at >= :startDate
# and created_at <= :endDate
```

### 4.4 动态条件与静态条件混合


```sql
select * from user
where deleted = 0
# and id = :id
# and status in (:statusList)
```
### 4.5 xml转义优化
在xml中，某些字符需要转义否则会报错，例如`<`、`>`等，需要转义为`&lt;`、`&gt;`等，或者用CDATA包裹。

这些特殊字符在原生 MyBatis 中处理十分麻烦，原生标签如果放在CDATA中会被解析成文本，但使用增强语法可以用CDATA轻易的包裹完整sql

```xml
<select id="findUsers"><![CDATA[
    select * from user 
    # where id > :id
    # and age < :age
]]></select>
```
等价于：
```xml
<select id="findUsers">
    select * from user 
    <if test="id != null"> <![CDATA[ and id > #{id} ]]></if>
    <if test="age != null"><![CDATA[ and age < #{age} ]]> </if>
</select>
```
---

## 5. 高级用法

### 5.1 自定义 OGNL 表达式

默认判空已经能涵盖 95% 的场景，如果默认不满足需求，可以用 `#(expr) condtion` 来自定义if条件：

```sql
select * from user
#(id != null && id > 0) where id = :id
```
括号内的表达式与 MyBatis `<if test="...">` 的 OGNL 语法等价。

示例：使用自定义表达式实现`<choose>`,`<when>`标签的功能：

```sql
# (type == 'a') and name like %:name%
# (type == 'b') and status = :status
# (type!='a' && type!='b') and id = :id
```
约等于：
```xml
<choose>
    <when test="type == 'a'">
        name like %:name%
    </when>
    <when test="type == 'b'">
        status = :status
    </when>
    <otherwise>
        id = :id
    </otherwise>
</choose>
```
### 5.2 复杂表达式

`#` 可以包裹带括号的复杂条件，和`between`一样，只要有任一参数为空，则整行条件忽略：

```sql
select * from user
#where (status = :status or type = :type)
```

---
## 6. 最佳实践
### 6.1 静态条件紧靠where
有静态条件时推荐where后先写静态条件，这样拷贝sql到db时不用修改语句
```sql
select * from user
where status = 1
# and id = :id
# and name = :name
```
优于：
```sql
select * from user
# where id = :id
# and name = :name
and status = 1
```
运行结果是一样的（条件位置顺序不影响sql性能），但是后者拷贝到db软件需要修改语句才能执行

### 6.2 多行sql共用一个判断条件
原生语法如下所示：
```xml
<select id="findUsers">
    select * from user where 1=1
    <if test="status == 1">  
        and id = #{id} 
        and name = #{name} 
    </if>
</select>
```
在增强语法中，建议写在一行（推荐，语义更好）：
```sql
select * from user where 1=1
#(status == 1) and id = :id  and name = :name
```
或者多行分别定义一次判断条件：
```sql
select * from user where 1=1
# (status == 1) and id = :id  
# (status == 1) and name = :name
```
### 6.3 注解 vs xml

sql简单，优先使用注解，一个`#where`就所有查询条件自动动态化（静态条件不受影响）：
```java
@Select("select id, name from user #where id = :id and name like %:name% ")
List<User> findUsers(Long id, String name);
```
sql较长或者复杂，推荐XML，一行sql一个条件：

```sql
<select id="findUsers" resultType="User">
    select * from user
    # where id = :id
    # and name like %:name%
    # and status in (:statusList)
    and store_id in (
        select store_id from store 
        # where name = :storeName
    )
</select>
```
jdk15之后有了多行字符串，根据个人喜好也可以将比较长的sql写在注解中
```java
@Select("""
    select * from user 
    # where id = :id 
    # and name like %:name% 
    # and status in (:statusList)
      and store_id in (select store_id from store where name = :storeName)
        """)
List<User> findUsers(Long id, String name);
```

---

## 7. 极强的兼容性

增强语法是原生语法的**超集**，以下原生写法不会受影响：

```sql
select * from user where id = #{id}
```

甚至可以在同一条 SQL 中混用：

```sql
select * from user
<where>
    #name = #{name}
    #and id = :id
    <if test="age != null">
        and age = :age
    </if>
</where>
```
生成结果：
```xml
select * from user
<where>
    <if test="name != null and name != ''">
        name = #{name}
    </if>
    <if test="id != null and id != ''">
        and id = #{id}
    </if>
    <if test="age != null">
        and age = #{age}
    </if>
</where>
```
但不推荐混用，有可能产生冲突，仅在出现增强语法无法实现时临时使用，并反馈issue到github


## 8. 逃生舱

极少数情况下，如果某条 SQL 不想被增强语法解析但误解析而出错，可以用 MyBatis 官方方式申明使用原生xml解析器，跳过框架的逻辑：

**XML：**

```xml
<select id="rawSql" lang="org.apache.ibatis.scripting.xmltags.XMLLanguageDriver">
    <!--  使用原生写法  -->
    select * from user where col::int = #{value}
</select>
```

**注解：**

```java
@Lang(XMLLanguageDriver.class)
@Select("select * from user where col::int = #{value}")
User rawSql(Integer value);
```
同时将问题反馈issue到github
> `XMLLanguageDriver"` 是MyBatis默认实现

