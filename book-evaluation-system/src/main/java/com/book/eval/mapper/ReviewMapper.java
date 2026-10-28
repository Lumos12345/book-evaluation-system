package com.book.eval.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReviewMapper {

    /** 插入一条 AI 生成的书籍评论 */
    @Insert("INSERT INTO book_reviews (book_id, content, source, rating, sentiment, sentiment_score, keywords, author, like_count, reply_count, created_at) " +
            "VALUES (#{bookId}, #{content}, #{source}, #{rating}, #{sentiment}, #{sentimentScore}, #{keywords}, #{author}, #{likeCount}, #{replyCount}, #{createdAt})")
    void insertReview(@Param("bookId") Long bookId, @Param("content") String content, @Param("source") String source,
                      @Param("rating") double rating, @Param("sentiment") String sentiment, @Param("sentimentScore") double sentimentScore,
                      @Param("keywords") String keywords, @Param("author") String author,
                      @Param("likeCount") int likeCount, @Param("replyCount") int replyCount, @Param("createdAt") String createdAt);

    /** 查询没有任何评论的书籍（用于启动时自动补评论） */
    @Select("SELECT b.id, b.title, b.author, b.genre, b.description FROM books b " +
            "LEFT JOIN book_reviews r ON r.book_id = b.id WHERE r.id IS NULL")
    List<Map<String, Object>> selectBooksWithoutReviews();

    /** 工具用：获取评论（时间倒序） */
    @Select("""
            SELECT id, content, rating, sentiment, sentiment_score, keywords,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
            FROM book_reviews
            WHERE book_id = #{bookId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectByBookId(@Param("bookId") Long bookId, @Param("limit") int limit);

    /** 分析页用：获取评论完整字段（时间倒序） */
    @Select("""
            SELECT id, content, rating, author, sentiment, sentiment_score,
                   keywords, like_count, reply_count,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
            FROM book_reviews
            WHERE book_id = #{bookId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectFullByBookId(@Param("bookId") Long bookId, @Param("limit") int limit);

    /** 对比分析用：情感/评分字段 */
    @Select("""
            SELECT sentiment, rating, sentiment_score
            FROM book_reviews
            WHERE book_id = #{bookId}
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectSentimentRows(@Param("bookId") Long bookId, @Param("limit") int limit);

    /** /api/review-trend 按天聚合 */
    @Select("""
            <script>
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS date,
                   COUNT(*) AS count,
                   COALESCE(AVG(rating), 0) AS avg_rating,
                   SUM(CASE WHEN sentiment = '正面' THEN 1 ELSE 0 END) AS positive,
                   SUM(CASE WHEN sentiment = '负面' THEN 1 ELSE 0 END) AS negative,
                   SUM(CASE WHEN sentiment = '中性' THEN 1 ELSE 0 END) AS neutral
            FROM book_reviews
            <if test="bookId != null">WHERE book_id = #{bookId}</if>
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            ORDER BY date DESC
            LIMIT 30
            </script>
            """)
    List<Map<String, Object>> selectTrendGroupByDate(@Param("bookId") Long bookId);

    /** /api/keyword-trend 关键词按天出现次数 */
    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS date,
                   COUNT(*) AS count
            FROM book_reviews
            WHERE book_id = #{bookId} AND keywords LIKE CONCAT('%', #{keyword}, '%')
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            ORDER BY date DESC
            LIMIT 30
            """)
    List<Map<String, Object>> selectKeywordTrend(@Param("bookId") Long bookId, @Param("keyword") String keyword);

    /** /api/time-analysis 按年月聚合 */
    @Select("""
            SELECT YEAR(created_at) AS year, MONTH(created_at) AS month,
                   COUNT(*) AS count,
                   COALESCE(AVG(rating), 0) AS avg_rating,
                   SUM(CASE WHEN sentiment = '正面' THEN 1 ELSE 0 END) AS positive_count,
                   SUM(CASE WHEN sentiment = '负面' THEN 1 ELSE 0 END) AS negative_count
            FROM book_reviews
            WHERE book_id = #{bookId}
            GROUP BY YEAR(created_at), MONTH(created_at)
            ORDER BY year DESC, month DESC
            """)
    List<Map<String, Object>> selectTimeAnalysis(@Param("bookId") Long bookId);

    /** AI 问答用：情感统计汇总 */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN sentiment = '正面' THEN 1 ELSE 0 END) AS positive,
                   SUM(CASE WHEN sentiment = '中性' THEN 1 ELSE 0 END) AS neutral,
                   SUM(CASE WHEN sentiment = '负面' THEN 1 ELSE 0 END) AS negative,
                   COALESCE(AVG(rating), 0) AS avg_rating
            FROM book_reviews
            WHERE book_id = #{bookId}
            """)
    Map<String, Object> selectStats(@Param("bookId") Long bookId);

    /** 书籍全部关键词（逗号分隔存储） */
    @Select("""
            SELECT keywords FROM book_reviews
            WHERE book_id = #{bookId} AND keywords IS NOT NULL AND keywords != ''
            """)
    List<String> selectKeywords(@Param("bookId") Long bookId);

    /** 导出评论数据 */
    @Select("""
            SELECT id, content, rating, sentiment,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at
            FROM book_reviews
            WHERE book_id = #{bookId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectExportReviews(@Param("bookId") Long bookId, @Param("limit") int limit);

    /** 智能荐书用：按书聚合情感计数 */
    @Select("SELECT book_id, sentiment, COUNT(*) AS count FROM book_reviews GROUP BY book_id, sentiment")
    List<Map<String, Object>> selectSentimentByBook();

    /** 全部评论关键词（相似书籍推荐用） */
    @Select("SELECT book_id, keywords FROM book_reviews WHERE keywords IS NOT NULL AND TRIM(keywords) <> ''")
    List<Map<String, Object>> selectAllKeywords();

    /** 删除指定书籍的全部评论（删除书籍时联动清理） */
    @Delete("DELETE FROM book_reviews WHERE book_id = #{bookId}")
    int deleteByBookId(@Param("bookId") Long bookId);
}
