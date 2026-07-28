package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 知识切片规则测试（Phase R1：Markdown 标题感知）。
 *
 * <p>切片必须稳定且尊重 section 边界，才能让金标评测和文档引用在规则未变化时可复跑；
 * 超长 section 才允许窗口硬切，并带固定重叠。</p>
 */
class KnowledgeChunkerTest {

    /** 不同 Markdown 标题应切成独立 chunk，且不跨 section 合并。 */
    @Test
    void splitsByMarkdownHeadersWithoutCrossingSections() {
        KnowledgeChunker chunker = new KnowledgeChunker(200);

        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk(
                "# 公告\n\n第一段内容。\n\n## 细节\n\n第二段内容。");

        assertThat(chunks).extracting(KnowledgeChunker.ChunkDraft::chunkNo).containsExactly(1, 2);
        assertThat(chunks).extracting(KnowledgeChunker.ChunkDraft::sectionHeading)
                .containsExactly("公告", "细节");
        assertThat(chunks).extracting(KnowledgeChunker.ChunkDraft::content)
                .containsExactly("第一段内容。", "第二段内容。");
    }

    /** 首段无标题的正文作为 preamble，与后续标题 section 分开切片。 */
    @Test
    void keepsPreambleSeparateFromFirstHeaderSection() {
        KnowledgeChunker chunker = new KnowledgeChunker(200);

        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk("前言说明。\n\n# 正文\n\n章节内容。");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionHeading()).isNull();
        assertThat(chunks.get(0).content()).isEqualTo("前言说明。");
        assertThat(chunks.get(1).sectionHeading()).isEqualTo("正文");
        assertThat(chunks.get(1).content()).isEqualTo("章节内容。");
    }

    /** 四级及以下标题不触发 section 切分，仍视为正文。 */
    @Test
    void ignoresHeadersBelowLevelThree() {
        KnowledgeChunker chunker = new KnowledgeChunker(200);

        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk("# 一级\n\n#### 四级仍属正文");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionHeading()).isEqualTo("一级");
        assertThat(chunks.get(0).content()).isEqualTo("#### 四级仍属正文");
    }

    /** 超长 section 只在 section 内按字符窗口拆分，且不跨越下一 section。 */
    @Test
    void splitsLongSectionWithinWindowWithoutCrossingNextSection() {
        KnowledgeChunker chunker = new KnowledgeChunker(5);

        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk("# A\n\n123456789\n\n# B\n\nx");

        assertThat(chunks.get(chunks.size() - 1).sectionHeading()).isEqualTo("B");
        assertThat(chunks.get(chunks.size() - 1).content()).isEqualTo("x");
        assertThat(chunks.subList(0, chunks.size() - 1))
                .allSatisfy(chunk -> assertThat(chunk.sectionHeading()).isEqualTo("A"));
        assertThat(chunks).noneMatch(chunk -> chunk.content().contains("x") && chunk.content().contains("9"));
        assertThat(chunks).extracting(KnowledgeChunker.ChunkDraft::chunkNo)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    /** 窗口切分带固定重叠，相邻 chunk 共享尾部/头部字符。 */
    @Test
    void appliesOverlapOnlyOnWindowSplits() {
        KnowledgeChunker chunker = new KnowledgeChunker(10);

        String body = "a".repeat(25);
        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk("# 章节\n\n" + body);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.sectionHeading()).isEqualTo("章节"));
        String first = chunks.get(0).content();
        String second = chunks.get(1).content();
        assertThat(first).hasSize(10);
        assertThat(second).hasSizeLessThanOrEqualTo(10);
        assertThat(first.substring(first.length() - 2)).isEqualTo(second.substring(0, 2));
    }

    /** 纯文本无标题时仍按窗口切分，sectionHeading 为空；窗口切分带重叠。 */
    @Test
    void splitsPlainTextWithoutHeaders() {
        KnowledgeChunker chunker = new KnowledgeChunker(5);

        List<KnowledgeChunker.ChunkDraft> chunks = chunker.chunk("123456789");

        assertThat(chunks).extracting(KnowledgeChunker.ChunkDraft::content)
                .containsExactly("12345", "23456", "34567", "45678", "56789");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.sectionHeading()).isNull());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isPositive());
    }
}
