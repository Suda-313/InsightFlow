package com.insightflow.evaluation.rag;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.knowledge.KnowledgeCrossQueryDecomposer;
import com.insightflow.repository.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 金标评测专用：按 requirement_key 组与证据文档标题构造 CROSS/VERSION 子查询。
 *
 * <p>比纯文本启发式更稳定：每组子查询显式包含目标文档关键词 + 问题 aspect，
 * 便于分别召回后再 RRF 合并；不写入生产 Chat 默认路径（仍可用 {@link KnowledgeCrossQueryDecomposer}）。</p>
 */
@Component
public class RagGoldCrossQueryDecomposer {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeCrossQueryDecomposer crossQueryDecomposer;

    public RagGoldCrossQueryDecomposer(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeCrossQueryDecomposer crossQueryDecomposer) {
        this.documentRepository = documentRepository;
        this.crossQueryDecomposer = crossQueryDecomposer;
    }

    public List<String> buildSubQueries(
            String question,
            RagGoldQuestionType questionType,
            List<RagGoldEvidenceSnapshot> evidences) {
        if (questionType != RagGoldQuestionType.CROSS_DOCUMENT
                && questionType != RagGoldQuestionType.VERSION_CONFLICT) {
            return List.of(question);
        }
        Map<String, List<RagGoldEvidenceSnapshot>> groups = groupByRequirementKey(evidences);
        if (groups.size() < 2) {
            return crossQueryDecomposer.decompose(question, questionType.name());
        }

        KnowledgeCrossQueryDecomposer.ParsedQuestion parsed = crossQueryDecomposer.parseQuestion(question);
        List<String> bodyClauses = crossQueryDecomposer.splitBody(parsed.body());
        List<String> subQueries = new ArrayList<>(groups.size());
        int groupIndex = 0;
        for (List<RagGoldEvidenceSnapshot> group : groups.values()) {
            String docLabel = resolveDocumentLabel(group.get(0).documentPublicId());
            String clauseBody = pickClause(parsed.body(), bodyClauses, groupIndex, groups.size(), docLabel);
            subQueries.add(crossQueryDecomposer.attachScene(parsed.scenePrefix(), clauseBody));
            groupIndex++;
        }
        return List.copyOf(subQueries);
    }

    private String pickClause(
            String body,
            List<String> bodyClauses,
            int groupIndex,
            int groupCount,
            String docLabel) {
        if (bodyClauses.size() == groupCount && groupIndex < bodyClauses.size()) {
            String clause = bodyClauses.get(groupIndex);
            if (clauseContainsDocHint(clause, docLabel)) {
                return clause;
            }
            return docLabel + " " + clause;
        }
        if (groupCount == 2 && CONNECTOR_BODY.matcher(body).find()) {
            String sharedAspect = crossQueryDecomposer.extractSharedAspect(body);
            if (!sharedAspect.isBlank()) {
                String suffix = sharedAspect.startsWith("的") ? sharedAspect : " " + sharedAspect;
                return docLabel + suffix;
            }
        }
        if (groupIndex < bodyClauses.size()) {
            return docLabel + " " + bodyClauses.get(groupIndex);
        }
        return docLabel + " " + body;
    }

    private static final java.util.regex.Pattern CONNECTOR_BODY =
            java.util.regex.Pattern.compile("[和与以及]");

    private boolean clauseContainsDocHint(String clause, String docLabel) {
        if (docLabel.isBlank()) {
            return false;
        }
        String compactLabel = docLabel.replaceAll("\\s+", "");
        String compactClause = clause.replaceAll("\\s+", "");
        return compactClause.contains(compactLabel.substring(0, Math.min(4, compactLabel.length())));
    }

    private String resolveDocumentLabel(UUID documentPublicId) {
        if (documentPublicId == null) {
            return "";
        }
        Optional<KnowledgeDocument> document = documentRepository.findByPublicId(documentPublicId);
        if (document.isEmpty()) {
            return "";
        }
        return shortenTitle(document.get().getTitle());
    }

    /** 去掉游戏名前缀，保留文档业务短名供 FTS/向量匹配。 */
    static String shortenTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        return title.replaceFirst("^超自然行动组[-\\s]*", "").trim();
    }

    private static Map<String, List<RagGoldEvidenceSnapshot>> groupByRequirementKey(
            List<RagGoldEvidenceSnapshot> evidences) {
        Map<String, List<RagGoldEvidenceSnapshot>> groups = new LinkedHashMap<>();
        for (int index = 0; index < evidences.size(); index++) {
            RagGoldEvidenceSnapshot evidence = evidences.get(index);
            String key = evidence.requirementKey();
            if (key == null || key.isBlank()) {
                key = "__solo_" + index;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(evidence);
        }
        return groups;
    }
}
