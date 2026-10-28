package com.book.eval.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 收藏书架数据访问 */
@Mapper
public interface FavoriteMapper {

    /** 是否已收藏 */
    @Select("SELECT COUNT(*) FROM favorites WHERE user_id = #{userId} AND book_id = #{bookId}")
    long exists(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /** 收藏 */
    @Insert("INSERT INTO favorites (user_id, book_id) VALUES (#{userId}, #{bookId})")
    void insert(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /** 取消收藏 */
    @Delete("DELETE FROM favorites WHERE user_id = #{userId} AND book_id = #{bookId}")
    void delete(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /** 删除某书的全部收藏记录（删除书籍时联动清理） */
    @Delete("DELETE FROM favorites WHERE book_id = #{bookId}")
    int deleteByBookId(@Param("bookId") Long bookId);

    /** 我的收藏书籍列表（含书信息） */
    @Select("SELECT b.id, b.title, b.author, b.genre, b.total_sales, b.total_rating, " +
            "ROUND(AVG(r.sentiment_score), 2) AS avg_sentiment, " +
            "SUM(CASE WHEN r.sentiment = '正面' THEN 1 ELSE 0 END) AS positive, " +
            "COUNT(r.id) AS review_count " +
            "FROM favorites f JOIN books b ON f.book_id = b.id " +
            "LEFT JOIN book_reviews r ON r.book_id = b.id " +
            "WHERE f.user_id = #{userId} GROUP BY b.id ORDER BY f.created_at DESC")
    List<Map<String, Object>> listByUser(@Param("userId") Long userId);
}
