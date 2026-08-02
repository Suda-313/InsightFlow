package com.insightflow.storage;

import java.io.InputStream;

/** 企业知识原文的受控对象存储端口；业务层不接触 bucket、凭据或可签名 URL。 */
public interface KnowledgeObjectStorage {

    /** 写入已验证的 Markdown/TXT 原文，调用方负责关闭输入流。 */
    void put(String objectKey, InputStream content, long size, String contentType);

    /** 打开已由服务层完成范围校验的原文，调用方负责关闭流。 */
    InputStream open(String objectKey);
}
