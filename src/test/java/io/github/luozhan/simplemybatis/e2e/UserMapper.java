package io.github.luozhan.simplemybatis.e2e;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 注解入口 Mapper：验证 @Select/@Update 走增强语法（defaultScriptingLanguage）。
 */
public interface UserMapper {

    /**
     * #where + like：id/name 任一为空各自省略。
     */
    @Select("select * from t_user #where id = :id #and name like %:name%")
    List<User> find(@Param("id") Long id, @Param("name") String name);

    /**
     * in 集合：空集合时整个 in 省略。
     */
    @Select("select count(*) from t_user # where status in (:statusList)")
    long countByStatus(@Param("statusList") List<Integer> statusList);

    /**
     * #set 逗号模式：仅非空字段被更新。
     */
    @Update("update t_user #set name = :name, #age = :age, where id = :id")
    int update(@Param("id") Long id, @Param("name") String name, @Param("age") Integer age);

    /**
     * #(expr) 自定义表达式，&& 统一转 and。
     */
    @Select("select * from t_user where 1=1 #(id != null && id > 0) and id = :id")
    List<User> exprFind(@Param("id") Long id);

    /**
     * 静态锚点写法：where deleted = 0 恒在。
     */
    @Select("select * from t_user #where deleted = 0  and id = :id")
    List<User> findActive(@Param("id") Long id);

    /**
     * 验证：XML 中省略 resultType，由 MyBatis 从接口方法自动推断。
     */
    List<User> findByAgeNoResultType(@Param("minAge") Integer minAge);

    /**
     * XML 定义的方法（对应 UserXmlMapper.xml 中的语句）。
     */
    List<User> findByAgeCData(@Param("minAge") Integer minAge, @Param("name") String name);

    /**
     * 逃生舱测试方法。
     */
    List<User> rawFind(@Param("id") Long id);
}
