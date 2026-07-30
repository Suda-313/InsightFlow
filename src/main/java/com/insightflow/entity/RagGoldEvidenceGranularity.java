package com.insightflow.entity;

/**
 * 可接受证据的粒度。
 *
 * <p>Runner 评分时可分别计算文档级与 chunk 级 Recall；证据列只存公开 UUID，不引用内部主键。</p>
 */
public enum RagGoldEvidenceGranularity {

    /** 任意该文档的已发布版本切片均可接受（较少使用）。 */
    DOCUMENT,

    /** 必须命中指定版本下的切片。 */
    VERSION,

    /** 必须命中指定 chunk 公开 ID。 */
    CHUNK
}
