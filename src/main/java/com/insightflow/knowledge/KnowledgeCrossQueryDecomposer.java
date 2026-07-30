package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将跨文档/版本冲突类问题拆成最小子查询，便于分别检索后再 RRF 合并。
 *
 * <p>生产路径仅依赖问题文本启发式（KISS）；金标评测由 {@code RagGoldCrossQueryDecomposer}
 * 按 requirement 组与文档标题构造更稳定的子查询。</p>
 */
@Component
public class KnowledgeCrossQueryDecomposer {

    /** 常见双主体连接词：「A和B…」拆成独立检索面（仅在主体 body 上匹配，不含场景前缀）。 */
    private static final Pattern CONNECTOR_SPLIT = Pattern.compile("[和与以及]");

    /** 业务场景前缀：剥离后再分句，避免「社区舆情对照」中的「对照」误触发分句。 */
    private static final Pattern SCENE_PREFIX = Pattern.compile(
            "^(复盘会上需要确认|值班追问|客服转来一个问题|调查员笔记|社区舆情对照|二线升级|质量门禁抽查|培训场景)[：:，,\\s]*");

    private static final Set<String> DECOMPOSE_TYPES = Set.of(
            "CROSS_DOCUMENT", "cross_document", "VERSION_CONFLICT", "version_conflict");

    /**
     * 按题型与问题文本决定是否分解；无法拆出 ≥2 个子句时返回原问题。
     */
    public List<String> decompose(String question, String questionTypeName) {
        if (question == null || question.isBlank()) {
            return List.of("");
        }
        if (!needsDecomposition(questionTypeName, question)) {
            return List.of(question.trim());
        }
        ParsedQuestion parsed = parseQuestion(question);
        List<String> parts = splitBody(parsed.body());
        if (parts.size() < 2) {
            return List.of(question.trim());
        }
        return parts.stream().map(part -> attachScene(parsed.scenePrefix(), part)).toList();
    }

    /** 供金标评测对齐 requirement 组数：取前 {@code groupCount} 个子句。 */
    public List<String> alignToGroupCount(String question, String questionTypeName, int groupCount) {
        if (groupCount < 2) {
            return decompose(question, questionTypeName);
        }
        ParsedQuestion parsed = parseQuestion(question);
        List<String> parts = splitBody(parsed.body());
        if (parts.size() >= groupCount) {
            return parts.subList(0, groupCount).stream()
                    .map(part -> attachScene(parsed.scenePrefix(), part))
                    .toList();
        }
        return decompose(question, questionTypeName);
    }

    /** 暴露分句结果供评测层与 requirement 组对齐。 */
    public List<String> splitBody(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String normalized = body.trim();

        List<String> commaParts = splitOnDelimiter(normalized, "[，,]", 8);
        if (commaParts.size() >= 2) {
            return List.copyOf(commaParts);
        }

        List<String> connectorParts = splitOnConnector(normalized);
        if (connectorParts.size() >= 2) {
            return List.copyOf(connectorParts);
        }

        List<String> questionParts = splitOnDelimiter(normalized, "[？?]", 6);
        if (questionParts.size() >= 2) {
            return List.copyOf(questionParts);
        }

        return List.of(normalized);
    }

    /**
     * 从「A和B…共享问句」提取共享问句尾部，供金标路径为每个文档组拼接子查询。
     *
     * <p>例：「暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？」→「时间窗有没有重叠？各自独立链路吗？」</p>
     */
    public String extractSharedAspect(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        Matcher aspectAfterDe = Pattern.compile("^[^和与以及]+[和与以及](?:[^和与以及]+?)(的.+)$").matcher(trimmed);
        if (aspectAfterDe.matches()) {
            return cleanupPart(aspectAfterDe.group(1));
        }
        Matcher trailingQuestion = Pattern.compile("^[^和与以及]+[和与以及][^和与以及]+?(.+[吗？?].*)$").matcher(trimmed);
        if (trailingQuestion.matches()) {
            return cleanupPart(trailingQuestion.group(1));
        }
        return trimmed;
    }

    public ParsedQuestion parseQuestion(String question) {
        String trimmed = question.trim();
        Matcher sceneMatcher = SCENE_PREFIX.matcher(trimmed);
        if (sceneMatcher.find()) {
            String scene = sceneMatcher.group(1);
            String body = trimmed.substring(sceneMatcher.end()).trim();
            return new ParsedQuestion(scene + "：", body);
        }
        return new ParsedQuestion("", trimmed);
    }

    public String attachScene(String scenePrefix, String part) {
        if (scenePrefix == null || scenePrefix.isBlank()) {
            return part;
        }
        if (part.startsWith(scenePrefix)) {
            return part;
        }
        return scenePrefix + part;
    }

    private boolean needsDecomposition(String questionTypeName, String question) {
        if (questionTypeName != null && DECOMPOSE_TYPES.contains(questionTypeName)) {
            return true;
        }
        ParsedQuestion parsed = parseQuestion(question);
        String body = parsed.body();
        return (CONNECTOR_SPLIT.matcher(body).find()
                        || body.contains("，")
                        || body.contains(","))
                && (body.contains("？") || body.contains("?") || body.contains("吗"));
    }

    private List<String> splitOnDelimiter(String body, String delimiterRegex, int minLength) {
        String[] rawParts = body.split(delimiterRegex);
        List<String> parts = new ArrayList<>();
        for (String raw : rawParts) {
            String trimmed = cleanupPart(raw);
            if (!trimmed.isBlank() && trimmed.length() >= minLength) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private List<String> splitOnConnector(String body) {
        String[] rawParts = CONNECTOR_SPLIT.split(body);
        List<String> parts = new ArrayList<>();
        for (String raw : rawParts) {
            String trimmed = cleanupPart(raw);
            if (!trimmed.isBlank() && trimmed.length() >= 4) {
                parts.add(trimmed);
            }
        }
        if (parts.size() < 2) {
            return parts;
        }
        String sharedTail = extractSharedAspect(body);
        if (sharedTail.isBlank()
                || sharedTail.equals(body.trim())
                || !sharedTail.startsWith("的")) {
            return parts;
        }
        List<String> enriched = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            if (index == parts.size() - 1 && part.contains(sharedTail.replaceAll("[？?。；;，,\\s]+$", ""))) {
                enriched.add(part);
            } else if (!part.contains("？") && !part.contains("?") && !part.contains("吗")) {
                enriched.add(part + sharedTail);
            } else {
                enriched.add(part);
            }
        }
        return dedupePreserveOrder(enriched);
    }

    private List<String> dedupePreserveOrder(List<String> parts) {
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            seen.add(part);
        }
        return List.copyOf(seen);
    }

    private String cleanupPart(String part) {
        if (part == null) {
            return "";
        }
        String cleaned = part.trim();
        cleaned = cleaned.replaceAll("[？?。；;，,\\s]+$", "");
        return cleaned.trim();
    }

    /** 场景前缀与主体问句，供金标层按文档组拼接子查询。 */
    public record ParsedQuestion(String scenePrefix, String body) {
    }
}
