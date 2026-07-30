package com.insightflow.evaluation.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 金标评测专用 query embedding 磁盘缓存。
 *
 * <p>键为 {@code datasetChecksum + embeddingModel + questionHash}；语料 checksum 或嵌入模型变更时
 * 自动失效。线上 {@link com.insightflow.knowledge.KnowledgeSearchTool} 不使用本缓存。</p>
 */
public class RagGoldEvaluationEmbeddingCache {

    private static final TypeReference<List<Double>> EMBEDDING_TYPE = new TypeReference<>() {
    };

    private final Path rootDir;
    private final ObjectMapper objectMapper;

    public RagGoldEvaluationEmbeddingCache(Path rootDir) {
        this.rootDir = rootDir;
        this.objectMapper = new ObjectMapper();
    }

    public Optional<List<Double>> get(String datasetChecksum, String embeddingModel, String question) {
        Path file = cacheFile(datasetChecksum, embeddingModel, question);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), EMBEDDING_TYPE));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public void put(String datasetChecksum, String embeddingModel, String question, List<Double> embedding)
            throws IOException {
        Path file = cacheFile(datasetChecksum, embeddingModel, question);
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), embedding);
    }

    private Path cacheFile(String datasetChecksum, String embeddingModel, String question) {
        String safeChecksum = sanitize(datasetChecksum);
        String safeModel = sanitize(embeddingModel);
        String questionHash = sha256(question);
        return rootDir.resolve(safeChecksum).resolve(safeModel).resolve(questionHash + ".json");
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 question hash", exception);
        }
    }
}
