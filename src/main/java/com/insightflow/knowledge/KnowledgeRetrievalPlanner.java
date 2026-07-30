package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据用户问题选择首轮可检索的文档类型。
 *
 * <p>这是确定性启发式，而不是让模型生成数据库过滤条件；未命中规则时返回空列表，
 * 表示首轮无需人为收窄类型。这样既能体现受控的 Agentic RAG 计划步骤，也不会形成可执行的自由工具调用。</p>
 */
@Component
public class KnowledgeRetrievalPlanner {

    /**
     * 将业务关键词映射到固定的知识文档类型。
     * 同一问题可能同时需要版本公告与已知问题，保留两种类型而非静默猜测单一分类。
     */
    public List<KnowledgeDocumentType> plan(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<KnowledgeDocumentType> types = new ArrayList<>();
        addWhen(types, normalized, KnowledgeDocumentType.RELEASE_NOTE, "版本", "公告", "更新", "发布", "release", "changelog");
        addWhen(types, normalized, KnowledgeDocumentType.KNOWN_ISSUE, "已知问题", "bug", "异常", "故障", "缺陷", "报错");
        addWhen(types, normalized, KnowledgeDocumentType.SUPPORT_SOP, "客服", "工单", "sop", "处理流程", "流程", "口径", "升级");
        addWhen(types, normalized, KnowledgeDocumentType.SENTIMENT_PLAYBOOK, "舆情", "回应", "危机", "分级", "投诉");
        addWhen(types, normalized, KnowledgeDocumentType.OPERATION_EVENT, "运营事件", "活动", "维护", "停服", "渠道", "开服", "补偿");
        addWhen(types, normalized, KnowledgeDocumentType.POSTMORTEM, "复盘", "事后", "根因", "事故", "postmortem", "故障回顾", "改进项");
        return List.copyOf(types);
    }

    /** 只要问题包含一个同类关键词，就把该固定类型加入首轮检索条件。 */
    private void addWhen(List<KnowledgeDocumentType> types, String question,
            KnowledgeDocumentType type, String... keywords) {
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                types.add(type);
                return;
            }
        }
    }
}
