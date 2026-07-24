package com.insightflow.knowledge;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 判断首轮知识证据是否足以停止检索。
 *
 * <p>阈值是服务端固定护栏，不由模型或 HTTP 请求控制；分数来自固定 RRF 公式，
 * 单路召回通常低于阈值，会触发一次放宽类型的补检索，之后无论结果如何都停止。</p>
 */
@Component
public class KnowledgeEvidenceGuardrail {

    /** 双路排名第一的 RRF 分数约为 2 / 61，0.02 用于区分双路与仅单路弱召回。 */
    private static final double SUFFICIENT_RRF_SCORE = 0.02d;

    /** 只有存在至少一条满足固定最低相关度的候选，首轮才被视为证据充足。 */
    public boolean isSufficient(List<KnowledgeVectorStore.SearchCandidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.score() >= SUFFICIENT_RRF_SCORE);
    }
}
