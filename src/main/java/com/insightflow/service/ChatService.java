package com.insightflow.service;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
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

    public ChatService(ChatClient chatClient, WorkspaceService workspaceService,
                       IssueMetricBucketRepository metricBucketRepository,
                       AlertRepository alertRepository,
                       IssueBaselineProfileRepository baselineRepository,
                       IssueCatalogRepository catalogRepository) {
        this.chatClient = chatClient;
        this.workspaceService = workspaceService;
        this.metricBucketRepository = metricBucketRepository;
        this.alertRepository = alertRepository;
        this.baselineRepository = baselineRepository;
        this.catalogRepository = catalogRepository;
    }

    public Flux<String> chat(UUID workspacePublicId, String message) {
        Long workspaceId = workspaceService.get(workspacePublicId).getId();
        String context = buildContext(workspaceId);

        String systemPrompt = "你是游戏客服舆情分析助手。根据以下当前数据上下文，用中文回答用户问题。回答要简洁、有数据支撑。\n\n" + context;

        // Use non-streaming call for better compatibility with DashScope
        return Flux.just(chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getContent());
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