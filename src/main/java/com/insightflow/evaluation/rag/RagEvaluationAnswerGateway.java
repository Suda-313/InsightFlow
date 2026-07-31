package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeRetrievalResult;

/**
 * RAG 评测与模型调用之间的最小边界。
 *
 * <p>评测运行器只能提交固定问题和已经受控检索的证据，不能获得 ChatClient、数据库或存储访问能力；
 * 该接口也让评分规则可以用确定性替身进行单元测试。</p>
 */
public interface RagEvaluationAnswerGateway {

    /** 基于给定题目和本题检索证据生成最终回答，并透传供应商 Usage（若有）。 */
    RagEvaluationGenerationResult answer(String question, KnowledgeRetrievalResult retrieval);
}
