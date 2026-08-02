package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Markdown/TXT 的确定性切片器（Phase R1 标题感知 + P4 导语/frontmatter）。
 *
 * <p>先剥离 YAML frontmatter，再按 {@code #～###} 切成 section；首个标题前的无标题段记为
 * {@link #PREAMBLE_SECTION_HEADING}，便于词法/精排独立召回 blockquote 导语。仅在单个 section
 * 超长时按字符窗口硬切，且不跨 section 边界。改切片规则后须 re-publish 语料。</p>
 */
@Component
public class KnowledgeChunker {

    /** 仅识别一级到三级标题；更深层级视为正文，避免过度切碎 SOP/手册结构。 */
    private static final Pattern SECTION_HEADER = Pattern.compile("^(#{1,3})\\s+(.+)$");

    /** 文档开头 YAML frontmatter；剥离后不参与切片，避免元数据挤占正文窗口。 */
    private static final Pattern YAML_FRONTMATTER = Pattern.compile("^---\\s*\\n.*?\\n---\\s*(?:\\n|$)", Pattern.DOTALL);

    /**
     * 首个 Markdown 标题前的无标题段使用固定章节名，供 {@code section_heading}/lexical 索引；
     * 纯无标题全文不套用此标签。
     */
    static final String PREAMBLE_SECTION_HEADING = "文档导语";

    /** 窗口硬切时的字符重叠量（R1.5）；只作用于同一 section 内的连续窗口，不跨 section。 */
    private static final int WINDOW_OVERLAP_CHARACTERS = 100;

    /** 单个切片最大字符数；由构造参数固定，避免在检索时临时改变已发布版本的切片语义。 */
    private final int maxCharacters;

    /**
     * 创建切片器。
     *
     * @param maxCharacters 正整数窗口；token_count 仍用字符长度近似，供检索审计统计
     */
    public KnowledgeChunker(@Value("${insightflow.knowledge.chunk-max-characters:1000}") int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("知识切片窗口必须为正数");
        }
        this.maxCharacters = maxCharacters;
    }

    /**
     * 将非空正文切成连续编号的草稿。
     *
     * <p>每个 section 至少对应一个 chunk；section 正文为空则跳过。窗口切分只发生在 section 内部，
     * 并带固定重叠以提高边界问答的召回稳定性。</p>
     *
     * @param content 已通过 UTF-8 与非空校验的 Markdown/TXT 正文
     * @return 从一开始连续编号的可嵌入片段
     */
    public List<ChunkDraft> chunk(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("知识正文不能为空");
        }
        String normalized = stripFrontmatter(content.replace("\r\n", "\n").replace('\r', '\n').trim());
        List<Section> sections = splitSections(normalized);
        boolean hasTitledSection = sections.stream().anyMatch(section -> section.heading() != null);
        List<ChunkDraft> chunks = new ArrayList<>();
        boolean beforeFirstTitle = true;
        for (Section section : sections) {
            if (section.body().isBlank()) {
                if (section.heading() != null) {
                    beforeFirstTitle = false;
                }
                continue;
            }
            String sectionHeading = section.heading();
            if (sectionHeading == null && beforeFirstTitle && hasTitledSection) {
                sectionHeading = PREAMBLE_SECTION_HEADING;
            }
            if (sectionHeading != null) {
                beforeFirstTitle = false;
            }
            for (String part : splitSectionBody(section.body())) {
                append(chunks, part, sectionHeading);
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("知识正文不能为空");
        }
        return List.copyOf(chunks);
    }

    /**
     * 按 Markdown 标题切 section；标题行本身不进入 body，而是写入 {@link Section#heading}。
     *
     * <p>首段无标题的正文作为 preamble section；若文档含 Markdown 标题，发布时会标记为
     * {@link #PREAMBLE_SECTION_HEADING}。</p>
     */
    private List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher matcher = SECTION_HEADER.matcher(line.trim());
            if (matcher.matches()) {
                flushSection(sections, heading, body);
                heading = matcher.group(2).trim();
                body.setLength(0);
            } else {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        flushSection(sections, heading, body);
        return sections;
    }

    /** 将累积的 section 正文落盘；允许 heading 为空（preamble）或 body 为空（纯标题行，后续会被跳过）。 */
    private void flushSection(List<Section> sections, String heading, StringBuilder body) {
        if (heading == null && body.isEmpty()) {
            return;
        }
        sections.add(new Section(heading, body.toString().trim()));
    }

    /**
     * 在单个 section 内按字符窗口切分；section 未超长时原样返回。
     *
     * <p>重叠只出现在窗口切分处，保证相邻 chunk 共享约 {@value #WINDOW_OVERLAP_CHARACTERS} 字上下文，
     * 但不把两个 section 拼进同一 chunk。</p>
     */
    private List<String> splitSectionBody(String body) {
        if (body.length() <= maxCharacters) {
            return List.of(body);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + maxCharacters, body.length());
            parts.add(body.substring(start, end));
            if (end >= body.length()) {
                break;
            }
            int nextStart = end - Math.min(WINDOW_OVERLAP_CHARACTERS, maxCharacters - 1);
            start = Math.max(nextStart, start + 1);
        }
        return parts;
    }

    /** 剥离 leading YAML frontmatter；非 frontmatter 文档原样返回。 */
    private String stripFrontmatter(String content) {
        Matcher matcher = YAML_FRONTMATTER.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        return content.substring(matcher.end()).trim();
    }

    /** 统一计算 chunk_no 和近似 token 统计；正文为空时不会到达此方法。 */
    private void append(List<ChunkDraft> chunks, String content, String sectionHeading) {
        chunks.add(new ChunkDraft(
                chunks.size() + 1,
                content,
                Math.max(1, content.length()),
                sectionHeading));
    }

    /** section 边界内的标题与正文；heading 为 null 表示 preamble。 */
    private record Section(String heading, String body) {
    }

    /**
     * 切片持久化前的不可变草稿，不包含内部 ID、对象键或任何模型推理信息。
     *
     * @param sectionHeading 所属 Markdown 章节标题（不含 {@code #}）；首个标题前导语为 {@link #PREAMBLE_SECTION_HEADING}
     */
    public record ChunkDraft(int chunkNo, String content, int tokenCount, String sectionHeading) {
    }
}
