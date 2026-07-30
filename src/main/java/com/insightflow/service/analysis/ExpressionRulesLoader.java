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
 * 启动期一次性加载平台 L2 表达规则；解析失败让应用不启动，避免运行期用空规则集
 * 让所有反馈静默落入 expr_other。
 *
 * <p>与 {@link IssueRulesLoader} 分离成独立文件和独立 Loader，是因为 L2 是全平台
 * 固定枚举、跨 Workspace 共用同一份规则，而 L1 按 Workspace Pack 变化；合并两者会
 * 让"平台稳定 + Pack 可插拔"这一架构边界在代码里消失。</p>
 */
public class ExpressionRulesLoader {

    /** 规则文件路径，固定在 classpath config/analysis/platform 下，禁止运行期改写。 */
    private static final String RULES_PATH = "config/analysis/platform/expression-rules.toml";

    /** 已解析规则，按文件出现顺序保留；分类器按 priority 排序而非此顺序。 */
    private List<ExpressionRule> rules;

    /** 冻结的规则版本，写入 feedback_projection_annotation.expression_rule_version 用于追溯。 */
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
                rules.add(new ExpressionRule(
                        t.getString("canonical_key"),
                        t.getString("name"),
                        Math.toIntExact(t.getLong("priority")),
                        toStringList(t.getArray("any_patterns")),
                        t.getArray("exclude_patterns") == null ? List.of() : toStringList(t.getArray("exclude_patterns"))));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load expression rules from classpath", exception);
        }
    }

    /** 把 toml 数组转为不可变字符串列表，避免分类器误改规则。 */
    private List<String> toStringList(TomlArray array) {
        return array.toList().stream().map(Object::toString).toList();
    }

    /** 返回规则列表，分类器遍历它做排除与正向匹配。 */
    public List<ExpressionRule> rules() {
        return rules;
    }

    /** 返回冻结版本号；写入投影标注行用于追溯规则口径。 */
    public String currentVersion() {
        return version;
    }
}
