package com.insightflow.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于 MinIO 的原始文件存储适配器。
 *
 * <p>bucket 只承载待保留的原文件，PostgreSQL 只保存对象键和脱敏事件。每次访问均由上层先
 * 完成 Workspace 与文件归属校验，适配器不暴露按前缀列举或任意删除能力。</p>
 */
@Component
public class MinioRawImportObjectStorage implements RawImportObjectStorage {

    /**
     * 使用官方 SDK，避免自行实现 S3 签名、分块上传或流式下载。
     */
    private final MinioClient minioClient;

    /**
     * 导入专用 bucket 名由环境配置决定，便于本地与部署环境隔离。
     */
    private final String bucket;

    /**
     * 构造适配器；不在构造时联网，防止 Bean 初始化阶段产生不可恢复副作用。
     */
    public MinioRawImportObjectStorage(
            MinioClient minioClient,
            @Value("${insightflow.storage.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /**
     * 需要时创建专用 bucket 并流式上传，不把文件全文读入 JVM 堆。
     */
    @Override
    public void put(String objectKey, InputStream content, long size, String contentType) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new RawObjectStorageException("Unable to store import file", exception);
        }
    }

    /**
     * 返回可关闭的流；SDK 的响应流关闭后会归还底层 HTTP 连接。
     */
    @Override
    public InputStream open(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new RawObjectStorageException("Unable to read import file", exception);
        }
    }

    /**
     * 仅在首次写入时检查/创建 bucket，便于全新本地环境一键启动后直接上传。
     */
    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
