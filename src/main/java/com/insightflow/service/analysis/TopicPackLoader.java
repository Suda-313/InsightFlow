package com.insightflow.service.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * 启动期一次性加载一个 Workspace Topic Pack（MVP 以目录 + toml 实现，不引入 Skill 市场）。
 *
 * <p>Pack 目录固定为 {@code config/analysis/packs/{packDirectory}/}，包含三个文件：
 * pack.toml（身份）、topic-catalog.toml（L1 议题目录，含 alert_eligible）、
 * topic-rules.toml（L1 分类规则，结构与 {@link IssueRulesLoader} 一致，复用
 * {@link IssueRule} record）。</p>
 *
 * <p><b>当前生效范围：</b>Pack 规则经 {@link TopicPackRegistry} 按 Workspace 绑定解析，
 * 由 {@link WorkspaceProjectionExecutionService} 在每次投影时构造 {@link RuleFirstIssueClassifier}
 * 作为 L1 生产分类源（替代全局 {@code issue-rules.toml}）。历史 {@code feedback_issue_link}
 * 若仍保留旧 8 类 issue key，不与 topic_* 做运行期 alias 映射——切换 Pack 或重投影后
 * 新 link 才写入 Pack 内 topic_* key；零命中仍写 {@link TopicPackDefaults#TOPIC_GENERAL_KEY}。</p>
 *
 * <p>Workspace 可通过 {@code topic_pack_id} 绑定 Pack；null 时回退
 * {@code insightflow.analysis.topic-pack-directory} 全局默认。</p>
 */
public class TopicPackLoader {

    /** 待加载的 Pack 目录名，如 game-chaoziran；由构造方注入，便于未来支持多 Pack。 */
    private final String packDirectory;

    /** Pack 稳定标识，来自 pack.toml 的 pack_id。 */
    private String packId;

    /** Pack 版本号，升版即代表词表或规则变化，触发该 Workspace 重投影。 */
    private String packVersion;

    /** Pack 展示名，供 Dashboard 展示"当前使用的议题包"。 */
    private String displayName;

    /** L1 议题目录，按 sort_order 升序排列；必须包含 topic_general，否则视为 Pack 不合法。 */
    private List<TopicPackTopic> topics;

    /** L1 分类规则；由投影流水线按 Workspace 绑定的 Pack 实例化 RuleFirstIssueClassifier。 */
    private List<IssueRule> rules;

    /** Phase C：Pack 级 LLM Topic Skill 开关；须与全局 insightflow.analysis.topic-llm-skill.enabled 同时为 true。 */
    private boolean topicLlmSkillEnabled;

    public TopicPackLoader(String packDirectory) {
        this.packDirectory = packDirectory;
    }

    /**
     * 解析 pack.toml + topic-catalog.toml + topic-rules.toml；任一文件缺失或格式错误
     * 直接抛出，Bean 初始化失败——不允许运行期用不完整的 Pack 伪装出可用的 Workspace 配置。
     * 目录不含 topic_general 同样视为非法 Pack，因为它是平台对 L1 唯一强制的形态约束。
     */
    public void load() {
        try {
            String basePath = "config/analysis/packs/" + packDirectory + "/";

            TomlTable packRoot = Toml.parse(new ClassPathResource(basePath + "pack.toml").getInputStream());
            packId = packRoot.getString("pack_id");
            packVersion = packRoot.getString("version");
            displayName = packRoot.getString("display_name");
            topicLlmSkillEnabled = Boolean.TRUE.equals(packRoot.getBoolean("topic_llm_skill_enabled"));

            TomlTable catalogRoot = Toml.parse(new ClassPathResource(basePath + "topic-catalog.toml").getInputStream());
            TomlArray topicArray = catalogRoot.getArray("topics");
            List<TopicPackTopic> parsedTopics = new ArrayList<>();
            for (int i = 0; i < topicArray.size(); i++) {
                TomlTable t = topicArray.getTable(i);
                parsedTopics.add(new TopicPackTopic(
                        t.getString("canonical_key"),
                        t.getString("name"),
                        Boolean.TRUE.equals(t.getBoolean("alert_eligible")),
                        Math.toIntExact(t.getLong("sort_order"))));
            }
            parsedTopics.sort(Comparator.comparingInt(TopicPackTopic::sortOrder));
            boolean hasGeneral = parsedTopics.stream()
                    .anyMatch(topic -> TopicPackDefaults.TOPIC_GENERAL_KEY.equals(topic.canonicalKey()));
            if (!hasGeneral) {
                throw new IllegalStateException(
                        "Topic Pack " + packDirectory + " 缺少强制兜底议题 " + TopicPackDefaults.TOPIC_GENERAL_KEY);
            }
            topics = parsedTopics;

            TomlTable rulesRoot = Toml.parse(new ClassPathResource(basePath + "topic-rules.toml").getInputStream());
            List<IssueRule> parsedRules = new ArrayList<>();
            TomlArray rulesArray = rulesRoot.getArray("rules");
            for (int i = 0; i < rulesArray.size(); i++) {
                TomlTable t = rulesArray.getTable(i);
                parsedRules.add(new IssueRule(
                        t.getString("canonical_key"),
                        t.getString("name"),
                        Math.toIntExact(t.getLong("priority")),
                        toStringList(t.getArray("any_patterns")),
                        t.getArray("all_patterns") == null ? List.of() : toStringList(t.getArray("all_patterns")),
                        t.getArray("exclude_patterns") == null ? List.of() : toStringList(t.getArray("exclude_patterns"))));
            }
            rules = parsedRules;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load topic pack from classpath: " + packDirectory, exception);
        }
    }

    private List<String> toStringList(TomlArray array) {
        return array.toList().stream().map(Object::toString).toList();
    }

    /** 返回 Pack 稳定标识，写入 feedback_projection_annotation.topic_pack_id。 */
    public String packId() {
        return packId;
    }

    /** 返回 Pack 版本号，写入 feedback_projection_annotation.topic_pack_version。 */
    public String packVersion() {
        return packVersion;
    }

    /** 返回 Pack 展示名，供 Dashboard 显示当前使用的议题包。 */
    public String displayName() {
        return displayName;
    }

    /** 返回按 sort_order 排序的 L1 议题目录，含 alert_eligible 与展示名。 */
    public List<TopicPackTopic> topics() {
        return topics;
    }

    /** 返回 Pack 内定义的 L1 分类规则，供投影时构造 RuleFirstIssueClassifier。 */
    public List<IssueRule> rules() {
        return rules;
    }

    /** 返回 Pack 是否启用 LLM Topic Skill 补标（须全局开关同时为 true 才生效）。 */
    public boolean topicLlmSkillEnabled() {
        return topicLlmSkillEnabled;
    }
}
