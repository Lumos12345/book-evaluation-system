package com.book.eval.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface BookMapper {

    /** /api/books 书籍列表（含评论数、均分） */
    @Select("""
            SELECT n.id, n.title, n.author, n.genre, n.total_sales,
                   n.total_rating, n.rating_count, n.description,
                   COUNT(r.id) AS review_count,
                   COALESCE(AVG(r.rating), 0) AS avg_rating
            FROM books n
            LEFT JOIN book_reviews r ON n.id = r.book_id
            GROUP BY n.id
            ORDER BY n.total_sales DESC
            """)
    List<Map<String, Object>> selectBooksWithStats();

    /** /api/sales-ranking 销量排行 */
    @Select("""
            SELECT n.id, n.title, n.author, n.genre,
                   n.total_sales, n.total_rating,
                   COUNT(r.id) AS review_count
            FROM books n
            LEFT JOIN book_reviews r ON n.id = r.book_id
            GROUP BY n.id
            ORDER BY n.total_sales DESC
            """)
    List<Map<String, Object>> selectSalesRanking();

    /** /api/book-detail 书籍详情 */
    @Select("""
            SELECT n.*,
                   COUNT(r.id) AS review_count,
                   COALESCE(AVG(r.rating), 0) AS avg_rating,
                   SUM(CASE WHEN r.sentiment = '正面' THEN 1 ELSE 0 END) AS positive_count,
                   SUM(CASE WHEN r.sentiment = '中性' THEN 1 ELSE 0 END) AS neutral_count,
                   SUM(CASE WHEN r.sentiment = '负面' THEN 1 ELSE 0 END) AS negative_count
            FROM books n
            LEFT JOIN book_reviews r ON n.id = r.book_id
            WHERE n.id = #{bookId}
            GROUP BY n.id
            """)
    Map<String, Object> selectBookDetail(@Param("bookId") Long bookId);

    /** 书籍基本信息 */
    @Select("""
            SELECT id, title, author, genre, total_sales, total_rating, description
            FROM books WHERE id = #{bookId}
            """)
    Map<String, Object> selectBookInfo(@Param("bookId") Long bookId);

    /** 对比分析用：仅基础字段 */
    @Select("""
            SELECT id, title, author, genre, total_sales, total_rating
            FROM books WHERE id = #{bookId}
            """)
    Map<String, Object> selectBookBasic(@Param("bookId") Long bookId);

    /** 最新一本书的ID（新增书籍后回查用） */
    @Select("SELECT id FROM books ORDER BY id DESC LIMIT 1")
    Long selectLatestId();

    /** 新增书籍（页面添加书籍功能） */
    @Insert("""
            INSERT INTO books (title, author, genre, total_sales, total_rating, price, description)
            VALUES (#{title}, #{author}, #{genre}, #{totalSales}, #{totalRating}, #{price}, #{description})
            """)
    int insertBook(@Param("title") String title, @Param("author") String author, @Param("genre") String genre,
                   @Param("totalSales") int totalSales, @Param("totalRating") double totalRating,
                   @Param("price") double price, @Param("description") String description);

    /** 删除书籍（评论与收藏由 Service 联动清理） */
    @Delete("DELETE FROM books WHERE id = #{bookId}")
    int deleteBook(@Param("bookId") Long bookId);

    /** /api/comment-sources 评论来源（按书分组） */
    @Select("""
            SELECT n.id AS book_id, n.title AS source,
                   COUNT(r.id) AS count,
                   COALESCE(AVG(r.rating), 0) AS avg_rating
            FROM books n
            LEFT JOIN book_reviews r ON n.id = r.book_id
            GROUP BY n.id
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectCommentSources();

    /** AI 推荐候选：评分>=4.0 的前5本 */
    @Select("""
            SELECT title, author, total_rating, total_sales
            FROM books
            WHERE total_rating >= 4.0
            ORDER BY total_rating DESC, total_sales DESC
            LIMIT 5
            """)
    List<Map<String, Object>> selectTopRated();

    /** 推荐工具候选：评论数>5 按均分排序 前10 */
    @Select("""
            SELECT n.id, n.title, n.author, n.genre,
                   COUNT(r.id) AS review_count,
                   COALESCE(AVG(r.rating), 0) AS avg_rating
            FROM books n
            LEFT JOIN book_reviews r ON n.id = r.book_id
            GROUP BY n.id
            HAVING review_count > 5
            ORDER BY avg_rating DESC, review_count DESC
            LIMIT 10
            """)
    List<Map<String, Object>> selectRecommendCandidates();

    /** 书籍是否存在 */
    @Select("SELECT COUNT(*) FROM books WHERE id = #{bookId}")
    long countById(@Param("bookId") Long bookId);

    /** 健康检查 */
    @Select("SELECT 1")
    Integer ping();
}
