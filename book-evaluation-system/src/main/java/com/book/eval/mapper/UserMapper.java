package com.book.eval.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface UserMapper {

    /** 按用户名查用户（含密码哈希与盐） */
    @Select("""
            SELECT id, username, password_hash, salt, nickname
            FROM users WHERE username = #{username}
            """)
    Map<String, Object> selectByUsername(@Param("username") String username);

    /** 用户名是否已注册 */
    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    long countByUsername(@Param("username") String username);

    /** 新增用户 */
    @Insert("""
            INSERT INTO users(username, password_hash, salt, nickname)
            VALUES(#{username}, #{passwordHash}, #{salt}, #{nickname})
            """)
    void insert(@Param("username") String username,
                @Param("passwordHash") String passwordHash,
                @Param("salt") String salt,
                @Param("nickname") String nickname);
}
