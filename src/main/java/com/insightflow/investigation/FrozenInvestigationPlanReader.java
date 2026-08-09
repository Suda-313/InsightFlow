package com.insightflow.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.investigation.window.InvestigationWindow;
import com.insightflow.investigation.window.InvestigationWindowType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 读取首次入队时冻结的调查计划。
 *
 * <p>Worker 只能消费该 JSON 内的服务端边界；计划缺失或损坏必须失败，不能以执行时的当前时间重建窗口，
 * 否则重试会取得与首次任务不同的证据。</p>
 */
@Service
public class FrozenInvestigationPlanReader {

    private final ObjectMapper objectMapper;

    public FrozenInvestigationPlanReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将受控 JSON 还原为已校验的窗口值对象。 */
    public List<InvestigationWindow> readWindows(InvestigationCase investigation) {
        if (investigation.getPlanJson() == null || investigation.getPlanJson().isBlank()) {
            throw new IllegalStateException("调查计划缺失，不能在 Worker 中重新规划");
        }
        try {
            JsonNode windows = objectMapper.readTree(investigation.getPlanJson()).path("windows");
            if (!windows.isArray() || windows.isEmpty()) {
                throw new IllegalArgumentException("调查计划未包含窗口");
            }
            List<InvestigationWindow> result = new ArrayList<>();
            for (JsonNode window : windows) {
                result.add(new InvestigationWindow(
                        InvestigationWindowType.valueOf(requiredText(window, "type")),
                        OffsetDateTime.parse(requiredText(window, "anchorTime")),
                        OffsetDateTime.parse(requiredText(window, "currentStart")),
                        OffsetDateTime.parse(requiredText(window, "currentEnd")),
                        OffsetDateTime.parse(requiredText(window, "previousStart")),
                        OffsetDateTime.parse(requiredText(window, "previousEnd"))));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("冻结的调查计划不可读取", exception);
        }
    }

    /** 防止 JSON 中的 null 或空白字符串绕过值对象的边界校验。 */
    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("调查计划字段缺失: " + field);
        }
        return value;
    }
}
