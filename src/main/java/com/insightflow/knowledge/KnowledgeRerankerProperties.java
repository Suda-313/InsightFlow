package com.insightflow.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 精排开关与模型参数；默认关闭，评测 CLI 或配置显式开启后才走 Cross-encoder。
 */
@ConfigurationProperties(prefix = "insightflow.knowledge.reranker")
public class KnowledgeRerankerProperties {

    /** 生产默认 false；验证通过后由部署配置开启。 */
    private boolean enabled = false;

    private String model = "qwen3-rerank";

    /** 送入精排模型的 RRF 前缀条数。 */
    private int candidateLimit = 30;

    /**
     * 最终排序中原 RRF rank 的权重，范围 0～1。
     *
     * <p>默认 0 保持纯 cross-encoder 行为；离线评测确认后才能调整生产值。</p>
     */
    private double rrfWeight = 0.0;

    /**
     * 最终 Top8 贪心选段时，同一文档已入选一条后施加的线性软惩罚。
     *
     * <p>默认 0 不改变现有排序；该值作用于归一化 rank score，不依赖供应商原始分数尺度。</p>
     */
    private double diversityPenalty = 0.0;

    private int timeoutSeconds = 5;

    /** qwen3-rerank OpenAI-compatible 根路径，与 embedding 的 compatible-mode 分离。 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-api";

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int candidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = Math.max(1, candidateLimit);
    }

    public double rrfWeight() {
        return rrfWeight;
    }

    public void setRrfWeight(double rrfWeight) {
        this.rrfWeight = Math.max(0.0, Math.min(1.0, rrfWeight));
    }

    public double diversityPenalty() {
        return diversityPenalty;
    }

    public void setDiversityPenalty(double diversityPenalty) {
        this.diversityPenalty = Math.max(0.0, diversityPenalty);
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 写入 AgentRun / RAG run 的检索版本后缀。 */
    public String versionLabel() {
        return "knowledge:rrf:v3+rerank:" + model
                + ":in" + candidateLimit
                + ":rrf" + normalizedLabelNumber(rrfWeight)
                + ":div" + normalizedLabelNumber(diversityPenalty);
    }

    private String normalizedLabelNumber(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
