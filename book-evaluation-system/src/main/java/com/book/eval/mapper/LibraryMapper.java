package com.book.eval.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 自定义书库 Mapper
 */
@Mapper
public interface LibraryMapper {

    /** 用户的自定义书库列表 */
    @Select("SELECT id, name, book_ids AS bookIds, created_at AS createdAt FROM libraries WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Map<String, Object>> listByUser(@Param("userId") Long userId);

    /** 新建书库 */
    @Insert("INSERT INTO libraries (user_id, name, book_ids) VALUES (#{userId}, #{name}, #{bookIds})")
    void insert(@Param("userId") Long userId, @Param("name") String name, @Param("bookIds") String bookIds);

    /** 同名书库检查 */
    @Select("SELECT COUNT(*) FROM libraries WHERE user_id = #{userId} AND name = #{name}")
    long existsByName(@Param("userId") Long userId, @Param("name") String name);

    /** 删除当前用户的指定书库（带归属校验，返回受影响行数） */
    @Delete("DELETE FROM libraries WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    /** 最近一次插入的自增 ID */
    @Select("SELECT LAST_INSERT_ID()")
    Long selectLatestId();
}
