package com.insightflow.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 单条金标题目及其最小可验证约束。
 *
 * <p>不维护容易被措辞影响的“标准长答案”，而是维护回答必须覆盖的业务事实和必须避免的编造，
 * 使后续自动规则与人工复核都能围绕相同边界判断。</p>
 */
public record GoldEvaluationCase(
        /** 稳定题目标识，供评测结果、失败回归和人工标注引用。 */
        @JsonProperty("case_id") String caseId,
        /** 固定脱敏场景标识，运行器只能使用与该标识匹配的测试数据。 */
        @JsonProperty("fixture_id") String fixtureId,
        /** trend、alert、comparison、refusal、report 五类之一。 */
        String category,
        /** 实际发送给聊天 Agent 的用户问题。 */
        String question,
        /** 回答必须包含的事实或边界说明，用于事实覆盖率评分。 */
        @JsonProperty("required_facts") List<String> requiredFacts,
        /** 回答不得声称的结论或数据，用于识别幻觉和越权回答。 */
        @JsonProperty("forbidden_claims") List<String> forbiddenClaims,
        /** 是否预期拒答或明确说明数据不足，而非生成确定性结论。 */
        @JsonProperty("refusal_expected") boolean refusalExpected) {

    /**
     * 将规则集合冻结，防止评测执行期间被调用方修改，破坏同一批次结果的可比性。
     */
    public GoldEvaluationCase {
        requiredFacts = List.copyOf(requiredFacts);
        forbiddenClaims = List.copyOf(forbiddenClaims);
    }
}
