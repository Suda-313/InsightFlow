package com.insightflow.evaluation.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从固定 case 子集文件加载 {@code case_key} 列表。
 *
 * <p>一行一个 key，忽略空行与 {@code #} 注释；禁止随机 limit 抽样。</p>
 */
public final class RagGoldCaseKeyFileLoader {

    private RagGoldCaseKeyFileLoader() {
    }

    public static Set<String> load(Path file) throws IOException {
        if (file == null) {
            return Set.of();
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("case-keys 文件不存在: " + file.toAbsolutePath());
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            keys.add(trimmed);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("case-keys 文件未包含任何 case_key: " + file.toAbsolutePath());
        }
        return Set.copyOf(keys);
    }
}
