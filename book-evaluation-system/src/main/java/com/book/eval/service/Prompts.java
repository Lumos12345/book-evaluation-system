package com.book.eval.service;

import java.util.Map;

/**
 * 集中管理所有 LLM 提示词（与参考项目 prompts.py 一致）
 */
public final class Prompts {

    private Prompts() {
    }

    /** 意图识别提示词 */
    public static final String INTENT_PROMPT = """
            你是一个书籍评论分析系统的意图识别助手。

            用户输入的问题是："{question}"

            请判断用户的意图属于以下哪一类：
            1. chart - 要求生成图表（如饼图、词云、趋势图、分布图、排行榜）
            2. compare - 用户明确说出"对比/比较/vs"等字眼并要求比较高下时才选此项；只是同时提到多本书、或问"哪本好""分别介绍一下这几本"都不算 compare
            3. content - 询问书籍内容（如故事、情节、主角、讲什么、介绍一下）
            4. evaluation - 询问书籍评价（如好不好看、值不值得读、口碑怎么样、读者反馈）
            5. recommend - 要求推荐书籍或帮用户挑选（如"推荐几本""该读哪本""哪本更适合我"）
            6. general - 其他一般性问题（如问篇幅字数、创作背景、闲聊寒暄等）

            判定优先级：chart > compare > content > evaluation > recommend > general。
            请只返回JSON格式：{"intent": "类别", "keywords": ["提取的关键词"]}
            """;

    /** 回答生成提示词 */
    public static final String SUMMARY_PROMPT = """
            你是一个书籍评论分析专家。

            当前分析的书籍：《{title}》
            作者：{author}
            评论总数：{total}条
            正面评论：{positive}条（{positive_rate}%）
            中性评论：{neutral}条
            负面评论：{negative}条
            平均评分：{avg_rating}星

            热门关键词：{keywords}

            用户的问题是："{question}"

            请根据以上数据，生成一个简洁、有用的回答。
            要求：
            1. 用自然、友好的语气
            2. 如果好评率高于80%，给出推荐
            3. 如果好评率低于40%，给出提醒
            4. 回答控制在150字以内
            """;

    /** 评论总结提示词 */
    public static final String REVIEW_SUMMARY_PROMPT = """
            请总结以下读者评论的核心观点：

            评论内容：
            {reviews}

            请从以下几个方面总结：
            1. 读者最满意的地方（正面）
            2. 读者最不满意的地方（负面）
            3. 读者讨论最多的主题
            4. 总体评价

            请用简洁的要点形式输出。
            """;

    /** 对比分析提示词 */
    public static final String COMPARE_PROMPT = """
            你是一个书籍对比分析专家。

            以下是多本书籍的数据：
            {novels_data}

            用户的问题是："{question}"

            请对比分析这些书籍，从评分、评论数、情感倾向等方面进行综合比较。
            要求：
            1. 指出评分最高的书籍
            2. 指出评论最多的书籍
            3. 给出综合推荐
            4. 回答控制在200字以内
            """;

    /** 推荐提示词 */
    public static final String RECOMMEND_PROMPT = """
            你是一个书籍推荐专家。

            以下是候选书籍列表：
            {novels_list}

            用户的需求是："{preference}"

            请根据用户需求，推荐最合适的书籍。
            要求：
            1. 给出推荐理由
            2. 回答控制在100字以内
            """;

    /** 简单模板替换（对应 Python str.format 的具名参数用法） */
    public static String format(String template, Map<String, String> params) {
        String result = template;
        for (Map.Entry<String, String> e : params.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
