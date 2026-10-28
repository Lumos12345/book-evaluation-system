package com.book.eval.service;

import com.book.eval.mapper.BookMapper;
import com.book.eval.mapper.ReviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能分析 Agent —— 支持 LLM 意图识别 + 关键词匹配降级
 * 移植自参考项目 agent_manager.py，8 个分析工具行为保持一致
 */
@Service
public class AgentService {

    private final BookMapper bookMapper;
    private final ReviewMapper reviewMapper;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentService(BookMapper bookMapper, ReviewMapper reviewMapper, LlmService llmService) {
        this.bookMapper = bookMapper;
        this.reviewMapper = reviewMapper;
        this.llmService = llmService;
    }

    /** 工具定义（名称 + 描述），用于 /api/agent/tools */
    public record ToolDef(String name, String description) {
    }

    /** 工具注册表（顺序与参考项目一致） */
    public List<ToolDef> getTools() {
        return List.of(
                new ToolDef("analyze_sentiment", "分析书籍的评论情感分布"),
                new ToolDef("extract_keywords", "提取评论中的高频关键词"),
                new ToolDef("analyze_rating_distribution", "分析评论的评分分布"),
                new ToolDef("analyze_trend", "分析评论数量、评分随时间的变化趋势"),
                new ToolDef("compare_books", "对比多本书籍的评论分析"),
                new ToolDef("analyze_sentiment_scores", "分析评论的情感得分分布"),
                new ToolDef("recommend_books", "根据评分和评论量推荐书籍"),
                new ToolDef("summarize_reviews", "使用AI总结评论的核心观点")
        );
    }

    // ============================================================
    // 降级方案：关键词匹配意图
    // ============================================================

    private String fallbackIntent(String question) {
        String q = question.toLowerCase();
        if (containsAny(q, "情感", "情绪", "正面", "负面", "中性", "分布")) return "sentiment";
        if (containsAny(q, "关键词", "热词", "排行", "高频")) return "keyword";
        if (containsAny(q, "评分", "星级", "评分区间")) return "rating";
        if (containsAny(q, "趋势", "变化", "走势", "时间")) return "trend";
        if (containsAny(q, "对比", "比较", "哪本")) return "compare";
        if (containsAny(q, "推荐", "好看", "值得看")) return "recommend";
        if (containsAny(q, "总结", "概括", "综述")) return "summary";
        return "default";
    }

    private boolean containsAny(String q, String... kws) {
        for (String kw : kws) {
            if (q.contains(kw)) return true;
        }
        return false;
    }

    // ============================================================
    // Agent 主流程：LLM 意图识别 → 路由工具
    // ============================================================

    public Map<String, Object> analyze(String question, List<Long> bookIds, int limit) {
        Map<String, Object> failed = new HashMap<>();
        try {
            Long bookId = (bookIds != null && !bookIds.isEmpty()) ? bookIds.get(0) : null;

            if (bookId == null) {
                failed.put("success", false);
                failed.put("error", "请指定书籍ID");
                return failed;
            }

            // 使用 LLM 进行意图识别（无 Key/失败时降级为 intent JSON，解析后即 general）
            String intentPrompt = Prompts.format(Prompts.INTENT_PROMPT, Map.of("question", question));
            String llmResponse = llmService.call(intentPrompt, 0.1, "{\"intent\": \"general\", \"keywords\": []}");

            String intentType;
            try {
                Map<?, ?> intentResult = objectMapper.readValue(llmResponse, Map.class);
                Object intentVal = intentResult.get("intent");
                intentType = intentVal != null ? String.valueOf(intentVal) : "general";
                System.out.println("🤖 LLM 识别意图: " + intentType);
            } catch (Exception e) {
                intentType = fallbackIntent(question);
                System.out.println("⚠️ LLM 解析失败，降级为关键词匹配: " + intentType);
            }

            // 根据意图选择工具
            Map<String, Object> result = null;

            switch (intentType) {
                case "sentiment" -> result = sentimentAnalysisTool(bookId, limit);
                case "keyword" -> result = keywordExtractionTool(bookId, limit);
                case "rating" -> result = ratingDistributionTool(bookId, limit);
                case "trend" -> result = trendAnalysisTool(bookId);
                case "compare" -> {
                    if (bookIds.size() >= 2) {
                        result = compareBooksTool(bookIds, limit);
                    } else {
                        Map<String, Object> err = new HashMap<>();
                        err.put("success", false);
                        err.put("error", "对比分析需要至少选择2本小说");
                        return err;
                    }
                }
                case "recommend" -> result = recommendBooksTool(limit);
                case "summary" -> result = reviewSummaryTool(bookId, 50);
                default -> result = sentimentAnalysisTool(bookId, limit);
            }

            // 解析并返回结果
            if (result != null) {
                if ("success".equals(result.get("status"))) {
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("success", true);
                    ok.put("answer", result.getOrDefault("summary", "分析完成"));
                    ok.put("data", result.getOrDefault("data", Map.of()));
                    ok.put("mode", "llm");
                    return ok;
                } else {
                    Map<String, Object> err = new HashMap<>();
                    err.put("success", false);
                    err.put("error", result.getOrDefault("message", "分析失败"));
                    return err;
                }
            }

            failed.put("success", false);
            failed.put("error", "无法分析，请重试");
            return failed;

        } catch (Exception e) {
            failed.put("success", false);
            failed.put("error", "分析出错: " + e.getMessage());
            return failed;
        }
    }

    // ============================================================
    // Tool 1: 情感分析工具
    // ============================================================

    public Map<String, Object> sentimentAnalysisTool(Long bookId, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> comments = reviewMapper.selectByBookId(bookId, limit);
            if (comments.isEmpty()) {
                error.put("status", "error");
                error.put("message", "该书籍暂无评论");
                return error;
            }

            long positive = comments.stream().filter(c -> "正面".equals(c.get("sentiment"))).count();
            long negative = comments.stream().filter(c -> "负面".equals(c.get("sentiment"))).count();
            long neutral = comments.stream().filter(c -> "中性".equals(c.get("sentiment"))).count();
            long total = comments.size();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", total);
            data.put("positive", positive);
            data.put("negative", negative);
            data.put("neutral", neutral);
            data.put("positive_rate", total > 0 ? round(positive * 100.0 / total, 1) : 0);
            data.put("negative_rate", total > 0 ? round(negative * 100.0 / total, 1) : 0);
            data.put("neutral_rate", total > 0 ? round(neutral * 100.0 / total, 1) : 0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", data);
            result.put("summary", String.format("共%d条评论，正面%d条(%.1f%%)，中性%d条，负面%d条",
                    total, positive, positive * 100.0 / total, neutral, negative));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 2: 关键词提取工具
    // ============================================================

    public Map<String, Object> keywordExtractionTool(Long bookId, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> comments = reviewMapper.selectByBookId(bookId, limit);
            if (comments.isEmpty()) {
                error.put("status", "error");
                error.put("message", "该书籍暂无评论");
                return error;
            }

            List<String> allKeywords = new ArrayList<>();
            for (Map<String, Object> c : comments) {
                Object kw = c.get("keywords");
                if (kw != null && !String.valueOf(kw).isEmpty()) {
                    for (String k : String.valueOf(kw).split(",")) {
                        allKeywords.add(k);
                    }
                }
            }

            Map<String, Long> counter = allKeywords.stream()
                    .collect(Collectors.groupingBy(k -> k, Collectors.counting()));
            List<Map.Entry<String, Long>> top = counter.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .toList();

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, Long> e : top) {
                data.add(Map.of("keyword", e.getKey(), "count", e.getValue()));
            }

            String top3 = top.stream().limit(3)
                    .map(e -> e.getKey() + "(" + e.getValue() + "次)")
                    .collect(Collectors.joining(", "));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", data);
            result.put("summary", "TOP3: " + top3);
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 3: 评分分布工具
    // ============================================================

    public Map<String, Object> ratingDistributionTool(Long bookId, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> comments = reviewMapper.selectByBookId(bookId, limit);
            if (comments.isEmpty()) {
                error.put("status", "error");
                error.put("message", "该书籍暂无评论");
                return error;
            }

            Map<String, Integer> ranges = new LinkedHashMap<>();
            ranges.put("5星", 0);
            ranges.put("4星", 0);
            ranges.put("3星", 0);
            ranges.put("2星", 0);
            ranges.put("1星", 0);

            List<Double> ratings = new ArrayList<>();
            for (Map<String, Object> c : comments) {
                Double r = toDouble(c.get("rating"));
                if (r != null && r > 0) {
                    ratings.add(r);
                    if (r >= 4.5) ranges.merge("5星", 1, Integer::sum);
                    else if (r >= 3.5) ranges.merge("4星", 1, Integer::sum);
                    else if (r >= 2.5) ranges.merge("3星", 1, Integer::sum);
                    else if (r >= 1.5) ranges.merge("2星", 1, Integer::sum);
                    else ranges.merge("1星", 1, Integer::sum);
                }
            }

            double avgRating = ratings.isEmpty() ? 0 : round(ratings.stream().mapToDouble(Double::doubleValue).sum() / ratings.size(), 2);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", ranges);
            result.put("avg_rating", avgRating);
            result.put("summary", String.format("平均评分: %s，5星%d条，4星%d条",
                    round(avgRating, 2), ranges.get("5星"), ranges.get("4星")));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 4: 趋势分析工具
    // ============================================================

    public Map<String, Object> trendAnalysisTool(Long bookId) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> rows = reviewMapper.selectTrendGroupByDate(bookId);
            if (rows.isEmpty()) {
                error.put("status", "error");
                error.put("message", "暂无趋势数据");
                return error;
            }

            // SQL 倒序取 30 条，反转成时间正序
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", String.valueOf(r.get("date")));
                item.put("count", toLong(r.get("count")));
                item.put("avg_rating", r.get("avg_rating") != null ? round(toDouble(r.get("avg_rating")), 2) : 0);
                data.add(item);
            }
            java.util.Collections.reverse(data);

            Map<String, Object> last = data.get(data.size() - 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", data);
            result.put("summary", String.format("共%d个时间点，最新日均评论%s条", data.size(), last.get("count")));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 5: 对比分析工具
    // ============================================================

    public Map<String, Object> compareBooksTool(List<Long> bookIds, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Long bookId : bookIds) {
                Map<String, Object> info = bookMapper.selectBookBasic(bookId);
                List<Map<String, Object>> comments = reviewMapper.selectSentimentRows(bookId, limit);

                long positive = comments.stream().filter(c -> "正面".equals(c.get("sentiment"))).count();
                long negative = comments.stream().filter(c -> "负面".equals(c.get("sentiment"))).count();
                List<Double> ratings = comments.stream()
                        .map(c -> toDouble(c.get("rating")))
                        .filter(r -> r != null && r > 0)
                        .collect(Collectors.toList());
                double avgRating = ratings.isEmpty() ? 0 : round(ratings.stream().mapToDouble(Double::doubleValue).sum() / ratings.size(), 2);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", bookId);
                item.put("title", info != null && info.get("title") != null ? info.get("title") : "书籍" + bookId);
                item.put("author", info != null && info.get("author") != null ? info.get("author") : "未知");
                item.put("review_count", comments.size());
                item.put("positive", positive);
                item.put("negative", negative);
                item.put("avg_rating", avgRating);
                item.put("positive_rate", !comments.isEmpty() ? round(positive * 100.0 / comments.size(), 1) : 0);
                results.add(item);
            }
            results.sort(Comparator.comparingDouble(m -> -toDouble(m.get("avg_rating"))));

            Map<String, Object> top = results.get(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", results);
            result.put("summary", String.format("对比了%d本小说，评分最高的是《%s》(%s星)",
                    results.size(), top.get("title"), top.get("avg_rating")));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 6: 情感得分分布工具
    // ============================================================

    public Map<String, Object> sentimentScoreDistributionTool(Long bookId, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> comments = reviewMapper.selectByBookId(bookId, limit);
            if (comments.isEmpty()) {
                error.put("status", "error");
                error.put("message", "该书籍暂无评论");
                return error;
            }

            List<Double> scores = comments.stream()
                    .map(c -> toDouble(c.get("sentiment_score")))
                    .filter(s -> s != null)
                    .collect(Collectors.toList());

            if (scores.isEmpty()) {
                error.put("status", "error");
                error.put("message", "没有情感得分数据");
                return error;
            }

            double[] bins = {-1.0, -0.8, -0.6, -0.4, -0.2, 0, 0.2, 0.4, 0.6, 0.8, 1.0};
            String[] labels = {"-1~-0.8", "-0.8~-0.6", "-0.6~-0.4", "-0.4~-0.2", "-0.2~0",
                    "0~0.2", "0.2~0.4", "0.4~0.6", "0.6~0.8", "0.8~1"};

            int[] counts = new int[bins.length - 1];
            for (double score : scores) {
                boolean matched = false;
                for (int i = 0; i < bins.length - 1; i++) {
                    if (score >= bins[i] && score < bins[i + 1]) {
                        counts[i]++;
                        matched = true;
                        break;
                    }
                }
                if (!matched && score == 1.0) {
                    counts[counts.length - 1]++;
                }
            }

            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    data.add(Map.of("range", labels[i], "count", counts[i]));
                }
            }

            double avg = scores.stream().mapToDouble(Double::doubleValue).sum() / scores.size();
            double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", data);
            result.put("avg_score", round(avg, 3));
            result.put("summary", String.format("情感得分范围: %.2f ~ %.2f，平均分: %.3f", min, max, avg));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 7: 推荐工具
    // ============================================================

    public Map<String, Object> recommendBooksTool(int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> rows = bookMapper.selectRecommendCandidates();

            List<Map<String, Object>> recommendations = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", r.get("title"));
                item.put("author", r.get("author"));
                item.put("genre", r.get("genre"));
                item.put("review_count", toLong(r.get("review_count")));
                item.put("avg_rating", round(toDouble(r.get("avg_rating")), 1));
                recommendations.add(item);
            }

            if (recommendations.isEmpty()) {
                throw new IllegalStateException("IndexError: list index out of range");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", recommendations);
            result.put("summary", String.format("根据高评分推荐了%d本小说，第一名是《%s》",
                    recommendations.size(), recommendations.get(0).get("title")));
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // Tool 8: LLM 评论总结工具
    // ============================================================

    public Map<String, Object> reviewSummaryTool(Long bookId, int limit) {
        Map<String, Object> error = new HashMap<>();
        try {
            List<Map<String, Object>> comments = reviewMapper.selectByBookId(bookId, limit);
            if (comments.isEmpty()) {
                error.put("status", "error");
                error.put("message", "该书籍暂无评论");
                return error;
            }

            // 提取评论内容（取前20条避免超长）
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> c : comments.subList(0, Math.min(20, comments.size()))) {
                String content = String.valueOf(c.get("content"));
                if (content.length() > 150) content = content.substring(0, 150);
                sb.append("- ").append(content).append("...\n");
            }

            // 使用提示词调用 LLM（与参考项目一致：无 Key 时返回模拟总结）
            String prompt = Prompts.format(Prompts.REVIEW_SUMMARY_PROMPT, Map.of("reviews", sb.toString()));
            String llmSummary = llmService.call(prompt, 0.3, "评论分析完成。根据数据统计，读者总体评价较好。");

            long positive = comments.stream().filter(c -> "正面".equals(c.get("sentiment"))).count();
            long negative = comments.stream().filter(c -> "负面".equals(c.get("sentiment"))).count();
            long neutral = comments.stream().filter(c -> "中性".equals(c.get("sentiment"))).count();
            long total = comments.size();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("llm_summary", llmSummary);
            data.put("total", total);
            data.put("positive", positive);
            data.put("negative", negative);
            data.put("neutral", neutral);
            data.put("review_count", total);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("data", data);
            result.put("summary", "AI 评论总结已生成");
            return result;
        } catch (Exception e) {
            error.put("status", "error");
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }
}
