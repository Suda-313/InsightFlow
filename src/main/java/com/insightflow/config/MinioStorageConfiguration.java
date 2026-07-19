package com.insightflow.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 原始导入文件的 MinIO 客户端配置。
 *
 * <p>访问凭据只从环境变量或本地配置注入，绝不写入代码或数据库；客户端创建不发起网络请求，
 * 因此应用启动阶段不会把对象存储短暂故障误判为代码错误。</p>
 */
@Configuration
public class MinioStorageConfiguration {

    /**
     * 构造 S3 兼容客户端；bucket 的存在性在首次文件写入时检查，避免初始化时产生副作用。
     */
    @Bean
    public MinioClient minioClient(
            @Value("${insightflow.storage.minio.endpoint}") String endpoint,
            @Value("${insightflow.storage.minio.access-key}") String accessKey,
            @Value("${insightflow.storage.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
