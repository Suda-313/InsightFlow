package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldCaseAssertion;
import com.insightflow.entity.RagGoldCaseEvidence;
import com.insightflow.entity.RagGoldDataset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 发布前计算数据集内容 checksum，保证 Runner 可验证加载的快照未被篡改。
 *
 * <p>算法为确定性字符串拼接后 SHA-256；字段顺序固定，便于跨环境复算。</p>
 */
public final class RagGoldDatasetChecksum {

    private RagGoldDatasetChecksum() {
    }

    public static String compute(
            RagGoldDataset dataset,
            List<RagGoldCase> cases,
            List<RagGoldCaseEvidence> evidences,
            List<RagGoldCaseAssertion> assertions) {
        StringBuilder builder = new StringBuilder();
        builder.append(dataset.getDatasetKey()).append('|')
                .append(dataset.getDatasetVersion()).append('|')
                .append(dataset.getSplit()).append('|')
                .append(dataset.getSourceCorpusVersion()).append('\n');
        cases.stream()
                .sorted(Comparator.comparing(RagGoldCase::getCaseKey))
                .forEach(goldCase -> appendCase(builder, goldCase));
        evidences.stream()
                .sorted(Comparator.comparing(RagGoldCaseEvidence::getCaseId)
                        .thenComparing(RagGoldCaseEvidence::getSortOrder))
                .forEach(evidence -> appendEvidence(builder, evidence));
        assertions.stream()
                .sorted(Comparator.comparing(RagGoldCaseAssertion::getCaseId)
                        .thenComparing(RagGoldCaseAssertion::getSortOrder))
                .forEach(assertion -> appendAssertion(builder, assertion));
        return sha256(builder.toString());
    }

    private static void appendCase(StringBuilder builder, RagGoldCase goldCase) {
        builder.append("case:")
                .append(goldCase.getCaseKey()).append('|')
                .append(goldCase.getQuestionText()).append('|')
                .append(goldCase.getQuestionType()).append('|')
                .append(goldCase.getDifficulty()).append('|')
                .append(goldCase.isShouldRefuse()).append('\n');
    }

    private static void appendEvidence(StringBuilder builder, RagGoldCaseEvidence evidence) {
        builder.append("evidence:")
                .append(evidence.getCaseId()).append('|')
                .append(evidence.getGranularity()).append('|')
                .append(evidence.getDocumentPublicId()).append('|')
                .append(evidence.getVersionPublicId()).append('|')
                .append(evidence.getChunkPublicId()).append('|')
                .append(evidence.getRequirementKey() == null ? "" : evidence.getRequirementKey()).append('\n');
    }

    private static void appendAssertion(StringBuilder builder, RagGoldCaseAssertion assertion) {
        builder.append("assertion:")
                .append(assertion.getCaseId()).append('|')
                .append(assertion.getAssertionType()).append('|')
                .append(assertion.getAssertionText()).append('|')
                .append(assertion.getWeight()).append('\n');
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
