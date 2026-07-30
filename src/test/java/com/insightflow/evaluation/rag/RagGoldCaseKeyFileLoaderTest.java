package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagGoldCaseKeyFileLoaderTest {

    @Test
    void loadsCaseKeysIgnoringCommentsAndBlankLines(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("slice.txt");
        Files.writeString(
                file,
                """
                # comment
                dev-001

                dev-002
                """);

        Set<String> keys = RagGoldCaseKeyFileLoader.load(file);

        assertThat(keys).containsExactlyInAnyOrder("dev-001", "dev-002");
    }

    @Test
    void rejectsEmptyFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "# only comment\n");

        assertThatThrownBy(() -> RagGoldCaseKeyFileLoader.load(file))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
