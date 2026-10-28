package com.book.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 调用大语言模型（DashScope 通义千问）
 * 与参考项目一致：未配置 Key 或调用失败时，返回调用方指定的降级文本
 */
@Service
public class LlmService {

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:qwen-turbo}")
    private String model;

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用大语言模型
     *
     * @param prompt    用户提示词
     * @param temperature 温度参数
     * @param fallback  降级响应（Key 未配置或调用失败时返回）
     */
    public String call(String prompt, double temperature, String fallback) {
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️ 未配置 llm.api-key，使用降级响应");
            return fallback;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", Map.of(
                            "messages", List.of(
                                    Map.of("role", "system", "content", "你是一个专业的助手。"),
                                    Map.of("role", "user", "content", prompt)
                            )
                    ),
                    "parameters", Map.of("temperature", temperature, "result_format", "message")
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String resp = restTemplate.postForObject(baseUrl, entity, String.class);

            JsonNode root = objectMapper.readTree(resp);
            String text = root.path("output").path("choices").get(0).path("message").path("content").asText("");
            // LLM 偶发返回空内容时同样降级，避免前端收到空白回答
            if (text == null || text.isBlank()) {
                System.out.println("⚠️ LLM 返回内容为空，使用降级响应");
                return fallback;
            }
            return text;
        } catch (Exception e) {
            System.out.println("❌ LLM 调用失败: " + e.getMessage());
            return fallback;
        }
    }
}
