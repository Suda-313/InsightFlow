package com.insightflow.importing.infrastructure;

import java.io.InputStream;

/**
 * 原始导入对象的受控存储端口。
 *
 * <p>业务层只使用 Workspace 前缀的对象键读写文件，不了解 MinIO SDK 细节，也不能借此读取
 * 任意 bucket 或任意对象。分析表绝不使用这个端口存入原始文本。</p>
 */
public interface RawImportObjectStorage {

    /**
     * 写入一个已经校验类型与大小的原始文件；调用方仍负责关闭输入流。
     */
    void put(String objectKey, InputStream content, long size, String contentType);

    /**
     * 打开受控对象供预览或异步导入读取；调用方必须在读取后关闭流。
     */
    InputStream open(String objectKey);
}
