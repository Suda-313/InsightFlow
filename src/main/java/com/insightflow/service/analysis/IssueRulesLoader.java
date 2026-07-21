package com.insightflow.service.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * 启动期一次性加载规则与归一映射；解析失败让应用不启动，避免运行期用空规则集伪造 unclassified。
 *
 * <p>版本号必须与 {@code WorkspaceProjectionCommandService} 的 ruleVersion 一致，否则幂等键
 * 与投影记录的 rule_version 会与实际执行的规则脱节。</p>
 */
public class IssueRulesLoader {

    /** 规则文件路径，固定在 classpath config/analysis 下，禁止运行期改写。 */
    private static final String RULES_PATH = "config/analysis/issue-rules.toml";

    /** 归一文件路径，与规则文件同版本绑定。 */
    private static final String NORMALIZE_PATH = "config/analysis/issue-normalize.toml";

    /** 已解析规则，按文件出现顺序保留；分类器按 priority 排序而非此顺序。 */
    private List<IssueRule> rules;

    /** 已解析归一映射，归一器按列表顺序子串替换。 */
    private List<NormalizeMapping> normalizeMappings;

    /** 冻结的规则版本，进入 workspace_projection.rule_version 用于追溯。 */
    private String version;

    /** 启动期解析 toml；任何缺失字段或格式错误直接抛出，Bean 初始化失败。 */
    public void load() {
        try {
            TomlTable rulesRoot = Toml.parse(new ClassPathResource(RULES_PATH).getInputStream());
            version = rulesRoot.getString("version");
            rules = new ArrayList<>();
            TomlArray rulesArray = rulesRoot.getArray("rules");
            for (int i = 0; i < rulesArray.size(); i++) {
                TomlTable t = rulesArray.getTable(i);
                rules.add(new IssueRule(
                        t.getString("canonical_key"),
                        t.getString("name"),
                        Math.toIntExact(t.getLong("priority")),
                        toStringList(t.getArray("any_patterns")),
                        t.getArray("all_patterns") == null ? List.of() : toStringList(t.getArray("all_patterns")),
                        t.getArray("exclude_patterns") == null ? List.of() : toStringList(t.getArray("exclude_patterns"))));
            }
            TomlTable normRoot = Toml.parse(new ClassPathResource(NORMALIZE_PATH).getInputStream());
            normalizeMappings = new ArrayList<>();
            TomlArray mappings = normRoot.getArray("mappings");
            for (int i = 0; i < mappings.size(); i++) {
                TomlTable m = mappings.getTable(i);
                normalizeMappings.add(new NormalizeMapping(toStringList(m.getArray("from")), m.getString("to")));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load issue rules from classpath", exception);
        }
    }

    /** 把 toml 数组转为不可变字符串列表，避免分类器误改规则。 */
    private List<String> toStringList(TomlArray array) {
        return array.toList().stream().map(Object::toString).toList();
    }

    /** 返回规则列表，分类器遍历它做排除与正向匹配。 */
    public List<IssueRule> rules() {
        return rules;
    }

    /** 返回归一映射，归一器在匹配前按顺序做子串替换。 */
    public List<NormalizeMapping> normalizeMappings() {
        return normalizeMappings;
    }

    /** 返回冻结版本号；与 command service 的 ruleVersion 必须一致。 */
    public String currentVersion() {
        return version;
    }
}
