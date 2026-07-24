package com.insightflow.storage;

import java.io.InputStream;
import org.springframework.stereotype.Component;

/**
 * 知识原文的 MinIO 适配器。
 *
 * <p>P3 与 CSV 原文共用受控 bucket，但仍通过独立端口阻止知识服务取得导入业务的存储语义；
 * 对象键由 KnowledgeDocumentService 生成，适配器不支持枚举或删除任意对象。</p>
 */
@Component
public class MinioKnowledgeObjectStorage implements KnowledgeObjectStorage {

    /** 已有适配器统一处理 MinIO 连接、bucket 创建和依赖故障转换，避免重复 SDK 实现。 */
    private final RawImportObjectStorage delegate;

    /** 注入受控原始对象端口，而不是向业务服务泄露 MinIOClient。 */
    public MinioKnowledgeObjectStorage(RawImportObjectStorage delegate) {
        this.delegate = delegate;
    }

    /** 委托已受控的写入能力，不允许调用方指定 bucket。 */
    @Override
    public void put(String objectKey, InputStream content, long size, String contentType) {
        delegate.put(objectKey, content, size, contentType);
    }

    /** 委托已受控的读取能力，范围授权仍由上层用例完成。 */
    @Override
    public InputStream open(String objectKey) {
        return delegate.open(objectKey);
    }
}
