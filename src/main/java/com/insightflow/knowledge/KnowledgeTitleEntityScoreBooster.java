package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * P2：在精排前对 RRF 候选施加标题/实体匹配加权，并在双主体 CROSS 查询中保证各实体有代表进 Top 池。
 *
 * <p>信号可解释、只改候选 score 与顺序，不做硬过滤；文档类型仅作软加权。</p>
 */
@Component
public class KnowledgeTitleEntityScoreBooster {

    /** 标题包含查询实体短语时的 RRF 分增量（相对 RRF 量级 ~0.01–0.05）。 */
    static final double ENTITY_TITLE_BOOST = 0.12;

    /** 版本号与标题对齐时的增量。 */
    static final double VERSION_TITLE_BOOST = 0.08;

    /** 查询类型提示与文档类型/标题一致时的软增量。 */
    static final double DOC_TYPE_SOFT_BOOST = 0.04;

    /**
     * content/section 含完整事件编号（KI-xxxx）时的增量。
     *
     * <p>Phase 4B 校准：从 0.15 降至 0.08，避免 identifier 信号压过 entity/coverage 信号，
     * 且仅在 {@link KnowledgeRetrievalOptions#identifierSupplementEnabled()} 为 true 时生效。</p>
     */
    static final double IDENTIFIER_BODY_BOOST = 0.08;

    /**
     * 标题含完整事件编号时的增量。
     *
     * <p>Phase 4B 校准：从 0.10 降至 0.05，与 body boost 同步降权保持相对比例。</p>
     */
    static final double IDENTIFIER_TITLE_BOOST = 0.05;

    /** 精排输入池默认深度，与 {@link KnowledgeRerankerProperties#candidateLimit()} 默认一致。 */
    static final int DEFAULT_RERANK_POOL = 30;

    private static final int FINAL_EVIDENCE_LIMIT = 8;

    private static final Pattern VERSION = Pattern.compile("(?i)\\bv?(\\d+(?:\\.\\d+)+)\\b");
    private static final Pattern CONNECTOR = Pattern.compile("[和与以及]");
    private static final Set<String> STOP_TOKENS = Set.of(
            "复盘会上需要确认", "值班追问", "社区舆情对照", "质量门禁抽查", "培训场景", "二线升级",
            "调查员笔记", "客服转来一个问题", "时间窗", "有没有", "重叠", "各自", "独立", "链路",
            "怎么", "什么", "能否", "是否", "可以", "请问", "问题", "活动", "公告", "说明", "文档");

    private final KnowledgeCrossQueryDecomposer crossQueryDecomposer;
    private final int rerankPoolSize;

    public KnowledgeTitleEntityScoreBooster(KnowledgeCrossQueryDecomposer crossQueryDecomposer) {
        this(crossQueryDecomposer, DEFAULT_RERANK_POOL);
    }

    @Autowired
    KnowledgeTitleEntityScoreBooster(
            KnowledgeCrossQueryDecomposer crossQueryDecomposer,
            @Autowired(required = false) KnowledgeRerankerProperties rerankerProperties) {
        this(
                crossQueryDecomposer,
                rerankerProperties == null ? DEFAULT_RERANK_POOL : rerankerProperties.candidateLimit());
    }

    KnowledgeTitleEntityScoreBooster(KnowledgeCrossQueryDecomposer crossQueryDecomposer, int rerankPoolSize) {
        this.crossQueryDecomposer = crossQueryDecomposer;
        this.rerankPoolSize = Math.max(FINAL_EVIDENCE_LIMIT, rerankPoolSize);
    }

    /**
     * 返回加权并重排后的候选；双主体题在 {@code reserveSize} 窗口内保证每个实体组至少一条代表。
     */
    public List<KnowledgeVectorStore.SearchCandidate> apply(
            String question,
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            KnowledgeRetrievalOptions options) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        QuerySignals signals = buildSignals(question, options);
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (KnowledgeVectorStore.SearchCandidate candidate : candidates) {
            double boost = computeBoost(signals, candidate);
            scored.add(new ScoredCandidate(withScore(candidate, candidate.score() + boost), boost));
        }
        scored.sort(Comparator.comparingDouble((ScoredCandidate item) -> item.candidate().score()).reversed());

        boolean rerankEnabled = options != null && options.rerankerEnabled();
        int reserveSize = rerankEnabled ? rerankPoolSize : FINAL_EVIDENCE_LIMIT;
        if (signals.entityGroups().size() >= 2) {
            scored = ensureEntityCoverage(scored, signals.entityGroups(), reserveSize);
        }
        return scored.stream().map(ScoredCandidate::candidate).toList();
    }

    private double computeBoost(QuerySignals signals, KnowledgeVectorStore.SearchCandidate candidate) {
        String normalizedTitle = normalizeTitle(candidate.title());
        String searchable = searchableText(candidate);
        double boost = 0.0;
        for (String version : signals.versions()) {
            if (normalizedTitle.contains(version.toLowerCase(Locale.ROOT))
                    || searchable.contains(version.toLowerCase(Locale.ROOT))) {
                boost += VERSION_TITLE_BOOST;
            }
        }
        for (String token : signals.entityTokens()) {
            if (token.length() >= 2 && (normalizedTitle.contains(token) || searchable.contains(token))) {
                boost += ENTITY_TITLE_BOOST;
            }
        }
        for (String identifier : signals.eventIds()) {
            if (KnowledgeIdentifierExtractor.containsExact(candidate.title(), identifier)) {
                boost += IDENTIFIER_TITLE_BOOST;
            } else if (KnowledgeIdentifierExtractor.containsExact(candidate.sectionHeading(), identifier)
                    || KnowledgeIdentifierExtractor.containsExact(candidate.content(), identifier)) {
                boost += IDENTIFIER_BODY_BOOST;
            }
        }
        boost += docTypeSoftBoost(signals.typeHints(), normalizedTitle, candidate.documentType());
        return boost;
    }

    private static String searchableText(KnowledgeVectorStore.SearchCandidate candidate) {
        String section = candidate.sectionHeading() == null
                ? ""
                : candidate.sectionHeading().toLowerCase(Locale.ROOT);
        String content = candidate.content() == null ? "" : candidate.content().toLowerCase(Locale.ROOT);
        return section + " " + content;
    }

    private double docTypeSoftBoost(Set<String> typeHints, String normalizedTitle, String documentType) {
        if (typeHints.isEmpty()) {
            return 0.0;
        }
        double boost = 0.0;
        if (typeHints.contains("postmortem")
                && (normalizedTitle.contains("复盘") || "POSTMORTEM".equalsIgnoreCase(documentType))) {
            boost += DOC_TYPE_SOFT_BOOST;
        }
        if (typeHints.contains("operation")
                && (normalizedTitle.contains("活动") || normalizedTitle.contains("签到")
                        || "OPERATION_EVENT".equalsIgnoreCase(documentType))) {
            boost += DOC_TYPE_SOFT_BOOST;
        }
        if (typeHints.contains("faq")
                && (normalizedTitle.contains("FAQ") || normalizedTitle.contains("常见问题"))) {
            boost += DOC_TYPE_SOFT_BOOST;
        }
        if (typeHints.contains("release")
                && (normalizedTitle.contains("版本") || normalizedTitle.contains("热修")
                        || "RELEASE_NOTE".equalsIgnoreCase(documentType))) {
            boost += DOC_TYPE_SOFT_BOOST;
        }
        return boost;
    }

    private List<ScoredCandidate> ensureEntityCoverage(
            List<ScoredCandidate> ranked,
            List<EntityGroup> entityGroups,
            int reserveSize) {
        List<ScoredCandidate> result = new ArrayList<>(ranked);
        int window = Math.min(reserveSize, result.size());
        if (window <= 0) {
            return result;
        }

        for (EntityGroup group : entityGroups) {
            if (hasMatchInWindow(result, group, window)) {
                continue;
            }
            int promoteFrom = findBestMatchIndex(result, group, window, result.size());
            if (promoteFrom < 0) {
                continue;
            }
            int swapWith = findLowestScoreIndex(result, window);
            if (swapWith < 0 || swapWith == promoteFrom) {
                continue;
            }
            ScoredCandidate promoted = result.get(promoteFrom);
            ScoredCandidate demoted = result.get(swapWith);
            result.set(swapWith, promoted);
            result.set(promoteFrom, demoted);
        }
        return result;
    }

    private static int findLowestScoreIndex(List<ScoredCandidate> ranked, int window) {
        int lowestIndex = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int index = 0; index < window && index < ranked.size(); index++) {
            double score = ranked.get(index).candidate().score();
            if (score < lowestScore) {
                lowestScore = score;
                lowestIndex = index;
            }
        }
        return lowestIndex;
    }

    private static boolean hasMatchInWindow(List<ScoredCandidate> ranked, EntityGroup group, int window) {
        for (int index = 0; index < window && index < ranked.size(); index++) {
            if (matchesGroup(ranked.get(index).candidate(), group)) {
                return true;
            }
        }
        return false;
    }

    private static int findBestMatchIndex(
            List<ScoredCandidate> ranked, EntityGroup group, int windowStart, int windowEnd) {
        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int index = windowStart; index < windowEnd && index < ranked.size(); index++) {
            if (!matchesGroup(ranked.get(index).candidate(), group)) {
                continue;
            }
            double score = ranked.get(index).candidate().score();
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    /**
     * 判断候选 chunk 是否代表某路子查询的 entity 组。
     *
     * <p>Phase 4D：子查询中的文档短名写入 {@link EntityGroup#titleAnchors()}，先约束
     * {@code candidate.title}，避免 dev-149 热修 chunk 因「匹配」等泛 token 占位 FAQ 组。</p>
     */
    static boolean matchesGroup(KnowledgeVectorStore.SearchCandidate candidate, EntityGroup group) {
        String normalizedTitle = normalizeTitle(candidate.title());
        String searchable = searchableText(candidate);
        if (!group.titleAnchors().isEmpty()) {
            boolean titleAnchorHit = false;
            for (String anchor : group.titleAnchors()) {
                if (normalizedTitle.contains(anchor)) {
                    titleAnchorHit = true;
                    break;
                }
            }
            if (!titleAnchorHit) {
                return false;
            }
        }
        for (String identifier : group.eventIds()) {
            if (KnowledgeIdentifierExtractor.containsExact(candidate.title(), identifier)
                    || KnowledgeIdentifierExtractor.containsExact(candidate.sectionHeading(), identifier)
                    || KnowledgeIdentifierExtractor.containsExact(candidate.content(), identifier)) {
                return true;
            }
        }
        if (normalizedTitle.isBlank() && searchable.isBlank()) {
            return false;
        }
        int minTokenLength = group.titleAnchors().isEmpty() ? 2 : 3;
        for (String token : group.tokens()) {
            if (token.length() >= minTokenLength
                    && (normalizedTitle.contains(token) || searchable.contains(token))) {
                return true;
            }
        }
        if (group.titleAnchors().isEmpty()) {
            for (String version : group.versions()) {
                if (normalizedTitle.contains(version.toLowerCase(Locale.ROOT))
                        || searchable.contains(version.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return !group.titleAnchors().isEmpty();
    }

    /**
     * 组内最佳代表排序分：token 重合数优先，同分取 RRF 更靠前（poolIndex 更小）。
     *
     * <p>供 {@link KnowledgeCoverageAwareSelector} 在 swap/upgrade 时区分同文档多 chunk。</p>
     */
    static int groupMatchQuality(
            KnowledgeVectorStore.SearchCandidate candidate,
            EntityGroup group,
            int poolIndex) {
        if (!matchesGroup(candidate, group)) {
            return Integer.MIN_VALUE;
        }
        return tokenOverlapCount(candidate, group) * 1_000 - poolIndex;
    }

    private static int tokenOverlapCount(KnowledgeVectorStore.SearchCandidate candidate, EntityGroup group) {
        String normalizedTitle = normalizeTitle(candidate.title());
        String searchable = searchableText(candidate);
        int overlap = 0;
        for (String token : group.tokens()) {
            if (token.length() < 3) {
                continue;
            }
            if (normalizedTitle.contains(token) || searchable.contains(token)) {
                overlap++;
            }
        }
        for (String identifier : group.eventIds()) {
            if (KnowledgeIdentifierExtractor.containsExact(candidate.title(), identifier)
                    || KnowledgeIdentifierExtractor.containsExact(candidate.sectionHeading(), identifier)
                    || KnowledgeIdentifierExtractor.containsExact(candidate.content(), identifier)) {
                overlap += 2;
            }
        }
        return overlap;
    }

    QuerySignals buildSignals(String question, KnowledgeRetrievalOptions options) {
        Set<String> versions = extractVersions(question);
        Set<String> entityTokens = new LinkedHashSet<>();
        Set<String> typeHints = extractTypeHints(question);
        List<EntityGroup> groups = new ArrayList<>();

        // identifierBoostEnabled 与 supplement 开关联动：off 时 eventIds 为空，computeBoost 跳过
        // identifier 加权循环，使 Phase 4A/4B 消融能真正分离 P2 全链路（supplement + booster）。
        // entityGroups 内部仍保留各自的 eventIds，供 ensureEntityCoverage/matchesGroup 做双主体覆盖判断。
        boolean identifierBoostEnabled = options == null || options.identifierSupplementEnabled();
        Set<String> eventIds = identifierBoostEnabled
                ? KnowledgeIdentifierExtractor.extractEventIds(question)
                : new LinkedHashSet<>();

        List<String> subQueries = options == null ? null : options.subQueries();
        if (subQueries != null && subQueries.size() >= 2) {
            for (String subQuery : subQueries) {
                groups.add(buildEntityGroup(subQuery));
            }
        } else {
            KnowledgeCrossQueryDecomposer.ParsedQuestion parsed = crossQueryDecomposer.parseQuestion(question);
            List<String> parts = crossQueryDecomposer.splitBody(parsed.body());
            if (parts.size() >= 2) {
                for (String part : parts) {
                    groups.add(buildEntityGroup(part));
                }
            } else if (CONNECTOR.matcher(parsed.body()).find()) {
                for (String part : parsed.body().split("[和与以及]")) {
                    if (!part.isBlank()) {
                        groups.add(buildEntityGroup(part.trim()));
                    }
                }
            }
        }

        for (EntityGroup group : groups) {
            entityTokens.addAll(group.tokens());
            versions.addAll(group.versions());
            // 仅在 identifier boost 开启时才将各子组的 eventIds 汇入信号，避免 identifier 关闭时仍施加加权
            if (identifierBoostEnabled) {
                eventIds.addAll(group.eventIds());
            }
        }
        if (groups.isEmpty()) {
            entityTokens.addAll(extractTokens(question));
        }
        return new QuerySignals(
                List.copyOf(versions), List.copyOf(entityTokens), List.copyOf(groups), typeHints, List.copyOf(eventIds));
    }

    private EntityGroup buildEntityGroup(String text) {
        Set<String> tokens = extractTokens(text);
        Set<String> groupVersions = extractVersions(text);
        Set<String> groupEventIds = KnowledgeIdentifierExtractor.extractEventIds(text);
        List<String> titleAnchors = extractTitleAnchors(text);
        return new EntityGroup(
                List.copyOf(tokens), List.copyOf(groupVersions), List.copyOf(groupEventIds), titleAnchors);
    }

    /** 从子查询文本提取文档标题锚点，供 matchesGroup 过滤跨文档误命中。 */
    static List<String> extractTitleAnchors(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = normalizeTitle(text);
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        addTitleAnchorIfPresent(anchors, normalized, "玩家常见问题faq");
        addTitleAnchorIfPresent(anchors, normalized, "常见问题faq");
        if (normalized.contains("faq") && anchors.stream().noneMatch(item -> item.contains("faq"))) {
            anchors.add("faq");
        }
        addTitleAnchorIfPresent(anchors, normalized, "热修复说明");
        addTitleAnchorIfPresent(anchors, normalized, "热修");
        addTitleAnchorIfPresent(anchors, normalized, "稳定性复盘");
        addTitleAnchorIfPresent(anchors, normalized, "postmortem");
        addTitleAnchorIfPresent(anchors, normalized, "数据限制说明");
        addTitleAnchorIfPresent(anchors, normalized, "可用性与限制");
        addTitleAnchorIfPresent(anchors, normalized, "运营数据");
        addTitleAnchorIfPresent(anchors, normalized, "版本更新说明");
        addTitleAnchorIfPresent(anchors, normalized, "玩法机制参考");
        addTitleAnchorIfPresent(anchors, normalized, "玩法机制");
        addTitleAnchorIfPresent(anchors, normalized, "运营档案");
        addTitleAnchorIfPresent(anchors, normalized, "运营事件档案");
        addTitleAnchorIfPresent(anchors, normalized, "对照档案");
        addTitleAnchorIfPresent(anchors, normalized, "归因 sop");
        addTitleAnchorIfPresent(anchors, normalized, "sop");
        addTitleAnchorIfPresent(anchors, normalized, "外挂举报");
        addTitleAnchorIfPresent(anchors, normalized, "暑期签到");
        addTitleAnchorIfPresent(anchors, normalized, "古蜀遗迹");
        addTitleAnchorIfPresent(anchors, normalized, "维护窗口");
        return List.copyOf(anchors);
    }

    private static void addTitleAnchorIfPresent(Set<String> anchors, String normalized, String anchor) {
        if (normalized.contains(anchor)) {
            anchors.add(anchor);
        }
    }

    static Set<String> extractVersions(String text) {
        Set<String> versions = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return versions;
        }
        Matcher matcher = VERSION.matcher(text);
        while (matcher.find()) {
            versions.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return versions;
    }

    static Set<String> extractTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = normalizeTitle(text);
        Matcher versionMatcher = VERSION.matcher(normalized);
        while (versionMatcher.find()) {
            tokens.add(versionMatcher.group(1).toLowerCase(Locale.ROOT));
        }
        normalized = normalized.replaceAll("[？?。；;，,：:\\s]+", " ");
        for (String raw : normalized.split("\\s+")) {
            String token = raw.trim();
            if (token.length() < 2 || STOP_TOKENS.contains(token)) {
                continue;
            }
            tokens.add(token);
            if (token.length() > 4) {
                tokens.add(token.substring(0, Math.min(4, token.length())));
            }
        }
        extractChineseRuns(normalized, tokens);
        return tokens;
    }

    private static void extractChineseRuns(String text, Set<String> tokens) {
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fff]{2,8}").matcher(text);
        while (matcher.find()) {
            String run = matcher.group();
            if (STOP_TOKENS.contains(run)) {
                continue;
            }
            tokens.add(run);
            if (run.length() >= 3) {
                tokens.add(run.substring(0, 2));
            }
            if (run.length() >= 4) {
                tokens.add(run.substring(0, Math.min(4, run.length())));
            }
        }
    }

    static Set<String> extractTypeHints(String question) {
        Set<String> hints = new HashSet<>();
        if (question == null) {
            return hints;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        if (normalized.contains("复盘") || normalized.contains("postmortem")) {
            hints.add("postmortem");
        }
        if (normalized.contains("活动") || normalized.contains("签到") || normalized.contains("维护")) {
            hints.add("operation");
        }
        if (normalized.contains("faq") || normalized.contains("常见问题")) {
            hints.add("faq");
        }
        if (normalized.contains("公告") || normalized.contains("版本") || normalized.contains("热修")) {
            hints.add("release");
        }
        return hints;
    }

    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceFirst("^超自然行动组[-\\s]*", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static KnowledgeVectorStore.SearchCandidate withScore(
            KnowledgeVectorStore.SearchCandidate candidate, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                candidate.documentId(),
                candidate.versionId(),
                candidate.versionNo(),
                candidate.chunkId(),
                candidate.title(),
                candidate.content(),
                score,
                candidate.documentType(),
                candidate.sectionHeading(),
                candidate.effectiveWindow());
    }

    record QuerySignals(
            List<String> versions,
            List<String> entityTokens,
            List<EntityGroup> entityGroups,
            Set<String> typeHints,
            List<String> eventIds) {
    }

    record EntityGroup(List<String> tokens, List<String> versions, List<String> eventIds, List<String> titleAnchors) {
        EntityGroup {
            titleAnchors = titleAnchors == null ? List.of() : List.copyOf(titleAnchors);
        }
    }

    private record ScoredCandidate(KnowledgeVectorStore.SearchCandidate candidate, double boost) {
    }
}
