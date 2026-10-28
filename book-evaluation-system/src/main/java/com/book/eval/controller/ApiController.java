package com.book.eval.controller;

import com.book.eval.service.AnalysisService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API 控制器 —— 路由与参考项目 urls.py 一致
 */
@Controller
public class ApiController {

    private final AnalysisService analysisService;

    public ApiController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** 首页 */
    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }

    /** /api/books 书籍列表 */
    @GetMapping("/api/books")
    @ResponseBody
    public Map<String, Object> getBooks() {
        return analysisService.getBooks();
    }

    /** /api/book-detail 书籍详情 */
    @GetMapping("/api/book-detail")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBookDetail(@RequestParam(required = false) Long book_id) {
        if (book_id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定书籍ID"));
        }
        Map<String, Object> result = analysisService.getBookDetail(book_id);
        if (result == null) {
            return ResponseEntity.status(404).body(Map.of("error", "书籍不存在"));
        }
        return ResponseEntity.ok(result);
    }

    /** /api/sales-ranking 销量排行 */
    @GetMapping("/api/sales-ranking")
    @ResponseBody
    public Map<String, Object> getSalesRanking() {
        return analysisService.getSalesRanking();
    }

    /** /api/analyze-comments 智能分析评论 */
    @PostMapping("/api/analyze-comments")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeComments(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的JSON数据"));
        }
        Long bookId = toLong(body.get("book_id"));
        String question = body.get("question") != null ? String.valueOf(body.get("question")) : "";
        int limit = toInt(body.get("limit"), 200);

        if (bookId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定书籍ID"));
        }
        Map<String, Object> result = analysisService.analyzeComments(bookId, question, limit);
        if (result.containsKey("error")) {
            if ("书籍不存在".equals(result.get("error"))) {
                return ResponseEntity.status(404).body(result);
            }
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(result);
    }

    /** /api/review-trend 评论趋势 */
    @GetMapping("/api/review-trend")
    @ResponseBody
    public Map<String, Object> getReviewTrend(@RequestParam(required = false) Long book_id) {
        return analysisService.getReviewTrend(book_id);
    }

    /** /api/comment-sources 评论来源 */
    @GetMapping("/api/comment-sources")
    @ResponseBody
    public Map<String, Object> getCommentSources() {
        return analysisService.getCommentSources();
    }

    /** /api/keyword-trend 关键词趋势 */
    @GetMapping("/api/keyword-trend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getKeywordTrend(
            @RequestParam(required = false) Long book_id,
            @RequestParam(defaultValue = "") String keyword) {
        if (book_id == null || keyword.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供书籍ID和关键词"));
        }
        return ResponseEntity.ok(analysisService.getKeywordTrend(book_id, keyword));
    }

    /** /api/time-analysis 时间维度分析 */
    @GetMapping("/api/time-analysis")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTimeAnalysis(@RequestParam(required = false) Long book_id) {
        if (book_id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供书籍ID"));
        }
        return ResponseEntity.ok(analysisService.getTimeAnalysis(book_id));
    }

    /** /api/compare-books 对比多本书籍 */
    @PostMapping("/api/compare-books")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> compareBooks(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的JSON数据"));
        }
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.getOrDefault("book_ids", List.of());
        List<Long> bookIds = rawIds.stream().map(Number::longValue).toList();
        int limit = toInt(body.get("limit"), 100);

        if (bookIds.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "请至少选择2本小说进行对比"));
        }
        Map<String, Object> result = analysisService.compareBooks(bookIds, limit);
        if (result.containsKey("error")) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** /api/health 健康检查 */
    @GetMapping("/api/health")
    @ResponseBody
    public Map<String, Object> health() {
        return analysisService.health();
    }

    /** /api/agent/analyze Agent 智能分析 */
    @PostMapping("/api/agent/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> agentAnalyze(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的JSON数据"));
        }
        String question = body.get("question") != null ? String.valueOf(body.get("question")) : "";
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.getOrDefault("book_ids", List.of());
        List<Long> bookIds = rawIds.stream().map(Number::longValue).toList();
        int limit = toInt(body.get("limit"), 100);

        if (question.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供问题"));
        }
        if (bookIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择小说"));
        }

        Map<String, Object> result = analysisService.agentAnalyze(question, bookIds, limit);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(500).body(result);
    }

    /** /api/agent/tools 工具列表 */
    @GetMapping("/api/agent/tools")
    @ResponseBody
    public Map<String, Object> agentToolsList() {
        return analysisService.agentToolsList();
    }

    /** /api/export-reviews 导出评论数据 */
    @GetMapping("/api/export-reviews")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> exportReviews(
            @RequestParam(required = false) Long book_id,
            @RequestParam(defaultValue = "500") int limit) {
        if (book_id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请指定书籍ID"));
        }
        return ResponseEntity.ok(analysisService.exportReviews(book_id, limit));
    }

    /** /api/book-reviews 指定书籍的评论列表 */
    @GetMapping("/api/book-reviews")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bookReviews(@RequestParam("book_id") Long bookId) {
        Map<String, Object> result = analysisService.getBookReviews(bookId);
        if ("书籍不存在".equals(result.get("error"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** /api/ai/understand AI 理解用户意图 */
    @PostMapping("/api/ai/understand")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> aiUnderstand(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的JSON数据"));
        }
        String question = body.get("question") != null ? String.valueOf(body.get("question")) : "";
        // 多选支持：优先取 book_ids 数组，兼容旧字段 book_id（严格按当前勾选书籍回答）
        List<Long> bookIds = new ArrayList<>();
        Object rawIds = body.get("book_ids");
        if (rawIds instanceof List<?> idList) {
            for (Object o : idList) {
                Long v = toLong(o);
                if (v != null) {
                    bookIds.add(v);
                }
            }
        }
        if (bookIds.isEmpty()) {
            Long bookId = toLong(body.get("book_id"));
            if (bookId != null) {
                bookIds.add(bookId);
            }
        }
        Map<String, Object> result = analysisService.aiUnderstand(question, bookIds);
        if ("书籍不存在".equals(result.get("error"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** /api/export-report 导出分析报告 */
    @PostMapping("/api/export-report")
    @ResponseBody
    public ResponseEntity<byte[]> exportReport(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> bookInfo = (Map<String, Object>) body.getOrDefault("book_info", Map.of());

        String html = analysisService.generateReportHtml(messages, bookInfo);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"book-analysis-report.html\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /** /api/catalog 书籍分类目录（销量/好评排行，无需登录） */
    @GetMapping("/api/catalog")
    @ResponseBody
    public Map<String, Object> catalog() {
        return analysisService.getCatalog();
    }

    /** /api/recommend-chat 智能荐书对话（无需登录） */
    @PostMapping("/api/recommend-chat")
    @ResponseBody
    public Map<String, Object> recommendChat(@RequestBody(required = false) Map<String, String> body) {
        String pref = body != null ? body.getOrDefault("preference", "") : "";
        return analysisService.recommendChat(pref);
    }

    /** /api/submit-review 用户投稿书评（AI 自动打分，需登录） */
    @PostMapping("/api/submit-review")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitReview(@RequestBody(required = false) Map<String, Object> body,
                                                            HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "message", "请先登录后再写书评"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "无效的JSON数据"));
        }
        Long bookId = toLong(body.get("book_id"));
        String content = body.get("content") != null ? String.valueOf(body.get("content")).trim() : "";
        if (bookId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "请选择书籍"));
        }
        if (content.length() < 10) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "书评内容至少 10 个字"));
        }

        Map<String, Object> result = analysisService.submitReview(
                ((Number) uid).longValue(),
                String.valueOf(session.getAttribute("nickname")),
                bookId, content, body.get("rating"));
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(500).body(result);
    }

    /** /api/my-reviews 我的书评列表（需登录） */
    @GetMapping("/api/my-reviews")
    @ResponseBody
    public Map<String, Object> myReviews(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "reviews", List.of());
        }
        return analysisService.getMyReviews(((Number) uid).longValue());
    }

    /** /api/my-reviews/{id} 删除我的书评（需登录，校验归属） */
    @DeleteMapping("/api/my-reviews/{id}")
    @ResponseBody
    public Map<String, Object> deleteMyReview(@PathVariable Long id, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "error", "请先登录");
        }
        return analysisService.deleteMyReview(((Number) uid).longValue(), id);
    }

    /** /api/polish-review AI 书评润色（需登录） */
    @PostMapping("/api/polish-review")
    @ResponseBody
    public Map<String, Object> polishReview(@RequestBody(required = false) Map<String, Object> body,
                                            HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "message", "请先登录后再使用 AI 润色");
        }
        String content = body != null && body.get("content") != null ? String.valueOf(body.get("content")) : "";
        return analysisService.polishReview(content);
    }

    /** /api/favorite/{bookId} 收藏/取消收藏（切换，需登录） */
    @PostMapping("/api/favorite/{bookId}")
    @ResponseBody
    public Map<String, Object> toggleFavorite(@PathVariable Long bookId, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "message", "请先登录后再收藏书籍");
        }
        return analysisService.toggleFavorite(((Number) uid).longValue(), bookId);
    }

    /** /api/my-favorites 我的收藏书架（需登录） */
    @GetMapping("/api/my-favorites")
    @ResponseBody
    public Map<String, Object> myFavorites(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "favorites", List.of());
        }
        return analysisService.getMyFavorites(((Number) uid).longValue());
    }

    /** /api/user-badge 书评徽章等级（需登录，按书评数量升级） */
    @GetMapping("/api/user-badge")
    @ResponseBody
    public Map<String, Object> userBadge(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", true, "count", 0, "level", 0, "name", "");
        }
        return analysisService.getReviewBadge(((Number) uid).longValue());
    }

    /** /api/similar-books 相似书籍推荐（喜欢这本书的人也看，无需登录） */
    @GetMapping("/api/similar-books")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> similarBooks(@RequestParam Long book_id) {
        Map<String, Object> result = analysisService.getSimilarBooks(book_id);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(404).body(result);
    }

    /** /api/add-book 新增书籍（需登录，页面录入） */
    @PostMapping("/api/add-book")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addBook(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "请先登录后再添加书籍"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "无效的JSON数据"));
        }
        Map<String, Object> result = analysisService.addBook(body);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    /** /api/delete-book 删除书籍（需登录，联动清理评论与收藏） */
    @PostMapping("/api/delete-book")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteBook(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "请先登录后再删除书籍"));
        }
        Long bookId = toLong(body != null ? body.get("book_id") : null);
        if (bookId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "请指定书籍ID"));
        }
        Map<String, Object> result = analysisService.deleteBook(bookId);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(404).body(result);
    }

    /** /api/my-libraries 我的自定义书库（需登录） */
    @GetMapping("/api/my-libraries")
    @ResponseBody
    public Map<String, Object> myLibraries(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return Map.of("ok", false, "libraries", List.of());
        }
        return analysisService.myLibraries(((Number) uid).longValue());
    }

    /** /api/match-books 从文本智能匹配书籍（需登录） */
    @PostMapping("/api/match-books")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> matchBooks(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "请先登录"));
        }
        String text = body != null && body.get("text") != null ? String.valueOf(body.get("text")) : "";
        return ResponseEntity.ok(analysisService.matchBooks(text));
    }

    /** /api/create-library 创建自定义书库（需登录） */
    @PostMapping("/api/create-library")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createLibrary(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "请先登录"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "无效的JSON数据"));
        }
        Map<String, Object> result = analysisService.createLibrary(((Number) uid).longValue(), body);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    /** /api/delete-library 删除自定义书库（仅本人创建的，需登录） */
    @PostMapping("/api/delete-library")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteLibrary(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "请先登录"));
        }
        Long libId = body != null ? toLong(body.get("library_id")) : null;
        Map<String, Object> result = analysisService.deleteLibrary(((Number) uid).longValue(), libId);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Object o, int def) {
        Long v = toLong(o);
        return v != null ? v.intValue() : def;
    }
}
