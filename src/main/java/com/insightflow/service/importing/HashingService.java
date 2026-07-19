package com.insightflow.service.importing;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * 导入文件、外部引用和规范化内容共用的 SHA-256 摘要服务。
 *
 * <p>哈希用于幂等、去重和审计，不用作加密或匿名化承诺；外部引用的真实明文绝不落库。</p>
 */
@Component
public class HashingService {

    /**
     * 流式计算文件摘要，避免为校验而把整个上传文件复制进内存。
     */
    public String sha256(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Unable to calculate SHA-256", exception);
        }
    }

    /**
     * 对短文本创建稳定摘要；调用方负责先选择正确的规范化或脱敏口径。
     */
    public String sha256(String value) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 以小写十六进制存储，长度固定为 64，正好匹配数据库 CHAR(64) 列。
     */
    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
