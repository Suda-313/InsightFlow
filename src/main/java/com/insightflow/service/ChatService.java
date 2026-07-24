package com.insightflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * AI 对话服务：调用 LLM 并注入当前数据上下文。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final WorkspaceService workspaceService;
    private final IssueMetricBucketRepository metricBucketRepository;
    private final AlertRepository alertRepository;
    private final IssueBaselineProfileRepository baselineRepository;
    private final IssueCatalogRepository catalogRepository;
    private final ObjectMapper objectMapper;

    public ChatService(ChatClient chatClient, WorkspaceService workspaceService,
                       IssueMetricBucketRepository metricBucketRepository,
                       AlertRepository alertRepository,
                       IssueBaselineProfileRepository baselineRepository,
                       IssueCatalogRepository catalogRepository,
                       ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.workspaceService = workspaceService;
        this.metricBucketRepository = metricBucketRepository;
        this.alertRepository = alertRepository;
        this.baselineRepository = baselineRepository;
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
    }

    public Flux<String> chat(UUID workspacePublicId, String message) {
        Long workspaceId = workspaceService.get(workspacePublicId).getId();
        String context = buildContext(workspaceId);

        String systemPrompt = "你是游戏客服舆情分析助手。根据以下当前数据上下文，用中文回答用户问题。\n\n" +
                "要求：\n" +
                "1. 必须引用具体数字（如\"从日均15条激增到40条，+167%\"）\n" +
                "2. 必须指出具体时间点（如\"7/14版本更新后\"）\n" +
                "3. 如果涉及异常，说明z-score和基线对比\n" +
                "4. 结尾给出1-2条可执行的建议\n" +
                "5. 不要用\"可能是\"\"建议排查\"等模糊表述，要用数据说话\n\n" + context;

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getContent();
        String reasoning = null;
        try {
            Object rawReasoning = response.getResult().getOutput().getMetadata().get("reasoning_content");
            if (rawReasoning != null) reasoning = rawReasoning.toString();
        } catch (Exception ignored) {}

        Map<String, String> result = new LinkedHashMap<>();
        if (reasoning != null && !reasoning.isBlank()) result.put("thinking", reasoning);
        result.put("content", content != null ? content : "抱歉，暂时无法回答。");

        try {
            return Flux.just(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            return Flux.just("{\"content\":\"抱歉，系统错误。\"}");
        }
    }

    private String buildContext(Long workspaceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前数据概览\n");

        // Issues
        List<IssueCatalog> catalogs = catalogRepository.findByWorkspaceId(workspaceId);
        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(7);
        List<IssueMetricBucket> buckets = metricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, sevenDaysAgo);

        sb.append("### 主题分布（最近7天）\n");
        catalogs.forEach(c -> {
            long count = buckets.stream().filter(b -> b.getIssueId().equals(c.getId())).mapToInt(IssueMetricBucket::getFeedbackCount).sum();
            if (count > 0) sb.append("- ").append(c.getCanonicalName()).append("(").append(c.getCanonicalKey()).append("): ").append(count).append(" 条\n");
        });

        // Alerts
        List<Alert> recentAlerts = alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        sb.append("\n### 最近告警\n");
        if (recentAlerts.isEmpty()) sb.append("暂无告警\n");
        else recentAlerts.forEach(a -> {
            String name = catalogRepository.findById(a.getIssueId()).map(IssueCatalog::getCanonicalName).orElse("未知");
            sb.append("- ").append(name).append(": ").append(a.getCurrentCount()).append(" 条\n");
        });

        // Baselines
        List<IssueBaselineProfile> baselines = baselineRepository.findByWorkspaceId(workspaceId);
        sb.append("\n### 基线状态\n");
        baselines.forEach(b -> {
            String name = catalogRepository.findById(b.getIssueId()).map(IssueCatalog::getCanonicalName).orElse("未知");
            sb.append("- ").append(name).append(": EWMA=").append(String.format("%.1f", b.getBaselineEwma()))
              .append(", status=").append(b.getStatus()).append(", classification=").append(b.getClassification()).append("\n");
        });

        return sb.toString();
    }
}