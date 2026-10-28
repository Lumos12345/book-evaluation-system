package com.book.eval.service;

import com.book.eval.mapper.BookMapper;
import com.book.eval.mapper.FavoriteMapper;
import com.book.eval.mapper.LibraryMapper;
import com.book.eval.mapper.ReviewMapper;
import com.book.eval.mapper.UserReviewMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 评论分析服务 —— 移植自参考项目 views.py 的分析接口
 */
@Service
public class AnalysisService implements org.springframework.boot.ApplicationRunner {

    private final BookMapper bookMapper;
    private final ReviewMapper reviewMapper;
    private final UserReviewMapper userReviewMapper;
    private final FavoriteMapper favoriteMapper;
    private final LibraryMapper libraryMapper;
    private final LlmService llmService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisService(BookMapper bookMapper, ReviewMapper reviewMapper,
                           UserReviewMapper userReviewMapper, FavoriteMapper favoriteMapper,
                           LibraryMapper libraryMapper, LlmService llmService, AgentService agentService) {
        this.bookMapper = bookMapper;
        this.reviewMapper = reviewMapper;
        this.userReviewMapper = userReviewMapper;
        this.favoriteMapper = favoriteMapper;
        this.libraryMapper = libraryMapper;
        this.llmService = llmService;
        this.agentService = agentService;
    }

    /** ============ AI 书评润色 ============ */

    /** 润色用户书评草稿：qwen-plus 优化文字，保留原意；AI 不可用时降级为简单去重排版 */
    public Map<String, Object> polishReview(String content) {
        Map<String, Object> result = new HashMap<>();
        if (content == null || content.trim().length() < 5) {
            result.put("ok", false);
            result.put("error", "书评内容太短，先写几句再润色吧");
            return result;
        }
        String prompt = "你是一位文风自然真诚的书评润色助手。请润色下面这段读者书评草稿：\n"
                + "1. 保留原意和真实的口语感，不要过度堆砌华丽辞藻\n"
                + "2. 修正语病、错别字，让句子更流畅\n"
                + "3. 长度与原文相近（可略微丰富），不要添加原文没有的情节\n"
                + "4. 只输出润色后的书评正文，不要任何解释、引号或前缀\n\n"
                + "书评草稿：" + content.trim();
        String polished = llmService.call(prompt, 0.6, null);
        if (polished == null || polished.isBlank()) {
            // 降级：AI 不可用时做最简单的整理（合并多余空白与标点）
            String simple = content.trim().replaceAll("\\s{2,}", " ");
            result.put("ok", true);
            result.put("polished", simple);
            result.put("fallback", true);
            return result;
        }
        result.put("ok", true);
        result.put("polished", polished.trim());
        return result;
    }

    /** ============ 收藏书架 ============ */

    /** 收藏 / 取消收藏（已收藏则取消，未收藏则加入） */
    public Map<String, Object> toggleFavorite(Long userId, Long bookId) {
        Map<String, Object> result = new HashMap<>();
        if (bookMapper.selectBookBasic(bookId) == null) {
            result.put("ok", false);
            result.put("error", "书籍不存在");
            return result;
        }
        if (favoriteMapper.exists(userId, bookId) > 0) {
            favoriteMapper.delete(userId, bookId);
            result.put("ok", true);
            result.put("favorited", false);
            result.put("message", "已取消收藏");
        } else {
            favoriteMapper.insert(userId, bookId);
            result.put("ok", true);
            result.put("favorited", true);
            result.put("message", "已加入书架");
        }
        return result;
    }

    /** 我的收藏书架 */
    public Map<String, Object> getMyFavorites(Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("favorites", favoriteMapper.listByUser(userId));
        return result;
    }

    /** 是否已收藏某书 */
    public boolean isFavorited(Long userId, Long bookId) {
        return favoriteMapper.exists(userId, bookId) > 0;
    }

    /** ============ 书评徽章等级 ============ */

    /** 按书评数量计算徽章等级：Lv1 书虫(1篇) → Lv5 大师(20篇) */
    public Map<String, Object> getReviewBadge(Long userId) {
        Map<String, Object> result = new HashMap<>();
        long count = userReviewMapper.countByUser(userId);
        String[] names = {"", "书虫", "书迷", "书评人", "书评达人", "大师"};
        int[] thresholds = {0, 1, 3, 5, 10, 20};
        int level = 0;
        for (int i = 5; i >= 1; i--) {
            if (count >= thresholds[i]) { level = i; break; }
        }
        result.put("ok", true);
        result.put("count", count);
        result.put("level", level);
        result.put("name", names[level]);
        if (level < 5) {
            result.put("next", names[level + 1]);
            result.put("nextCount", thresholds[level + 1]);
        }
        return result;
    }

    /** ============ 相似书籍推荐 ============ */

    /** 喜欢这本书的人也看：同类型加分 + 评论关键词重合度 */
    public Map<String, Object> getSimilarBooks(Long bookId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> base = bookMapper.selectBookBasic(bookId);
        if (base == null) {
            result.put("ok", false);
            result.put("error", "书籍不存在");
            return result;
        }
        String baseGenre = base.get("genre") != null ? String.valueOf(base.get("genre")) : "";
        // 每本书的评论关键词集合
        Map<Long, Set<String>> kwMap = new HashMap<>();
        for (Map<String, Object> row : reviewMapper.selectAllKeywords()) {
            Long bid = ((Number) row.get("book_id")).longValue();
            Set<String> set = kwMap.computeIfAbsent(bid, k -> new HashSet<>());
            Object kw = row.get("keywords");
            if (kw != null) {
                for (String s : String.valueOf(kw).split("[,，、]")) {
                    String t = s.trim();
                    if (!t.isEmpty()) set.add(t);
                }
            }
        }
        Set<String> baseKw = kwMap.getOrDefault(bookId, new HashSet<>());
        List<Map<String, Object>> sims = new ArrayList<>();
        for (Map<String, Object> b : bookMapper.selectBooksWithStats()) {
            Long bid = ((Number) b.get("id")).longValue();
            if (bid.equals(bookId)) continue;
            Set<String> kws = kwMap.getOrDefault(bid, new HashSet<>());
            long overlap = baseKw.stream().filter(kws::contains).count();
            boolean sameGenre = !baseGenre.isEmpty() && baseGenre.equals(String.valueOf(b.get("genre")));
            if (!sameGenre && overlap == 0) continue;
            int score = (sameGenre ? 2 : 0) + (int) Math.min(overlap, 5);
            Map<String, Object> sim = new LinkedHashMap<>();
            sim.put("id", bid);
            sim.put("title", b.get("title"));
            sim.put("author", b.get("author"));
            sim.put("genre", b.get("genre"));
            sim.put("total_rating", b.get("total_rating"));
            sim.put("review_count", b.get("review_count"));
            sim.put("score", score);
            sim.put("reason", (sameGenre ? "同类型" : "兴趣相似") + (overlap > 0 ? " · " + overlap + " 个共同话题" : ""));
            sims.add(sim);
        }
        sims.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Integer) m.get("score"))));
        result.put("ok", true);
        result.put("books", sims.subList(0, Math.min(4, sims.size())));
        return result;
    }

    /** ============ 页面添加书籍 ============ */

    /** 录入新书：入库后返回书籍信息，目录即时刷新 */
    public Map<String, Object> addBook(Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
        if (title.isEmpty()) {
            result.put("ok", false);
            result.put("error", "书名不能为空");
            return result;
        }
        String author = body.get("author") != null ? String.valueOf(body.get("author")).trim() : "";
        String genre = body.get("genre") != null ? String.valueOf(body.get("genre")).trim() : "";
        String desc = body.get("description") != null ? String.valueOf(body.get("description")).trim() : null;
        double rating = 8.0;
        double price = 0;
        int sales = 0;
        try {
            if (body.get("total_rating") != null && !String.valueOf(body.get("total_rating")).isEmpty()) {
                rating = Double.parseDouble(String.valueOf(body.get("total_rating")));
            }
            if (body.get("total_sales") != null && !String.valueOf(body.get("total_sales")).isEmpty()) {
                sales = Integer.parseInt(String.valueOf(body.get("total_sales")));
            }
        } catch (NumberFormatException e) {
            result.put("ok", false);
            result.put("error", "评分或销量格式不正确");
            return result;
        }
        rating = Math.max(0, Math.min(10, rating));
        sales = Math.max(0, sales);
        bookMapper.insertBook(title, author, genre.isEmpty() ? "未分类" : genre, sales, rating, price, desc);
        Long newId = bookMapper.selectLatestId();
        result.put("ok", true);
        result.put("book", bookMapper.selectBookBasic(newId));
        return result;
    }

    /** ============ 删除书籍 ============ */

    /** 删除书籍：连同其种子评论与收藏记录一并清理（事务保证一致性，用户书评保留书名快照） */
    @Transactional
    public Map<String, Object> deleteBook(Long bookId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> book = bookMapper.selectBookBasic(bookId);
        if (book == null) {
            result.put("ok", false);
            result.put("error", "书籍不存在或已被删除");
            return result;
        }
        reviewMapper.deleteByBookId(bookId);
        favoriteMapper.deleteByBookId(bookId);
        bookMapper.deleteBook(bookId);
        result.put("ok", true);
        result.put("title", book.get("title"));
        return result;
    }

    /** ============ 书库系统 ============ */

    /** 从文本智能匹配书籍：标题包含匹配库内书籍；库外书名由 LLM 自动建档入库（AI 不可用时仅返回已有匹配） */
    public Map<String, Object> matchBooks(String text) {
        Map<String, Object> result = new HashMap<>();
        if (text == null || normalize(text).length() < 2) {
            result.put("ok", false);
            result.put("error", "请输入包含书名的文字");
            return result;
        }
        String norm = normalize(text);
        List<Map<String, Object>> matched = new ArrayList<>();
        Set<String> hitTitles = new HashSet<>();
        List<Map<String, Object>> allBooks = bookMapper.selectBooksWithStats();
        for (Map<String, Object> b : allBooks) {
            String title = String.valueOf(b.get("title"));
            String nt = normalize(title);
            if (nt.length() < 2 || !norm.contains(nt)) continue;
            hitTitles.add(nt);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.get("id"));
            m.put("title", title);
            m.put("author", b.get("author"));
            m.put("genre", b.get("genre"));
            m.put("total_rating", b.get("total_rating"));
            Map<String, Object> info = bookMapper.selectBookInfo(((Number) b.get("id")).longValue());
            if (info != null) m.put("description", info.get("description"));
            matched.add(m);
        }
        autoCreateBooks(text, allBooks, hitTitles, matched);
        result.put("ok", true);
        result.put("books", matched);
        return result;
    }

    /** 库外书名自动建档：LLM 抽取书名 → 已入库的跳过 → LLM 生成档案 → 插入 books 表 */
    private void autoCreateBooks(String text, List<Map<String, Object>> allBooks,
                                 Set<String> hitTitles, List<Map<String, Object>> matched) {
        try {
            List<String> mentioned = extractMentionedTitles(text);
            List<String> toCreate = new ArrayList<>();
            for (String t : mentioned) {
                String nt = normalize(t);
                if (nt.length() < 2 || hitTitles.contains(nt)) continue;
                boolean exists = false;
                for (Map<String, Object> b : allBooks) {
                    String bt = normalize(String.valueOf(b.get("title")));
                    if (bt.length() >= 2 && (bt.equals(nt) || nt.contains(bt) || bt.contains(nt))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists && !toCreate.contains(t)) toCreate.add(t);
            }
            if (toCreate.isEmpty()) return;
            System.out.println("📚 书库自动建档：" + toCreate);
            for (Map<String, Object> p : createBookProfiles(toCreate)) {
                String title = String.valueOf(p.get("title"));
                if (title.isBlank()) continue;
                String nt = normalize(title);
                if (hitTitles.contains(nt)) continue;
                String author = String.valueOf(p.get("author"));
                String genre = String.valueOf(p.get("genre"));
                String desc = String.valueOf(p.get("description"));
                double rating = ((Number) p.get("rating")).doubleValue();
                int sales = ((Number) p.get("sales")).intValue();
                rating = Math.max(0, Math.min(10, rating));
                sales = Math.max(0, sales);
                bookMapper.insertBook(title,
                        author.isBlank() ? "佚名" : author,
                        genre.isBlank() ? "未分类" : genre,
                        sales, rating, 0, desc.isBlank() ? null : desc);
                Long newId = bookMapper.selectLatestId();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", newId);
                m.put("title", title);
                m.put("author", author.isBlank() ? "佚名" : author);
                m.put("genre", genre.isBlank() ? "未分类" : genre);
                m.put("total_rating", rating);
                m.put("description", desc.isBlank() ? null : desc);
                m.put("is_new", true);
                matched.add(m);
                hitTitles.add(nt);
                try {
                    // 新书同步生成一批真实感评论，导入后即可查看/分析
                    generateReviewsForBook(newId, title,
                            author.isBlank() ? "佚名" : author,
                            genre.isBlank() ? "未分类" : genre,
                            desc.isBlank() ? null : desc);
                } catch (Exception ex) {
                    System.out.println("⚠️ 《" + title + "》评论生成失败: " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 书库自动建档失败（不影响已有匹配）: " + e.getMessage());
        }
    }

    /** LLM 抽取文本中提到的书名；失败时兜底用书名号《》提取 */
    private List<String> extractMentionedTitles(String text) {
        List<String> titles = new ArrayList<>();
        String raw = llmService.call("从下面这段文字中抽取出现的所有图书书名，只输出 JSON 字符串数组，"
                + "例如 [\"三体\",\"活着\"]，不要输出任何其他内容。文字：" + text, 0.2, "");
        if (raw != null && !raw.isBlank()) {
            JsonNode arr = readJsonArray(raw);
            if (arr != null) {
                for (JsonNode n : arr) {
                    String t = n.asText().trim();
                    if (!t.isEmpty()) titles.add(t);
                }
            }
        }
        if (titles.isEmpty()) {
            Matcher mm = Pattern.compile("《([^《》]{2,30})》").matcher(text);
            while (mm.find()) titles.add(mm.group(1).trim());
        }
        return titles;
    }

    /** LLM 为书名批量生成真实书籍档案（AI 不可用时返回空列表，跳过建档） */
    private List<Map<String, Object>> createBookProfiles(List<String> titles) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        if (titles.isEmpty()) return profiles;
        String prompt = "请为下面这些图书各生成一份档案，只输出 JSON 数组，不要输出其他内容。每个元素格式："
                + "{\"title\":\"书名\",\"author\":\"作者\",\"genre\":\"类型（文学/科幻/历史/悬疑/童话等）\","
                + "\"description\":\"50字以内简介\",\"rating\":8.5,\"sales\":100000}。"
                + "必须是真实存在的图书，信息不确定时给出合理估计。图书列表：" + String.join("、", titles);
        String raw = llmService.call(prompt, 0.3, "");
        if (raw == null || raw.isBlank()) return profiles;
        JsonNode arr = readJsonArray(raw);
        if (arr == null) return profiles;
        for (JsonNode n : arr) {
            String title = n.path("title").asText("").trim();
            if (title.isEmpty()) continue;
            Map<String, Object> p = new HashMap<>();
            p.put("title", title);
            p.put("author", n.path("author").asText("").trim());
            p.put("genre", n.path("genre").asText("").trim());
            p.put("description", n.path("description").asText("").trim());
            p.put("rating", n.path("rating").asDouble(8.0));
            p.put("sales", n.path("sales").asInt(0));
            profiles.add(p);
        }
        return profiles;
    }

    /** 从 LLM 输出中提取 JSON 数组（容忍代码围栏与前后杂文字），失败返回 null */
    private JsonNode readJsonArray(String raw) {
        try {
            String json = raw.trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) return null;
            return objectMapper.readTree(json.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    /** LLM 为书籍生成一批真实感中文评论并入库（AI 不可用返回 false，下次启动自动重试） */
    private boolean generateReviewsForBook(Long bookId, String title, String author, String genre, String desc) {
        String prompt = "请为图书《" + title + "》（作者：" + author + "，类型：" + genre
                + (desc == null || desc.isBlank() ? "" : "，简介：" + desc)
                + "）生成6条真实的中文读者短评，模仿豆瓣/知乎/微信读书的口吻，内容要具体（可提及情节、人物、文笔、阅读感受），长短不一，不要套模板。"
                + "只输出 JSON 数组，不要输出任何其他内容，每个元素格式："
                + "{\"content\":\"评论内容\",\"source\":\"豆瓣、知乎、微信读书 之一\",\"rating\":4.5,"
                + "\"sentiment\":\"正面|中性|负面\",\"sentiment_score\":0.6,\"keywords\":\"关键词1,关键词2\","
                + "\"author\":\"中文网名\",\"like_count\":123,\"reply_count\":45}。"
                + "评分与情感要有多样性：多数正面(4~5星)，少量中性(3星)和负面(1~2星)，sentiment 必须与评分一致。";
        String raw = llmService.call(prompt, 0.8, "");
        if (raw == null || raw.isBlank()) return false;
        JsonNode arr = readJsonArray(raw);
        if (arr == null || !arr.isArray() || arr.isEmpty()) return false;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int inserted = 0;
        for (JsonNode n : arr) {
            String content = n.path("content").asText("").trim();
            if (content.isEmpty()) continue;
            double rating = Math.max(1, Math.min(5, n.path("rating").asDouble(4.0)));
            String sentiment = n.path("sentiment").asText("").trim();
            if (!"正面".equals(sentiment) && !"中性".equals(sentiment) && !"负面".equals(sentiment)) {
                sentiment = rating >= 4 ? "正面" : rating >= 3 ? "中性" : "负面";
            }
            double score = Math.max(-1, Math.min(1, n.path("sentiment_score").asDouble(
                    rating >= 4 ? 0.6 : rating >= 3 ? 0.05 : -0.5)));
            // 情感得分方向与标签保持一致（LLM 偶尔给出反号分数，会干扰情感图表）
            if ("负面".equals(sentiment) && score > 0) score = -score;
            if ("正面".equals(sentiment) && score < 0) score = -score;
            String source = n.path("source").asText("豆瓣").trim();
            String keywords = n.path("keywords").asText("").trim();
            String reviewer = n.path("author").asText("匿名读者").trim();
            int likes = Math.max(0, n.path("like_count").asInt(50));
            int replies = Math.max(0, n.path("reply_count").asInt(10));
            String createdAt = LocalDateTime.now()
                    .minusMinutes(ThreadLocalRandom.current().nextInt(1, 60 * 24 * 90))
                    .format(fmt);
            reviewMapper.insertReview(bookId, content, source, rating, sentiment, score,
                    keywords.isEmpty() ? null : keywords, reviewer, likes, replies, createdAt);
            inserted++;
        }
        System.out.println("💬 《" + title + "》已生成 " + inserted + " 条 AI 评论");
        return inserted > 0;
    }

    /** 应用就绪后：后台为所有没有评论的书籍自动补生成 AI 评论（新建档/手动添加的书自动覆盖） */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try {
                List<Map<String, Object>> pending = reviewMapper.selectBooksWithoutReviews();
                if (pending.isEmpty()) return;
                System.out.println("💬 检测到 " + pending.size() + " 本书没有评论，开始自动补生成…");
                int done = 0;
                for (Map<String, Object> b : pending) {
                    try {
                        Long bid = ((Number) b.get("id")).longValue();
                        String title = String.valueOf(b.get("title"));
                        String desc = b.get("description") != null ? String.valueOf(b.get("description")) : null;
                        if (generateReviewsForBook(bid, title, String.valueOf(b.get("author")),
                                String.valueOf(b.get("genre")), desc)) {
                            done++;
                        }
                    } catch (Exception e) {
                        System.out.println("⚠️ 单本评论补生成失败: " + e.getMessage());
                    }
                }
                System.out.println("✅ 评论自动补齐完成：" + done + "/" + pending.size() + " 本");
            } catch (Exception e) {
                System.out.println("⚠️ 评论补齐任务异常: " + e.getMessage());
            }
        }, "review-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** 归一化：仅保留字母与数字（含中文）并转小写 */
    private String normalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 创建自定义书库：直接传 book_ids，或传 text 由系统自动匹配 */
    public Map<String, Object> createLibrary(Long userId, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String name = body.get("name") != null ? String.valueOf(body.get("name")).trim() : "";
        if (name.isEmpty()) {
            result.put("ok", false);
            result.put("error", "请给书库起个名字");
            return result;
        }
        if (libraryMapper.existsByName(userId, name) > 0) {
            result.put("ok", false);
            result.put("error", "已存在同名书库");
            return result;
        }
        Set<Long> ids = new LinkedHashSet<>();
        Object raw = body.get("book_ids");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Long v = null;
                if (o instanceof Number n) v = n.longValue();
                else {
                    try { v = Long.parseLong(String.valueOf(o).trim()); } catch (NumberFormatException ignored) { }
                }
                if (v != null) ids.add(v);
            }
        }
        if (ids.isEmpty() && body.get("text") != null && !String.valueOf(body.get("text")).isBlank()) {
            Map<String, Object> m = matchBooks(String.valueOf(body.get("text")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> books = (List<Map<String, Object>>) m.get("books");
            for (Map<String, Object> b : books) ids.add(((Number) b.get("id")).longValue());
        }
        if (ids.isEmpty()) {
            result.put("ok", false);
            result.put("error", "没有匹配到任何书籍，换段文字试试");
            return result;
        }
        libraryMapper.insert(userId, name, ids.toString());
        result.put("ok", true);
        result.put("count", ids.size());
        result.put("library_id", libraryMapper.selectLatestId());
        return result;
    }

    /** 删除当前用户创建的自定义书库（带归属校验，仅能删除自己的） */
    public Map<String, Object> deleteLibrary(Long userId, Long libId) {
        Map<String, Object> result = new HashMap<>();
        if (libId == null) {
            result.put("ok", false);
            result.put("error", "请指定要删除的书库");
            return result;
        }
        int rows = libraryMapper.deleteById(libId, userId);
        if (rows > 0) {
            result.put("ok", true);
        } else {
            result.put("ok", false);
            result.put("error", "书库不存在或无权删除");
        }
        return result;
    }

    /** 我的自定义书库列表（含书籍数量） */
    public Map<String, Object> myLibraries(Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        List<Map<String, Object>> libs = libraryMapper.listByUser(userId);
        for (Map<String, Object> lib : libs) {
            Object idsRaw = lib.get("bookIds");
            int count = 0;
            if (idsRaw != null) {
                String ids = String.valueOf(idsRaw).replaceAll("[^0-9,]", "");
                count = ids.isEmpty() ? 0 : ids.split(",").length;
            }
            lib.put("count", count);
        }
        result.put("libraries", libs);
        return result;
    }

    // ============================================================
    // 用户投稿书评：AI 自动打分
    // ============================================================

    private static final String REVIEW_ANALYSIS_PROMPT = """
            你是一位书籍评论分析专家。请对下面这条用户书评进行分析，严格只返回 JSON，不要输出任何其他文字：
            {"sentiment":"正面/中性/负面 三选一","sentiment_score":0.0到1.0之间的情感得分,"keywords":"3-5个关键词,用逗号分隔","comment":"一句话点评,30字以内"}

            书评内容：%s
            """;

    /** 提交书评：AI 分析情感/得分/关键词/点评 → 入库 → 返回分析结果 */
    public Map<String, Object> submitReview(Long userId, String nickname, Long bookId, String content, Object rating) {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> book = bookMapper.selectBookBasic(bookId);
        if (book == null) {
            resp.put("ok", false);
            resp.put("message", "书籍不存在");
            return resp;
        }

        // AI 分析（无 Key 或调用失败时降级为关键词规则打分）
        Map<String, Object> analysis = analyzeReviewWithAi(content);

        // 用户评分：仅接受 1-5
        Object finalRating = null;
        if (rating instanceof Number n && n.doubleValue() >= 1 && n.doubleValue() <= 5) {
            finalRating = n.doubleValue();
        }

        userReviewMapper.insert(userId, nickname, bookId,
                String.valueOf(book.get("title")), content, finalRating,
                (String) analysis.get("sentiment"), analysis.get("sentiment_score"),
                (String) analysis.get("keywords"), (String) analysis.get("comment"));

        resp.put("ok", true);
        resp.put("review", analysis);
        return resp;
    }

    /** 我的书评列表（按用户隔离） */
    public Map<String, Object> getMyReviews(Long userId) {
        return Map.of("ok", true, "reviews", userReviewMapper.selectByUserId(userId));
    }

    /** 删除我的书评（校验归属，防止越权删除他人书评） */
    public Map<String, Object> deleteMyReview(Long userId, Long id) {
        int rows = userReviewMapper.deleteByIdAndUser(id, userId);
        if (rows == 0) {
            return Map.of("ok", false, "error", "书评不存在或无权删除");
        }
        return Map.of("ok", true);
    }

    // ============================================================
    // /api/catalog 书籍分类目录（销量/好评排行）
    // ============================================================

    /** 分类目录：全部书籍 + 好评率（正面评论占比） */
    public Map<String, Object> getCatalog() {
        Map<Long, long[]> senti = new HashMap<>(); // [正面, 中性, 负面]
        for (Map<String, Object> row : reviewMapper.selectSentimentByBook()) {
            Long bookId = toDouble(row.get("book_id"), 0.0).longValue();
            String s = String.valueOf(row.get("sentiment"));
            long c = toDouble(row.get("count"), 0.0).longValue();
            long[] arr = senti.computeIfAbsent(bookId, k -> new long[3]);
            int idx = "正面".equals(s) ? 0 : "负面".equals(s) ? 2 : 1;
            arr[idx] += c;
        }
        List<Map<String, Object>> books = new ArrayList<>();
        for (Map<String, Object> b : bookMapper.selectBooksWithStats()) {
            Long bid = toDouble(b.get("id"), 0.0).longValue();
            long[] arr = senti.getOrDefault(bid, new long[3]);
            long total = arr[0] + arr[1] + arr[2];
            long goodRate = total > 0 ? Math.round(arr[0] * 100.0 / total) : 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bid);
            item.put("title", String.valueOf(b.get("title")));
            item.put("author", String.valueOf(b.get("author")));
            item.put("genre", String.valueOf(b.get("genre")));
            item.put("total_sales", toDouble(b.get("total_sales"), 0.0).longValue());
            item.put("avg_rating", toDouble(b.get("avg_rating"), 0.0));
            item.put("review_count", toDouble(b.get("review_count"), 0.0).longValue());
            item.put("good_rate", goodRate);
            books.add(item);
        }
        return Map.of("ok", true, "books", books);
    }

    // ============================================================
    // /api/recommend-chat 智能荐书对话
    // ============================================================

    private static final String RECO_PROMPT = """
            你是一位专业的荐书助手。下面是候选书籍数据（JSON 数组）与用户偏好，请挑选最符合偏好的 2-3 本书推荐。
            严格只返回 JSON，不要输出任何其他文字：
            {"opening":"一句话开场白,20字以内","picks":[{"title":"书名,必须从候选中选","reason":"结合数据与偏好的推荐理由,50字以内"}]}

            候选书籍：%s
            用户偏好：%s
            """;

    /** 智能荐书：结合评分/情感/类型，AI 挑选 2-3 本并给出理由 */
    public Map<String, Object> recommendChat(String preference) {
        Map<String, Object> resp = new HashMap<>();
        String pref = preference == null ? "" : preference.trim();
        if (pref.isEmpty()) {
            resp.put("ok", false);
            resp.put("message", "请描述你的阅读偏好");
            return resp;
        }
        List<Map<String, Object>> books = bookMapper.selectBooksWithStats();

        // 按书聚合情感计数
        Map<Long, Map<String, Long>> sentiByBook = new HashMap<>();
        for (Map<String, Object> row : reviewMapper.selectSentimentByBook()) {
            Long bookId = toDouble(row.get("book_id"), 0.0).longValue();
            sentiByBook.computeIfAbsent(bookId, k -> new LinkedHashMap<>())
                    .put(String.valueOf(row.get("sentiment")), toDouble(row.get("count"), 0.0).longValue());
        }

        // 组装候选数据喂给大模型
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> b : books) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("title", String.valueOf(b.get("title")));
            c.put("author", String.valueOf(b.get("author")));
            c.put("genre", String.valueOf(b.get("genre")));
            c.put("rating", toDouble(b.get("avg_rating"), 0.0));
            c.put("sales", toDouble(b.get("total_sales"), 0.0).longValue());
            c.put("reviews", toDouble(b.get("review_count"), 0.0).longValue());
            candidates.add(c);
        }
        String candJson;
        try {
            candJson = objectMapper.writeValueAsString(candidates);
        } catch (Exception e) {
            candJson = String.valueOf(candidates);
        }

        String opening;
        List<Map<String, String>> picks;
        String fallback = "抱歉，AI 荐书暂时不可用。";
        String raw = llmService.call(RECO_PROMPT.formatted(candJson, pref), 0.8, fallback);
        if (fallback.equals(raw)) {
            picks = ruleBasedPicks(books, pref);
            opening = "已按「" + pref + "」为你挑选了高评分书籍：";
        } else {
            try {
                String json = raw.trim();
                int s = json.indexOf('{');
                int e = json.lastIndexOf('}');
                if (s < 0 || e <= s) {
                    throw new IllegalStateException("非 JSON 响应");
                }
                JsonNode node = objectMapper.readTree(json.substring(s, e + 1));
                opening = node.path("opening").asText("为你推荐以下书籍：");
                picks = new ArrayList<>();
                for (JsonNode p : node.path("picks")) {
                    String t = p.path("title").asText("");
                    String r = p.path("reason").asText("");
                    if (!t.isEmpty() && !r.isEmpty()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("title", t);
                        m.put("reason", r);
                        picks.add(m);
                    }
                }
                if (picks.isEmpty()) {
                    throw new IllegalStateException("无有效推荐");
                }
            } catch (Exception ex) {
                picks = ruleBasedPicks(books, pref);
                opening = "为你推荐以下书籍：";
            }
        }

        // 用 title 反查完整数据组装返回
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> p : picks) {
            for (Map<String, Object> b : books) {
                if (String.valueOf(b.get("title")).equals(p.get("title"))) {
                    Long bid = toDouble(b.get("id"), 0.0).longValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("book_id", bid);
                    item.put("title", p.get("title"));
                    item.put("author", String.valueOf(b.get("author")));
                    item.put("genre", String.valueOf(b.get("genre")));
                    item.put("avg_rating", toDouble(b.get("avg_rating"), 0.0));
                    item.put("review_count", toDouble(b.get("review_count"), 0.0).longValue());
                    item.put("total_sales", toDouble(b.get("total_sales"), 0.0).longValue());
                    item.put("sentiment", sentiByBook.getOrDefault(bid, Map.of()));
                    item.put("reason", p.get("reason"));
                    result.add(item);
                    break;
                }
            }
        }
        resp.put("ok", true);
        resp.put("opening", opening);
        resp.put("picks", result);
        return resp;
    }

    /** 无大模型时的降级推荐：偏好里的类型词匹配 + 评分排序取前 2 */
    private List<Map<String, String>> ruleBasedPicks(List<Map<String, Object>> books, String pref) {
        String[] genres = {"科幻", "文学", "小说", "历史", "童话", "哲学", "推理", "言情", "武侠", "奇幻", "神话", "诗歌", "心理", "悬疑"};
        List<Map<String, Object>> pool = new ArrayList<>(books);
        for (String g : genres) {
            if (pref.contains(g)) {
                List<Map<String, Object>> matched = new ArrayList<>();
                for (Map<String, Object> b : books) {
                    if (String.valueOf(b.get("genre")).contains(g)) {
                        matched.add(b);
                    }
                }
                if (!matched.isEmpty()) {
                    pool = matched;
                    break;
                }
            }
        }
        pool.sort((a, b2) -> Double.compare(toDouble(b2.get("avg_rating"), 0.0), toDouble(a.get("avg_rating"), 0.0)));
        List<Map<String, String>> picks = new ArrayList<>();
        for (int i = 0; i < Math.min(2, pool.size()); i++) {
            Map<String, Object> b = pool.get(i);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("title", String.valueOf(b.get("title")));
            m.put("reason", "评分 " + toDouble(b.get("avg_rating"), 0.0) + "，评论 "
                    + toDouble(b.get("review_count"), 0.0).longValue() + " 条，销量 "
                    + toDouble(b.get("total_sales"), 0.0).longValue() + " 册，口碑与热度兼备");
            picks.add(m);
        }
        return picks;
    }

    /** 调用大模型分析书评；失败时按情感关键词规则降级打分 */
    private Map<String, Object> analyzeReviewWithAi(String content) {
        String fallback = "抱歉，AI 分析暂时不可用，已按规则保存您的书评。";
        String raw = llmService.call(REVIEW_ANALYSIS_PROMPT.formatted(content), 0.3, fallback);
        Map<String, Object> result = new HashMap<>();
        if (fallback.equals(raw)) {
            // 未配置 Key：关键词规则降级
            result.putAll(ruleBasedAnalysis(content));
            result.put("comment", fallback);
            return result;
        }
        try {
            String json = raw.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("非 JSON 响应");
            }
            JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
            String sentiment = node.path("sentiment").asText("中性");
            if (!List.of("正面", "中性", "负面").contains(sentiment)) {
                sentiment = "中性";
            }
            double score = node.path("sentiment_score").asDouble(0.5);
            score = Math.max(0.0, Math.min(1.0, score));
            result.put("sentiment", sentiment);
            result.put("sentiment_score", Math.round(score * 100) / 100.0);
            result.put("keywords", node.path("keywords").asText(""));
            result.put("comment", node.path("comment").asText(""));
            return result;
        } catch (Exception e) {
            result.putAll(ruleBasedAnalysis(content));
            result.put("comment", fallback);
            return result;
        }
    }

    /** 无大模型时的降级打分：情感词计数 */
    private Map<String, Object> ruleBasedAnalysis(String content) {
        String[] posWords = {"好看", "精彩", "喜欢", "推荐", "优秀", "感人", "深刻", "流畅", "震撼", "出色", "经典", "引人入胜", "受益", "启发", "治愈"};
        String[] negWords = {"难看", "无聊", "失望", "浪费", "拖沓", "差劲", "晦涩", "平庸", "无趣", "啰嗦", "生硬", "尴尬"};
        int pos = 0, neg = 0;
        for (String w : posWords) {
            if (content.contains(w)) pos++;
        }
        for (String w : negWords) {
            if (content.contains(w)) neg++;
        }
        String sentiment;
        double score;
        if (pos == 0 && neg == 0) {
            sentiment = "中性";
            score = 0.5;
        } else {
            double ratio = (double) pos / (pos + neg);
            sentiment = ratio > 0.6 ? "正面" : (ratio < 0.4 ? "负面" : "中性");
            score = Math.round((0.3 + 0.4 * ratio) * 100) / 100.0;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("sentiment", sentiment);
        result.put("sentiment_score", score);
        result.put("keywords", "暂无");
        return result;
    }

    // ============================================================
    // /api/books 书籍列表
    // ============================================================

    public Map<String, Object> getBooks() {
        List<Map<String, Object>> books = new ArrayList<>();
        for (Map<String, Object> row : bookMapper.selectBooksWithStats()) {
            Map<String, Object> book = new LinkedHashMap<>(row);
            book.put("total_rating", toDouble(row.get("total_rating"), 0.0));
            book.put("avg_rating", toDouble(row.get("avg_rating"), 0.0));
            books.add(book);
        }
        return Map.of("ok", true, "books", books);
    }

    // ============================================================
    // /api/book-detail 书籍详情
    // ============================================================

    public Map<String, Object> getBookDetail(Long bookId) {
        Map<String, Object> row = bookMapper.selectBookDetail(bookId);
        if (row == null) {
            return null;
        }
        Map<String, Object> book = new LinkedHashMap<>(row);
        for (String key : List.of("total_sales", "total_rating", "price", "avg_rating")) {
            if (book.containsKey(key) && book.get(key) != null) {
                book.put(key, toDouble(book.get(key), 0.0));
            }
        }
        return Map.of("ok", true, "book", book);
    }

    // ============================================================
    // /api/sales-ranking 销量排行
    // ============================================================

    public Map<String, Object> getSalesRanking() {
        List<Map<String, Object>> ranking = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> row : bookMapper.selectSalesRanking()) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("rank", idx++);
            item.put("total_rating", toDouble(row.get("total_rating"), 0.0));
            ranking.add(item);
        }
        return Map.of("ok", true, "ranking", ranking);
    }

    // ============================================================
    // /api/review-trend 评论趋势
    // ============================================================

    public Map<String, Object> getReviewTrend(Long bookId) {
        List<Map<String, Object>> rows = reviewMapper.selectTrendGroupByDate(bookId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", String.valueOf(r.get("date")));
            item.put("count", toLong(r.get("count"), 0L));
            item.put("avg_rating", toDouble(r.get("avg_rating"), 0.0));
            item.put("positive", toLong(r.get("positive"), 0L));
            item.put("negative", toLong(r.get("negative"), 0L));
            item.put("neutral", toLong(r.get("neutral"), 0L));
            data.add(item);
        }
        java.util.Collections.reverse(data);

        List<String> dates = data.stream().map(d -> String.valueOf(d.get("date"))).toList();
        List<Long> counts = data.stream().map(d -> toLong(d.get("count"), 0L)).toList();
        List<Double> avgRatings = data.stream().map(d -> toDouble(d.get("avg_rating"), 0.0)).toList();
        List<Long> positives = data.stream().map(d -> toLong(d.get("positive"), 0L)).toList();
        List<Long> negatives = data.stream().map(d -> toLong(d.get("negative"), 0L)).toList();

        Map<String, Object> chart = buildTrendChart(dates, counts, avgRatings, positives, negatives);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("data", data);
        resp.put("chart", chart);
        return resp;
    }

    /** 评论趋势图（与参考项目 review_trend 的 ECharts 配置一致） */
    private Map<String, Object> buildTrendChart(List<String> dates, List<Long> counts,
                                                List<Double> avgRatings, List<Long> positives, List<Long> negatives) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("tooltip", Map.of("trigger", "axis",
                "axisPointer", Map.of("type", "cross"),
                "backgroundColor", "rgba(255,255,255,0.9)",
                "borderColor", "#e0f2fe", "borderWidth", 1,
                "textStyle", Map.of("fontSize", 12)));
        chart.put("legend", Map.of("data", List.of("评论数量", "平均评分", "正面", "负面"),
                "top", 0, "left", "center", "textStyle", Map.of("fontSize", 11),
                "itemWidth", 14, "itemHeight", 10));
        chart.put("grid", Map.of("left", "6%", "right", "6%", "bottom", "15%", "top", "18%", "containLabel", true));
        chart.put("xAxis", Map.of("type", "category", "data", dates,
                "axisLabel", Map.of("rotate", dates.size() > 5 ? 30 : 0, "fontSize", 11,
                        "interval", 0, "margin", 8, "color", "#64748b"),
                "axisLine", Map.of("lineStyle", Map.of("color", "#e2e8f0")),
                "axisTick", Map.of("alignWithLabel", true)));
        chart.put("yAxis", List.of(
                Map.of("type", "value", "name", "数量",
                        "nameTextStyle", Map.of("fontSize", 11, "color", "#64748b"),
                        "splitLine", Map.of("lineStyle", Map.of("type", "dashed", "color", "#e2e8f0")),
                        "axisLabel", Map.of("fontSize", 10, "color", "#64748b"), "minInterval", 1),
                Map.of("type", "value", "name", "评分", "min", 0, "max", 5,
                        "nameTextStyle", Map.of("fontSize", 11, "color", "#64748b"),
                        "splitLine", Map.of("show", false),
                        "axisLabel", Map.of("fontSize", 10, "color", "#64748b"))));
        chart.put("series", List.of(
                Map.of("name", "评论数量", "type", "bar", "data", counts,
                        "itemStyle", Map.of("color", "#667eea", "borderRadius", List.of(4, 4, 0, 0)),
                        "barWidth", "35%", "barGap", "20%",
                        "label", Map.of("show", counts.size() <= 10, "position", "top",
                                "fontSize", 10, "color", "#64748b")),
                trendLineSeries(avgRatings),
                Map.of("name", "正面", "type", "line", "data", positives, "smooth", true,
                        "itemStyle", Map.of("color", "#52c41a"), "lineStyle", Map.of("width", 2),
                        "symbolSize", 6, "symbol", "diamond"),
                Map.of("name", "负面", "type", "line", "data", negatives, "smooth", true,
                        "itemStyle", Map.of("color", "#ff4d4f"), "lineStyle", Map.of("width", 2),
                        "symbolSize", 6, "symbol", "diamond")));
        chart.put("color", List.of("#667eea", "#38bdf8", "#52c41a", "#ff4d4f"));
        return chart;
    }

    /** 趋势图中的平均评分折线系列（参数过多拆分为 LinkedHashMap 构建） */
    private Map<String, Object> trendLineSeries(List<Double> avgRatings) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", "平均评分");
        s.put("type", "line");
        s.put("yAxisIndex", 1);
        s.put("data", avgRatings);
        s.put("smooth", true);
        s.put("itemStyle", Map.of("color", "#38bdf8"));
        s.put("lineStyle", Map.of("width", 2.5));
        s.put("symbolSize", 8);
        s.put("symbol", "circle");
        s.put("label", Map.of("show", avgRatings.size() <= 10, "formatter", "{c}",
                "fontSize", 10, "position", "top", "color", "#38bdf8"));
        s.put("areaStyle", Map.of("color", "rgba(56, 189, 248, 0.15)"));
        return s;
    }

    // ============================================================
    // /api/comment-sources 评论来源
    // ============================================================

    public Map<String, Object> getCommentSources() {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> row : bookMapper.selectCommentSources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("book_id", row.get("book_id"));
            item.put("source", row.get("source"));
            item.put("count", row.get("count"));
            item.put("avg_rating", round(toDouble(row.get("avg_rating"), 0.0), 2));
            sources.add(item);
        }
        return Map.of("ok", true, "sources", sources);
    }

    // ============================================================
    // /api/keyword-trend 关键词趋势
    // ============================================================

    public Map<String, Object> getKeywordTrend(Long bookId, String keyword) {
        List<Map<String, Object>> rows = reviewMapper.selectKeywordTrend(bookId, keyword);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            data.add(Map.of("date", String.valueOf(r.get("date")), "count", toLong(r.get("count"), 0L)));
        }
        java.util.Collections.reverse(data);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("keyword", keyword);
        resp.put("data", data);
        return resp;
    }

    // ============================================================
    // /api/time-analysis 时间维度分析
    // ============================================================

    public Map<String, Object> getTimeAnalysis(Long bookId) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : reviewMapper.selectTimeAnalysis(bookId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("year", toLong(r.get("year"), 0L));
            item.put("month", toLong(r.get("month"), 0L));
            item.put("count", toLong(r.get("count"), 0L));
            item.put("avg_rating", toDouble(r.get("avg_rating"), 0.0));
            item.put("positive_count", toLong(r.get("positive_count"), 0L));
            item.put("negative_count", toLong(r.get("negative_count"), 0L));
            data.add(item);
        }
        return Map.of("ok", true, "data", data);
    }

    // ============================================================
    // /api/analyze-comments 智能分析评论（按问题生成图表）
    // ============================================================

    public Map<String, Object> analyzeComments(Long bookId, String question, int limit) {
        Map<String, Object> bookRow = bookMapper.selectBookInfo(bookId);
        if (bookRow == null) {
            return error("书籍不存在");
        }
        Map<String, Object> bookInfo = new LinkedHashMap<>();
        bookInfo.put("id", bookRow.get("id"));
        bookInfo.put("title", bookRow.get("title"));
        bookInfo.put("author", bookRow.get("author"));
        bookInfo.put("genre", bookRow.get("genre"));
        bookInfo.put("total_sales", bookRow.get("total_sales"));
        bookInfo.put("total_rating", toDouble(bookRow.get("total_rating"), 0.0));
        bookInfo.put("description", bookRow.get("description") != null ? bookRow.get("description") : "");

        List<Map<String, Object>> comments = reviewMapper.selectFullByBookId(bookId, limit);
        if (comments.isEmpty()) {
            return error("该书籍暂无评论");
        }

        List<Map<String, Object>> charts = new ArrayList<>();
        String summary = "分析完成！";
        List<Map<String, Object>> tableData = null;
        String[] tableHeaders = null;
        List<Map<String, Object>> wordcloud = null;
        boolean hasChart = false;

        String q = question == null ? "" : question.toLowerCase().trim();

        long positive = comments.stream().filter(c -> "正面".equals(c.get("sentiment"))).count();
        long negative = comments.stream().filter(c -> "负面".equals(c.get("sentiment"))).count();
        long neutral = comments.stream().filter(c -> "中性".equals(c.get("sentiment"))).count();
        long total = comments.size();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("positive", positive);
        stats.put("negative", negative);
        stats.put("neutral", neutral);
        List<Double> ratings = comments.stream().map(c -> toDouble(c.get("rating"), null))
                .filter(r -> r != null && r > 0).collect(Collectors.toList());
        stats.put("avg_rating", ratings.isEmpty() ? 0 : round(avg(ratings), 2));

        // 1. 词云图
        if (containsAny(q, "词云", "关键词云", "wordcloud")) {
            wordcloud = buildWordFrequency(comments, 50);
            summary = "已生成评论词云图，展示高频关键词。";
            hasChart = true;
        }
        // 2. 情感得分分布图
        else if (containsAny(q, "情感得分", "得分分布", "情感分数", "得分")) {
            Map<String, Object> distChart = buildSentimentDistributionChart(comments);
            if (distChart != null) {
                charts.add(Map.of("title", "📊 情感得分分布", "option", distChart));
                summary = "显示情感得分分布情况";
                hasChart = true;
            }
        }
        // 3. 评论趋势折线图
        else if (containsAny(q, "趋势", "变化", "走势", "趋势图", "关键词趋势")) {
            Map<String, List<Double>> dateRatings = new LinkedHashMap<>();
            Map<String, long[]> dateCounts = new LinkedHashMap<>();
            for (Map<String, Object> c : comments) {
                if (c.get("created_at") != null) {
                    String date = String.valueOf(c.get("created_at")).substring(0, 10);
                    dateRatings.putIfAbsent(date, new ArrayList<>());
                    dateCounts.putIfAbsent(date, new long[]{0, 0, 0});
                    dateCounts.get(date)[0]++;
                    Double r = toDouble(c.get("rating"), null);
                    if (r != null && r > 0) dateRatings.get(date).add(r);
                    if ("正面".equals(c.get("sentiment"))) dateCounts.get(date)[1]++;
                    else if ("负面".equals(c.get("sentiment"))) dateCounts.get(date)[2]++;
                }
            }
            List<String> dates = dateCounts.keySet().stream().sorted().toList();
            int from = Math.max(0, dates.size() - 30);
            dates = dates.subList(from, dates.size());

            List<Long> counts = new ArrayList<>();
            List<Double> avgRatings = new ArrayList<>();
            List<Long> pos = new ArrayList<>();
            List<Long> neg = new ArrayList<>();
            for (String d : dates) {
                counts.add(dateCounts.get(d)[0]);
                avgRatings.add(dateRatings.get(d).isEmpty() ? 0 : round(avg(dateRatings.get(d)), 2));
                pos.add(dateCounts.get(d)[1]);
                neg.add(dateCounts.get(d)[2]);
            }
            charts.add(Map.of("title", "📈 评论趋势", "option",
                    buildTrendChart(dates, counts, avgRatings, pos, neg)));
            summary = "显示最近30天的评论趋势变化";
            hasChart = true;
        }
        // 4. 评分分布条形图
        else if (containsAny(q, "评分分布", "评分区间", "各评分", "评分")) {
            Map<String, Object> chart = buildRatingDistributionChart(comments);
            charts.add(Map.of("title", "⭐ 评分分布", "option", chart));
            double avg = ratings.isEmpty() ? 0 : round(avg(ratings), 1);
            summary = String.format("共 %d 条评论，平均评分 %s 星", total, avg);
            hasChart = true;
        }
        // 5. 关键词排行
        else if (containsAny(q, "关键词排行", "热词", "高频词", "排行", "关键词")) {
            List<Map.Entry<String, Long>> top = topKeywords(comments, 10);
            tableData = new ArrayList<>();
            int rank = 1;
            for (Map.Entry<String, Long> e : top) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("排名", rank++);
                row.put("关键词", e.getKey());
                row.put("出现次数", e.getValue());
                tableData.add(row);
            }
            tableHeaders = new String[]{"排名", "关键词", "出现次数"};
            charts.add(Map.of("title", "🏷️ 关键词排行 TOP 10", "option", buildKeywordChart(top)));
            if (!top.isEmpty()) {
                summary = String.format("关键词排行：%s(%d次)", top.get(0).getKey(), top.get(0).getValue());
            }
            hasChart = true;
        }
        // 6. 正面负面对比
        else if (containsAny(q, "正面负面", "正面对比", "负面对比", "对比")) {
            List<String> posWords = new ArrayList<>();
            List<String> negWords = new ArrayList<>();
            List<Map<String, Object>> posComments = comments.stream()
                    .filter(c -> "正面".equals(c.get("sentiment"))).limit(50).toList();
            List<Map<String, Object>> negComments = comments.stream()
                    .filter(c -> "负面".equals(c.get("sentiment"))).limit(50).toList();
            collectKeywords(posComments, posWords);
            collectKeywords(negComments, negWords);
            String posTop = topWordsJoined(posWords, 5);
            String negTop = topWordsJoined(negWords, 5);

            tableData = new ArrayList<>();
            tableData.add(Map.of("类型", "正面评论关键词", "关键词", posTop.isEmpty() ? "无" : posTop));
            tableData.add(Map.of("类型", "负面评论关键词", "关键词", negTop.isEmpty() ? "无" : negTop));
            tableHeaders = new String[]{"类型", "关键词"};
            summary = String.format("正面评论 %d 条，负面评论 %d 条", posComments.size(), negComments.size());

            charts.add(Map.of("title", "📊 正负面评论对比", "option",
                    buildComparisonChart(posComments.size(), negComments.size())));
            hasChart = true;
        }
        // 7. 情感分布饼图
        else if (containsAny(q, "情感分布", "情感比例", "饼图", "情绪分布", "情感")) {
            charts.add(Map.of("title", "📊 评论情感分布", "option",
                    buildSentimentPie(positive, neutral, negative)));
            summary = String.format("共 %d 条评论，正面 %d 条，中性 %d 条，负面 %d 条。", total, positive, neutral, negative);
            hasChart = true;
        }
        // 8. 默认
        if (!hasChart) {
            charts.add(Map.of("title", "📊 情感分布", "option",
                    buildSentimentPie(positive, neutral, negative)));
            wordcloud = buildWordFrequency(comments, 50);
            summary = String.format("共分析 %d 条评论，正面 %d 条，中性 %d 条，负面 %d 条", total, positive, neutral, negative);
        }

        // 评论列表（前50条）
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> c : comments.subList(0, Math.min(50, comments.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            String content = String.valueOf(c.get("content"));
            item.put("id", c.get("id"));
            item.put("content", content.length() > 100 ? content.substring(0, 100) + "..." : content);
            item.put("full_content", content);
            Double rating = toDouble(c.get("rating"), null);
            item.put("rating", (rating != null && rating > 0) ? rating : null);
            item.put("author", c.get("author") != null ? c.get("author") : "匿名");
            item.put("sentiment", c.get("sentiment") != null ? c.get("sentiment") : "中性");
            Double score = toDouble(c.get("sentiment_score"), null);
            item.put("sentiment_score", score != null ? score : 0);
            Object kw = c.get("keywords");
            item.put("keywords", kw != null && !String.valueOf(kw).isEmpty()
                    ? List.of(String.valueOf(kw).split(",")) : List.of());
            item.put("like_count", c.get("like_count") != null ? c.get("like_count") : 0);
            item.put("reply_count", c.get("reply_count") != null ? c.get("reply_count") : 0);
            results.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("book", bookInfo);
        resp.put("stats", stats);
        resp.put("summary", summary);
        resp.put("charts", charts);
        resp.put("wordcloud", wordcloud);
        resp.put("tableData", tableData);
        resp.put("tableHeaders", tableHeaders);
        resp.put("results", results);
        return resp;
    }

    // ============================================================
    // /api/compare-books 对比多本书籍（LLM 增强总结）
    // ============================================================

    public Map<String, Object> compareBooks(List<Long> bookIds, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long bookId : bookIds) {
            Map<String, Object> bookRow = bookMapper.selectBookBasic(bookId);
            if (bookRow == null) continue;

            List<Map<String, Object>> rows = reviewMapper.selectSentimentRows(bookId, limit);
            long positive = rows.stream().filter(r -> "正面".equals(r.get("sentiment"))).count();
            long negative = rows.stream().filter(r -> "负面".equals(r.get("sentiment"))).count();
            long neutral = rows.stream().filter(r -> "中性".equals(r.get("sentiment"))).count();
            List<Double> ratings = rows.stream().map(r -> toDouble(r.get("rating"), null))
                    .filter(x -> x != null && x > 0).collect(Collectors.toList());
            double avgRating = ratings.isEmpty() ? 0 : round(avg(ratings), 2);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bookRow.get("id"));
            item.put("title", bookRow.get("title"));
            item.put("author", bookRow.get("author"));
            item.put("genre", bookRow.get("genre"));
            item.put("total_sales", bookRow.get("total_sales"));
            item.put("total_rating", toDouble(bookRow.get("total_rating"), 0.0));
            item.put("review_count", rows.size());
            item.put("positive", positive);
            item.put("negative", negative);
            item.put("neutral", neutral);
            item.put("avg_rating", avgRating);
            results.add(item);
        }

        if (results.isEmpty()) {
            return error("未找到有效的书籍数据");
        }

        // 使用 LLM 生成对比分析文字总结（无 Key 时降级为统计描述）
        StringBuilder novelsData = new StringBuilder();
        for (Map<String, Object> r : results) {
            novelsData.append(String.format("《%s》- 作者:%s, 评分:%s星, 评论数:%s条, 正面:%s条, 负面:%s条%n",
                    r.get("title"), r.get("author"), r.get("avg_rating"),
                    r.get("review_count"), r.get("positive"), r.get("negative")));
        }
        String comparePrompt = Prompts.format(Prompts.COMPARE_PROMPT, Map.of(
                "novels_data", novelsData.toString(),
                "question", "请对比这" + results.size() + "本小说"));
        String llmSummary = llmService.call(comparePrompt, 0.3, "评论分析完成，请查看统计数据。");

        Map<String, Object> chartOption = new LinkedHashMap<>();
        chartOption.put("tooltip", Map.of("trigger", "axis"));
        chartOption.put("legend", Map.of("data", List.of("正面", "中性", "负面", "平均评分"),
                "top", 0, "textStyle", Map.of("fontSize", 11)));
        chartOption.put("grid", Map.of("left", "8%", "right", "5%", "bottom", "15%", "top", "18%", "containLabel", true));
        chartOption.put("xAxis", Map.of("type", "category",
                "data", results.stream().map(r -> r.get("title")).toList(),
                "axisLabel", Map.of("fontSize", 11)));
        chartOption.put("yAxis", List.of(
                Map.of("type", "value", "name", "评论数",
                        "nameTextStyle", Map.of("fontSize", 11),
                        "splitLine", Map.of("lineStyle", Map.of("type", "dashed"))),
                Map.of("type", "value", "name", "评分", "min", 0, "max", 5,
                        "nameTextStyle", Map.of("fontSize", 11),
                        "splitLine", Map.of("show", false))));
        chartOption.put("series", List.of(
                Map.of("name", "正面", "type", "bar",
                        "data", results.stream().map(r -> r.get("positive")).toList(),
                        "itemStyle", Map.of("color", "#52c41a"), "barWidth", "20%", "barGap", "10%"),
                Map.of("name", "中性", "type", "bar",
                        "data", results.stream().map(r -> r.get("neutral")).toList(),
                        "itemStyle", Map.of("color", "#faad14"), "barWidth", "20%", "barGap", "10%"),
                Map.of("name", "负面", "type", "bar",
                        "data", results.stream().map(r -> r.get("negative")).toList(),
                        "itemStyle", Map.of("color", "#ff4d4f"), "barWidth", "20%", "barGap", "10%"),
                Map.of("name", "平均评分", "type", "line", "yAxisIndex", 1,
                        "data", results.stream().map(r -> r.get("avg_rating")).toList(),
                        "smooth", true, "itemStyle", Map.of("color", "#667eea"),
                        "lineStyle", Map.of("width", 2.5), "symbolSize", 8, "symbol", "circle",
                        "areaStyle", Map.of("color", "rgba(102, 126, 234, 0.1)"))));
        chartOption.put("color", List.of("#52c41a", "#faad14", "#ff4d4f", "#667eea"));
        Map<String, Object> chart = Map.of("title", "📊 书籍对比分析", "option", chartOption);

        List<Map<String, Object>> tableData = new ArrayList<>();
        for (Map<String, Object> r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("书籍", r.get("title"));
            row.put("作者", r.get("author"));
            row.put("类型", r.get("genre") != null ? r.get("genre") : "未知");
            row.put("评论数", r.get("review_count"));
            row.put("正面", r.get("positive"));
            row.put("中性", r.get("neutral"));
            row.put("负面", r.get("negative"));
            row.put("平均评分", r.get("avg_rating"));
            row.put("销量", r.get("total_sales"));
            tableData.add(row);
        }
        String[] tableHeaders = {"书籍", "作者", "类型", "评论数", "正面", "中性", "负面", "平均评分", "销量"};

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", results.stream().mapToLong(r -> toLong(r.get("review_count"), 0L)).sum());
        stats.put("positive", results.stream().mapToLong(r -> toLong(r.get("positive"), 0L)).sum());
        stats.put("negative", results.stream().mapToLong(r -> toLong(r.get("negative"), 0L)).sum());
        stats.put("neutral", results.stream().mapToLong(r -> toLong(r.get("neutral"), 0L)).sum());
        stats.put("avg_rating", round(results.stream().mapToDouble(r -> toDouble(r.get("avg_rating"), 0.0)).sum() / results.size(), 2));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("results", results);
        resp.put("summary", llmSummary);
        resp.put("charts", List.of(chart));
        resp.put("tableData", tableData);
        resp.put("tableHeaders", tableHeaders);
        resp.put("stats", stats);
        return resp;
    }

    // ============================================================
    // /api/health 健康检查
    // ============================================================

    public Map<String, Object> health() {
        boolean dbOk;
        try {
            dbOk = bookMapper.ping() != null;
        } catch (Exception e) {
            dbOk = false;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", dbOk ? "ok" : "error");
        resp.put("database", dbOk);
        resp.put("timestamp", LocalDateTime.now().toString());
        return resp;
    }

    // ============================================================
    // /api/agent/* Agent 接口
    // ============================================================

    public Map<String, Object> agentAnalyze(String question, List<Long> bookIds, int limit) {
        Map<String, Object> result = agentService.analyze(question, bookIds, limit);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(result.get("success"))) {
            resp.put("ok", true);
            resp.put("answer", result.getOrDefault("answer", ""));
            resp.put("data", result.getOrDefault("data", Map.of()));
            resp.put("mode", result.getOrDefault("mode", "basic"));
            resp.put("book_ids", bookIds);
        } else {
            resp.put("ok", false);
            resp.put("error", result.getOrDefault("error", "分析失败"));
        }
        return resp;
    }

    public Map<String, Object> agentToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (AgentService.ToolDef t : agentService.getTools()) {
            tools.add(Map.of("name", t.name(), "description", t.description().strip()));
        }
        return Map.of("ok", true, "tools", tools, "langchain_available", false);
    }

    // ============================================================
    // /api/export-reviews 导出评论数据
    // ============================================================

    public Map<String, Object> exportReviews(Long bookId, int limit) {
        List<Map<String, Object>> reviews = reviewMapper.selectExportReviews(bookId, limit);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("reviews", reviews);
        resp.put("count", reviews.size());
        return resp;
    }

    // ============================================================
    // /api/book-reviews 指定书籍的评论列表（查看评论弹窗）
    // ============================================================

    public Map<String, Object> getBookReviews(Long bookId) {
        Map<String, Object> book = bookMapper.selectBookInfo(bookId);
        if (book == null) {
            return error("书籍不存在");
        }
        List<Map<String, Object>> reviews = reviewMapper.selectFullByBookId(bookId, 100);
        return Map.of("ok", true, "book", book, "reviews", reviews);
    }

    // ============================================================
    // /api/ai/understand AI 理解用户意图（LLM）
    // ============================================================

    /** 多书上下文的意图理解入口：严格基于当前勾选的全部书籍回答 */
    public Map<String, Object> aiUnderstand(String question, List<Long> bookIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (bookIds != null) {
            for (Long id : bookIds) {
                Map<String, Object> row = bookMapper.selectBookInfo(id);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        if (rows.size() > 1) {
            return multiBookUnderstand(question, rows);
        }
        Long singleId = rows.isEmpty() ? null : toDouble(rows.get(0).get("id"), 0.0).longValue();
        return aiUnderstand(question, singleId);
    }

    /** 多本书联合意图理解：QA/评价类一次性覆盖所有选中书籍（严格不涉及未选书籍），图表类交给前端逐书分析 */
    private Map<String, Object> multiBookUnderstand(String question, List<Map<String, Object>> bookRows) {
        // 意图识别（与单书一致：LLM → 关键词降级）
        String intentPrompt = Prompts.format(Prompts.INTENT_PROMPT, Map.of("question", question));
        String llmResponse = llmService.call(intentPrompt, 0.1, "{\"intent\": \"general\", \"keywords\": []}");
        String intentType;
        try {
            intentType = String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(llmResponse, Map.class).getOrDefault("intent", "general"));
        } catch (Exception e) {
            String q = question.toLowerCase();
            if (containsAny(q, "内容", "故事", "情节", "主角", "简介", "讲什么")) intentType = "content";
            else if (containsAny(q, "好看", "评价", "值得", "推荐", "怎么样")) intentType = "evaluation";
            else if (containsAny(q, "饼图", "词云", "趋势", "分布", "排行")) intentType = "chart";
            else if (containsAny(q, "对比", "比较", "对比分析")) intentType = "compare";
            else if (containsAny(q, "推荐", "哪本")) intentType = "recommend";
            else intentType = "general";
        }

        if ("chart".equals(intentType)) {
            return Map.of("ok", true, "type", "chart");
        }
        if ("compare".equals(intentType)) {
            return Map.of("ok", true, "type", "compare", "message", "已识别为对比分析请求，请调用对比接口");
        }

        // 每本书一行统计，供 LLM 与降级文案使用
        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> row : bookRows) {
            lines.append("- ").append(bookStatLine(row)).append("\n");
        }

        // 内容类：LLM 逐本生动介绍（无 LLM 降级为简介摘录）
        if ("content".equals(intentType)) {
            StringBuilder intros = new StringBuilder();
            for (Map<String, Object> row : bookRows) {
                String desc = row.get("description") == null ? "（暂无简介）" : String.valueOf(row.get("description"));
                intros.append(String.format("《%s》（%s）：%s%n%n", row.get("title"), row.get("author"), desc));
            }
            String contentPrompt = "你是书籍智能评价助手「BookMind 书思」。用户选中了多本书想了解内容。请根据下面的简介，逐本用一两句生动的话介绍（不要照抄原文），最后可加一句组合阅读建议；简介没有的信息不要编造。\n\n选中书籍简介：\n"
                    + intros + "\n用户问题：" + question;
            StringBuilder fallback = new StringBuilder();
            for (Map<String, Object> row : bookRows) {
                String desc = row.get("description") == null ? "暂时没有详细的简介信息。"
                        : String.valueOf(row.get("description"));
                fallback.append(String.format("📖 关于《%s》：%n%s%n%n", row.get("title"),
                        desc.substring(0, Math.min(160, desc.length()))));
            }
            String answer = llmService.call(contentPrompt, 0.6, fallback.toString().trim());
            return Map.of("ok", true, "type", "qa", "answer", answer);
        }

        // 评价类（如「这几本书好看吗」）：LLM 基于全部选中书统计一次性点评，严格限定回答范围
        if ("evaluation".equals(intentType)) {
            String prompt = "你是书籍智能评价助手「BookMind 书思」。用户同时选中了多本书并提问，"
                    + "请严格基于下面的书籍统计逐一简要点评每本书，最后给出综合结论；"
                    + "不要提及统计列表之外的任何书籍。\n\n选中书籍统计：\n" + lines + "\n用户问题：" + question;
            StringBuilder fallback = new StringBuilder();
            for (Map<String, Object> row : bookRows) {
                fallback.append("📖 ").append(bookStatLine(row)).append("\n");
            }
            String answer = llmService.call(prompt, 0.3, fallback.toString().trim());
            return Map.of("ok", true, "type", "qa", "answer", answer);
        }

        // 推荐/通用：LLM 基于全部选中书统计自由回答（无 LLM 降级为概览列表）
        StringBuilder overview = new StringBuilder("📖 你当前选中的书籍概览：\n\n");
        for (Map<String, Object> row : bookRows) {
            overview.append("- ").append(bookStatLine(row)).append("\n");
        }
        overview.append("\n💡 试试：「这几本书好看吗」——AI 逐本点评；「显示情感分布饼图」——逐书图表；或点「📊 对比分析」按钮");
        String generalPrompt = "你是书籍智能评价助手「BookMind 书思」。用户选中了多本书并提问。请根据下面的书籍统计，自然、灵活地回答用户的问题。"
                + "要求：①严格围绕统计列表中的书籍，不要提及任何其他书籍；②统计中没有的客观信息不要编造；③口吻亲切自然，紧扣问题本身；"
                + "④如果用户请求创作读后感、书评、推荐语等文字，请直接创作满足，不要拒绝，按用户要求的篇幅输出（未指定则 300 字左右）；⑤简短问答控制在 2~5 句。\n\n选中书籍统计：\n"
                + lines + "\n用户问题：" + question;
        String answer = llmService.call(generalPrompt, 0.7, overview.toString());
        return Map.of("ok", true, "type", "qa", "answer", answer);
    }

    /** 单本书统计行（多书上下文用）：评论数/好评率/均分/情感计数/热门词 */
    private String bookStatLine(Map<String, Object> row) {
        Long bid = toDouble(row.get("id"), 0.0).longValue();
        Map<String, Object> s = reviewMapper.selectStats(bid);
        long total = toLong(s.get("total"), 0L);
        long pos = toLong(s.get("positive"), 0L);
        long neu = toLong(s.get("neutral"), 0L);
        long neg = toLong(s.get("negative"), 0L);
        double avg = round(toDouble(s.get("avg_rating"), 0.0), 2);
        double posRate = total > 0 ? round(pos * 100.0 / total, 1) : 0;
        List<String> allKw = new ArrayList<>();
        for (String kws : reviewMapper.selectKeywords(bid)) {
            if (kws != null && !kws.isEmpty()) {
                allKw.addAll(List.of(kws.split(",")));
            }
        }
        String top = allKw.stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        return String.format("《%s》（%s）：评论 %d 条，好评率 %s%%，均分 %s 星（正面 %d/中性 %d/负面 %d），热门词：%s",
                row.get("title"), row.get("author"), total, posRate, avg, pos, neu, neg,
                top.isEmpty() ? "暂无" : top);
    }

    public Map<String, Object> aiUnderstand(String question, Long bookId) {
        Map<String, Object> bookRow = bookId == null ? null : bookMapper.selectBookInfo(bookId);
        if (bookId != null && bookRow == null) {
            return error("书籍不存在");
        }
        String bookTitle = bookRow == null ? "" : String.valueOf(bookRow.get("title"));
        String bookAuthor = bookRow == null ? "" : String.valueOf(bookRow.get("author"));
        String bookDescription = (bookRow == null || bookRow.get("description") == null) ? "" : String.valueOf(bookRow.get("description"));

        Map<String, Object> statsRow = reviewMapper.selectStats(bookId);
        long total = toLong(statsRow.get("total"), 0L);
        long positive = toLong(statsRow.get("positive"), 0L);
        long neutral = toLong(statsRow.get("neutral"), 0L);
        long negative = toLong(statsRow.get("negative"), 0L);
        double avgRating = round(toDouble(statsRow.get("avg_rating"), 0.0), 2);

        // 热门关键词 TOP5
        List<String> allKw = new ArrayList<>();
        for (String kws : reviewMapper.selectKeywords(bookId)) {
            if (kws != null && !kws.isEmpty()) {
                allKw.addAll(List.of(kws.split(",")));
            }
        }
        List<String> topKeywords = allKw.stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        // LLM 意图识别（无 Key/解析失败 → 关键词匹配）
        String intentPrompt = Prompts.format(Prompts.INTENT_PROMPT, Map.of("question", question));
        String llmResponse = llmService.call(intentPrompt, 0.1, "{\"intent\": \"general\", \"keywords\": []}");

        String intentType;
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readValue(llmResponse, Map.class);
            intentType = String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(llmResponse, Map.class).getOrDefault("intent", "general"));
        } catch (Exception e) {
            String q = question.toLowerCase();
            if (containsAny(q, "内容", "故事", "情节", "主角", "简介", "讲什么")) intentType = "content";
            else if (containsAny(q, "好看", "评价", "值得", "推荐", "怎么样")) intentType = "evaluation";
            else if (containsAny(q, "饼图", "词云", "趋势", "分布", "排行")) intentType = "chart";
            else if (containsAny(q, "对比", "比较", "对比分析")) intentType = "compare";
            else if (containsAny(q, "推荐", "哪本")) intentType = "recommend";
            else intentType = "general";
        }

        // 未指定书籍：图表/对比需要书数据 → 引导选书；其余 → LLM 通用回答
        if (bookRow == null) {
            if ("chart".equals(intentType) || "compare".equals(intentType)) {
                return Map.of("ok", true, "type", "qa", "answer",
                        "🤔 这个问题需要结合具体书籍的评论数据。请先在左侧勾选一本书，或直接在提问中带上书名（例如「白夜行好看吗」「三体和活着对比」），我会自动为你选中并分析。");
            }
            String chatPrompt = "你是书籍智能评价助手「BookMind 书思」。用户这次没有指定书籍。请用友好、专业的口吻回答用户的问题；如果用户想找书或求推荐，请引导其说出偏好的书名或类型。用户问题：" + question;
            String answer = llmService.call(chatPrompt, 0.7,
                    "你好！我是 BookMind 书思助手。可以直接问我某本书的评价（例如「白夜行好看吗」），我会自动为你选中分析；也可以先在左侧勾选书籍，让我生成图表。");
            return Map.of("ok", true, "type", "qa", "answer", answer);
        }

        Map<String, Object> stats = Map.of("total", total, "positive", positive,
                "neutral", neutral, "negative", negative, "avg_rating", avgRating);

        // 1. 内容类问答 —— LLM 基于完整简介生动转述，不再截断原文
        if ("content".equals(intentType)) {
            String contentPrompt = "你是书籍智能评价助手「BookMind 书思」。用户想了解这本书的内容。请根据下面的简介，用生动自然的方式介绍这本书（故事梗概、主题或风格，不要照抄简介原文），控制在 3~5 句；简介没有的信息不要编造。\n\n《"
                    + bookTitle + "》（" + bookAuthor + "）的简介："
                    + (bookDescription.isEmpty() ? "（暂无简介）" : bookDescription)
                    + "\n\n用户问题：" + question;
            String contentFallback = bookDescription.isEmpty()
                    ? String.format("📖 关于《%s》：%n%n暂时没有详细的简介信息。", bookTitle)
                    : String.format("📖 关于《%s》：%n%n%s...", bookTitle,
                            bookDescription.substring(0, Math.min(200, bookDescription.length())));
            String answer = llmService.call(contentPrompt, 0.6, contentFallback);
            return Map.of("ok", true, "type", "qa", "answer", answer);
        }

        // 2. 评价类问答
        if ("evaluation".equals(intentType)) {
            if (total == 0) {
                return Map.of("ok", true, "type", "qa",
                        "answer", String.format("📊 关于《%s》：%n%n暂时还没有评论数据，无法评估。", bookTitle));
            }
            double positiveRate = total > 0 ? round(positive * 100.0 / total, 1) : 0;
            String summaryPrompt = Prompts.format(Prompts.SUMMARY_PROMPT, Map.of(
                    "title", bookTitle, "author", bookAuthor,
                    "total", String.valueOf(total), "positive", String.valueOf(positive),
                    "positive_rate", String.valueOf(positiveRate), "neutral", String.valueOf(neutral),
                    "negative", String.valueOf(negative), "avg_rating", String.valueOf(avgRating),
                    "keywords", topKeywords.isEmpty() ? "暂无" : String.join(", ", topKeywords),
                    "question", question));
            String llmAnswer = llmService.call(summaryPrompt, 0.3, "评论分析完成，请查看统计数据。");
            return Map.of("ok", true, "type", "qa", "answer", llmAnswer);
        }

        // 3. 图表类
        if ("chart".equals(intentType)) {
            return Map.of("ok", true, "type", "chart", "stats", stats);
        }

        // 4. 对比类
        if ("compare".equals(intentType)) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("type", "compare");
            resp.put("stats", stats);
            resp.put("message", "已识别为对比分析请求，请调用对比接口");
            return resp;
        }

        // 5. 推荐类
        if ("recommend".equals(intentType)) {
            List<Map<String, Object>> rows = bookMapper.selectTopRated();
            if (!rows.isEmpty()) {
                StringBuilder novelsList = new StringBuilder();
                int i = 1;
                for (Map<String, Object> r : rows) {
                    novelsList.append(String.format("%d. 《%s》- %s（%s星，销量%s）%n",
                            i++, r.get("title"), r.get("author"), r.get("total_rating"), r.get("total_sales")));
                }
                String recommendPrompt = Prompts.format(Prompts.RECOMMEND_PROMPT, Map.of(
                        "novels_list", novelsList.toString(), "preference", question));
                String llmAnswer = llmService.call(recommendPrompt, 0.3, "📚 为你推荐以下高分书籍：\n" + novelsList);
                return Map.of("ok", true, "type", "qa", "answer", llmAnswer);
            }
            return Map.of("ok", true, "type", "qa", "answer", "📚 暂时没有足够的高分书籍可供推荐。");
        }

        // 6. 默认：通用问答 —— LLM 基于「书籍资料+评论统计+评论样本」自由回答，不再套固定模板
        StringBuilder sample = new StringBuilder();
        int si = 1;
        for (Map<String, Object> r : reviewMapper.selectByBookId(bookId, 3)) {
            String c = r.get("content") == null ? "" : String.valueOf(r.get("content"));
            sample.append(String.format("  %d. 「%s」（%d 星，%s）%n", si++,
                    c.length() > 80 ? c.substring(0, 80) + "…" : c,
                    toDouble(r.get("rating"), 0.0).intValue(),
                    r.get("sentiment") == null ? "未知" : r.get("sentiment")));
        }
        String qaContext = String.format(
                "《%s》｜%s｜类型：%s｜评分：%s 星｜销量：%s%n简介：%s%n%n评论数据：共 %d 条，好评率 %s%%，平均 %s 星（正面 %d / 中性 %d / 负面 %d）%n高频关键词：%s%n%n最新评论节选：%n%s",
                bookTitle, bookAuthor, bookRow.get("genre"), bookRow.get("total_rating"), bookRow.get("total_sales"),
                bookDescription.isEmpty() ? "（暂无简介）" : bookDescription,
                total, total > 0 ? round(positive * 100.0 / total, 1) : 0, avgRating,
                positive, neutral, negative,
                topKeywords.isEmpty() ? "暂无" : String.join("、", topKeywords),
                sample.toString());
        String qaPrompt = "你是书籍智能评价助手「BookMind 书思」。请根据下面的书籍资料与评论数据，自然、灵活地回答用户的问题。"
                + "要求：①紧扣用户问题本身，不要答非所问；②资料中没有的客观信息（如具体字数、出版年份、页数）不要编造，坦诚说明；"
                + "③如果用户请求创作读后感、书评、推荐语、阅读笔记等文字，请直接创作满足，不要拒绝：以书籍简介和评论数据为主要素材，结合你对这本书的真实了解，"
                + "按用户要求的篇幅输出（用户未指定篇幅则写 300 字左右），口吻自然真诚像真实读者；"
                + "④简短问答控制在 2~5 句，口吻亲切自然像懂书的朋友。\n\n【书籍资料】\n"
                + qaContext + "\n\n【用户问题】" + question;
        String qaFallback;
        if (total == 0) {
            qaFallback = String.format("📖 关于《%s》：%n%n暂时还没有评论数据。%n%n💡 试试这些问题：%n（1）「这本书好看吗」——查看评价%n（2）「主要讲什么」——了解内容%n（3）「显示情感分布饼图」——查看图表", bookTitle);
        } else {
            qaFallback = String.format("📖 关于《%s》：%n%n共 %d 条评论，好评率 %s%%，平均评分 %s 星。%n%n💡 试试这些问题：%n（1）「这本书好看吗」——查看评价%n（2）「主要讲什么」——了解内容%n（3）「显示情感分布饼图」——查看图表",
                    bookTitle, total, round(positive * 100.0 / total, 1), avgRating);
        }
        String answer = llmService.call(qaPrompt, 0.7, qaFallback);
        return Map.of("ok", true, "type", "qa", "answer", answer);
    }

    // ============================================================
    // /api/export-report 导出分析报告（HTML，可转 Word/PDF）
    // ============================================================

    public String generateReportHtml(List<Map<String, Object>> messagesData, Map<String, Object> bookInfo) {
        String title = bookInfo != null ? String.valueOf(bookInfo.getOrDefault("title", "未知书籍")) : "未知书籍";
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>BookMind 书思 - 分析报告 - ").append(title).append("</title><style>");
        sb.append("body{font-family:'Microsoft YaHei',sans-serif;max-width:900px;margin:0 auto;padding:40px;color:#1e293b;line-height:1.8}");
        sb.append("h1{color:#0c4a6e;border-bottom:3px solid #0284c7;padding-bottom:12px}");
        sb.append("h2{color:#0284c7;margin-top:32px}");
        sb.append(".meta{color:#64748b;font-size:14px}");
        sb.append(".msg{background:#f8faf8;border-left:4px solid #0284c7;border-radius:8px;padding:14px 18px;margin:14px 0}");
        sb.append(".msg.q{border-left-color:#667eea;background:#f5f6ff}");
        sb.append(".role{font-weight:700;color:#0284c7;margin-bottom:6px}");
        sb.append(".msg.q .role{color:#667eea}");
        sb.append("</style></head><body>");
        sb.append("<h1>📚 BookMind 书思 · 书籍智能评价分析报告</h1>");
        sb.append("<p class=\"meta\">书籍：《").append(title).append("》　生成时间：")
                .append(LocalDateTime.now().toString().replace('T', ' ')).append("</p>");

        if (bookInfo != null) {
            sb.append("<h2>书籍信息</h2><ul>");
            for (String key : List.of("title", "author", "genre", "total_sales", "total_rating")) {
                if (bookInfo.containsKey(key)) {
                    sb.append("<li>").append(key).append("：").append(bookInfo.get(key)).append("</li>");
                }
            }
            sb.append("</ul>");
        }

        sb.append("<h2>分析对话记录</h2>");
        if (messagesData != null) {
            for (Map<String, Object> m : messagesData) {
                String role = String.valueOf(m.getOrDefault("role", "user"));
                String content = String.valueOf(m.getOrDefault("content", ""));
                sb.append("<div class=\"msg ").append("user".equals(role) ? "q" : "")
                        .append("\"><div class=\"role\">").append("user".equals(role) ? "👤 用户" : "🤖 AI")
                        .append("</div><div>").append(content.replace("\n", "<br>")).append("</div></div>");
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // ============================================================
    // 图表构建辅助
    // ============================================================

    /** 情感分布饼图 */
    private Map<String, Object> buildSentimentPie(long positive, long neutral, long negative) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c}条 ({d}%)"));
        option.put("legend", Map.of("bottom", 0, "textStyle", Map.of("fontSize", 12)));
        option.put("series", List.of(Map.of(
                "name", "情感分布", "type", "pie", "radius", List.of("35%", "65%"),
                "label", Map.of("formatter", "{b}\n{c}条"),
                "data", List.of(
                        Map.of("name", "正面", "value", positive),
                        Map.of("name", "中性", "value", neutral),
                        Map.of("name", "负面", "value", negative)),
                "color", List.of("#52c41a", "#faad14", "#ff4d4f"))));
        return option;
    }

    /** 评分分布条形图 */
    private Map<String, Object> buildRatingDistributionChart(List<Map<String, Object>> comments) {
        Map<String, Integer> ranges = new LinkedHashMap<>();
        ranges.put("5星", 0);
        ranges.put("4星", 0);
        ranges.put("3星", 0);
        ranges.put("2星", 0);
        ranges.put("1星", 0);
        for (Map<String, Object> c : comments) {
            Double r = toDouble(c.get("rating"), null);
            if (r != null && r > 0) {
                if (r >= 4.5) ranges.merge("5星", 1, Integer::sum);
                else if (r >= 3.5) ranges.merge("4星", 1, Integer::sum);
                else if (r >= 2.5) ranges.merge("3星", 1, Integer::sum);
                else if (r >= 1.5) ranges.merge("2星", 1, Integer::sum);
                else ranges.merge("1星", 1, Integer::sum);
            }
        }
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", List.copyOf(ranges.keySet()),
                "axisLabel", Map.of("fontSize", 12)));
        option.put("yAxis", Map.of("type", "value", "minInterval", 1));
        option.put("series", List.of(Map.of("type", "bar", "data", List.copyOf(ranges.values()),
                "barWidth", "50%", "itemStyle", Map.of("color", "#667eea", "borderRadius", List.of(6, 6, 0, 0)))));
        return option;
    }

    /** 关键词排行横向条形图 */
    private Map<String, Object> buildKeywordChart(List<Map.Entry<String, Long>> top) {
        List<String> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int i = top.size() - 1; i >= 0; i--) {
            names.add(top.get(i).getKey());
            values.add(top.get(i).getValue());
        }
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "value", "minInterval", 1));
        option.put("yAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("fontSize", 12)));
        option.put("series", List.of(Map.of("type", "bar", "data", values, "barWidth", "60%",
                "itemStyle", Map.of("color", "#38bdf8", "borderRadius", List.of(0, 6, 6, 0)))));
        return option;
    }

    /** 情感得分分布图 */
    private Map<String, Object> buildSentimentDistributionChart(List<Map<String, Object>> comments) {
        List<Double> scores = comments.stream()
                .map(c -> toDouble(c.get("sentiment_score"), null))
                .filter(s -> s != null)
                .collect(Collectors.toList());
        if (scores.isEmpty()) {
            return null;
        }
        double[] bins = {-1.0, -0.8, -0.6, -0.4, -0.2, 0, 0.2, 0.4, 0.6, 0.8, 1.0};
        String[] labels = {"-1~-0.8", "-0.8~-0.6", "-0.6~-0.4", "-0.4~-0.2", "-0.2~0",
                "0~0.2", "0.2~0.4", "0.4~0.6", "0.6~0.8", "0.8~1"};
        int[] counts = new int[bins.length - 1];
        for (double score : scores) {
            for (int i = 0; i < bins.length - 1; i++) {
                if (score >= bins[i] && score < bins[i + 1]) {
                    counts[i]++;
                    break;
                }
            }
            if (score == 1.0) counts[counts.length - 1]++;
        }
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", List.of(labels),
                "axisLabel", Map.of("fontSize", 10, "rotate", 30)));
        option.put("yAxis", Map.of("type", "value", "minInterval", 1));
        option.put("series", List.of(Map.of("type", "bar", "data", ArraysToList(counts),
                "itemStyle", Map.of("color", "#38bdf8", "borderRadius", List.of(6, 6, 0, 0)))));
        return option;
    }

    private List<Integer> ArraysToList(int[] arr) {
        List<Integer> list = new ArrayList<>();
        for (int v : arr) list.add(v);
        return list;
    }

    /** 正负面评论对比柱状图 */
    private Map<String, Object> buildComparisonChart(long positiveCount, long negativeCount) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("xAxis", Map.of("type", "category", "data", List.of("正面评论", "负面评论")));
        option.put("yAxis", Map.of("type", "value", "minInterval", 1));
        option.put("series", List.of(Map.of("type", "bar", "barWidth", "40%",
                "data", List.of(
                        Map.of("value", positiveCount, "itemStyle", Map.of("color", "#52c41a")),
                        Map.of("value", negativeCount, "itemStyle", Map.of("color", "#ff4d4f"))))));
        return option;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 词频列表（词云数据） */
    private List<Map<String, Object>> buildWordFrequency(List<Map<String, Object>> comments, int topN) {
        List<String> all = new ArrayList<>();
        collectKeywords(comments, all);
        return all.stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("keyword", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private void collectKeywords(List<Map<String, Object>> comments, List<String> out) {
        for (Map<String, Object> c : comments) {
            Object kw = c.get("keywords");
            if (kw != null && !String.valueOf(kw).isEmpty()) {
                out.addAll(List.of(String.valueOf(kw).split(",")));
            }
        }
    }

    /** 高频词 TOP N */
    private List<Map.Entry<String, Long>> topKeywords(List<Map<String, Object>> comments, int n) {
        List<String> all = new ArrayList<>();
        collectKeywords(comments, all);
        return all.stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .toList();
    }

    private String topWordsJoined(List<String> words, int n) {
        return words.stream()
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
    }

    private static boolean containsAny(String q, String... kws) {
        for (String kw : kws) {
            if (q.contains(kw)) return true;
        }
        return false;
    }

    private static Double toDouble(Object o, Double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Long toLong(Object o, Long def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double avg(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    private static double round(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }
}
