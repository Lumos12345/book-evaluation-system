package com.book.eval.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserReviewMapper {

    /** 保存用户投稿书评（AI 分析结果一并入库） */
    @Insert("""
            INSERT INTO user_reviews(user_id, username, book_id, book_title, content,
                                     rating, sentiment, sentiment_score, keywords, ai_comment)
            VALUES(#{userId}, #{username}, #{bookId}, #{bookTitle}, #{content},
                   #{rating}, #{sentiment}, #{sentimentScore}, #{keywords}, #{aiComment})
            """)
    void insert(@Param("userId") Long userId,
                @Param("username") String username,
                @Param("bookId") Long bookId,
                @Param("bookTitle") String bookTitle,
                @Param("content") String content,
                @Param("rating") Object rating,
                @Param("sentiment") String sentiment,
                @Param("sentimentScore") Object sentimentScore,
                @Param("keywords") String keywords,
                @Param("aiComment") String aiComment);

    /** 我的书评列表（按用户隔离，最新在前） */
    @Select("""
            SELECT id, book_id, book_title, content, rating,
                   sentiment, sentiment_score, keywords, ai_comment,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS created_at
            FROM user_reviews
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectByUserId(@Param("userId") Long userId);

    /** 删除我的书评（id + 用户双重校验归属） */
    @Delete("""
            DELETE FROM user_reviews WHERE id = #{id} AND user_id = #{userId}
            """)
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 统计用户书评总数（徽章等级用） */
    @Select("SELECT COUNT(*) FROM user_reviews WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
