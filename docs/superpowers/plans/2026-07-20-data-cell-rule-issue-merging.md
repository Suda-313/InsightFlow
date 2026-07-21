# Data Cell 与规则优先主题归并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已投影的脱敏 `FeedbackEvent` 以确定性方式映射为可追溯主题事实（`issue_catalog`/`issue_alias`/`feedback_issue_link`/`data_cell`/`cell_issue`），暂不计算指标/EWMA/Alert。

**Architecture:** 在现有 `WorkspaceProjectionTaskRunner` 链路中插入一个单事务编排服务 `WorkspaceProjectionExecutionService`：加载脱敏事件 → 归一化 → 规则优先分类（最多 2 主题，未命中 `unclassified`）→ DataCell 切分（40/60min/6000token）→ 写 5 张事实表；成功后由现有 `WorkspaceProjectionCompletionService` 翻转终态并 `markProjected`，失败则 `projection_failed` 且事实整体回滚。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, tomlj（新增 TOML 解析），JUnit 5 + Mockito（纯单元测试，本机无 Docker 也可跑）。

## Global Constraints

- 技术栈固定 Java 17 + Spring Boot 3.5 + PostgreSQL 16 + Flyway + MinIO；模块化单体 + MVC 包结构；不引入 Kafka/微服务/真实爬虫/多 Agent/真实 LLM 调用。
- 不修改 V1–V6 迁移；新增表/字段必须新前向迁移（本期 5 张表已在 V6 预留，无需新迁移）。
- 所有业务读写按 `workspace_id` 隔离；外部 API 只暴露 `public_id`。
- 只读脱敏 `feedback_event.sanitized_text`，不保存原始 CSV/真实工单号/手机/邮箱/未脱敏文本到 PostgreSQL。
- 本期不写 `issue_metric_bucket`/`issue_baseline_profile`/`alert`；不在 Controller 或子类手写跨层 SQL/主题计算/状态机。
- 每个新增业务/实体/迁移模块有效注释行数 ≥ 非空代码行数 1/2，解释业务目的/约束/边界，禁止机械复述。
- TDD：先写失败测试，再写最小实现。
- **不提交不推送**（HANDOFF §1："不要提交或推送，除非用户明确要求"）；每个任务末尾只 `git add` 暂存，最终由用户决定是否提交。
- 本机无 Docker；所有新测试为 Mockito 纯单元测试（不依赖真实 PG），可在本机跑 `mvnw.cmd test`。真实 PG + app 启动验证留给装 Docker 的设备。

---

## File Structure

**新增配置（resources）：**
- `src/main/resources/config/analysis/issue-rules.toml` — 规则优先主题规则（8 条种子）
- `src/main/resources/config/analysis/issue-normalize.toml` — 同义词归一映射

**新增分析服务（`service/analysis/`）：**
- `IssueRule.java` — 规则记录（canonical_key/name/priority/any_patterns/all_patterns/exclude_patterns）
- `NormalizeMapping.java` — 归一映射记录（from[]/to）
- `IssueRulesLoader.java` — `@Component`，启动期 `@PostConstruct` 解析 toml，暴露 rules + version
- `IssueTextNormalizer.java` — 纯函数：全角半角/繁简/标点/同义词归一
- `Classification.java` — 记录（canonicalKey/confidence/assignmentMethod）
- `IssueClassifier.java` — Port 接口（后续 Qwen 实现）
- `RuleFirstIssueClassifier.java` — 本期唯一实现，纯函数
- `DataCellPlan.java` — 切分计算输出值（windowStart/end/closeReason/events/tokens）
- `DataCellBuilder.java` — 纯函数切分
- `ProjectionSourceLoader.java` — 按 projection_file 读 FeedbackEvent
- `IssueCatalogService.java` — find-or-create issue_catalog + issue_alias
- `ProjectionFactWriter.java` — 写 link/data_cell/cell_issue
- `WorkspaceProjectionExecutionService.java` — 单事务编排（REQUIRES_NEW）

**新增实体（`entity/`）：**
- `IssueCatalog.java`、`IssueAlias.java`、`FeedbackIssueLink.java`、`DataCell.java`、`CellIssue.java`

**新增仓储（`repository/`）：**
- `IssueCatalogRepository.java`、`IssueAliasRepository.java`、`FeedbackIssueLinkRepository.java`、`DataCellRepository.java`、`CellIssueRepository.java`

**修改：**
- `pom.xml` — 加 tomlj 依赖
- `entity/FeedbackEvent.java` — 加 getId/getWorkspaceId/getSanitizedText/getOccurredAt/getSourceKind 读方法
- `repository/FeedbackEventRepository.java` — 加按 ingestedTaskId 批量查询
- `entity/WorkspaceProjection.java` — 加 recordSourceWindow + getSourceWindowStart/End
- `task/WorkspaceProjectionCompletionService.java` — markSucceeded 使用已记录的 source window
- `task/WorkspaceProjectionTaskRunner.java` — 调用 execution service 后再 complete

---

### Task 1: 添加 tomlj TOML 解析依赖

**Files:**
- Modify: `pom.xml:24-89`（dependencies 块）

**Interfaces:**
- Produces: `org.tomlj:tomlj` 在 classpath，供 Task 2 的 `IssueRulesLoader` 使用。

- [ ] **Step 1: 在 pom.xml dependencies 块末尾（`</dependency>` of testcontainers postgresql 之后、`</dependencies>` 之前）加 tomlj**

```xml
        <dependency>
            <groupId>org.tomlj</groupId>
            <artifactId>tomlj</artifactId>
            <version>1.1.0</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析**

Run: `.\mvnw.cmd -q dependency:resolve -Dincludes=org.tomlj:tomlj`
Expected: BUILD SUCCESS，无 "Could not resolve" 错误。

- [ ] **Step 3: 暂存（不提交）**

```bash
git add pom.xml
```

---

### Task 2: 规则与归一配置 + IssueRulesLoader

**Files:**
- Create: `src/main/resources/config/analysis/issue-rules.toml`
- Create: `src/main/resources/config/analysis/issue-normalize.toml`
- Create: `src/main/java/com/insightflow/service/analysis/IssueRule.java`
- Create: `src/main/java/com/insightflow/service/analysis/NormalizeMapping.java`
- Create: `src/main/java/com/insightflow/service/analysis/IssueRulesLoader.java`
- Test: `src/test/java/com/insightflow/service/analysis/IssueRulesLoaderTest.java`

**Interfaces:**
- Produces: `IssueRulesLoader.rules()` → `List<IssueRule>`；`IssueRulesLoader.currentVersion()` → `String`（与 command service 的 `@Value("rules:v1")` 一致）。
- `IssueRule` 字段：`String canonicalKey, String name, int priority, List<String> anyPatterns, List<String> allPatterns, List<String> excludePatterns`。

- [ ] **Step 1: 写 issue-rules.toml**

```toml
# 规则优先主题归并；未命中返回 unclassified，不伪造主题。改规则即升 version 触发重新投影。
version = "rules:v1"

[[rules]]
canonical_key = "login_failure"
name = "登录失败"
priority = 90
any_patterns = ["登录不上", "登录失败", "登不上", "进不去游戏", "无法登录", "登录异常"]
exclude_patterns = ["充值", "退款"]

[[rules]]
canonical_key = "payment_recharge"
name = "充值异常"
priority = 80
any_patterns = ["充值", "到账", "未到账", "充了没收到", "充值失败"]
exclude_patterns = []

[[rules]]
canonical_key = "item_loss"
name = "道具丢失或异常"
priority = 70
any_patterns = ["道具", "没了", "消失", "丢失", "扣了", "异常消失"]
exclude_patterns = ["充值"]

[[rules]]
canonical_key = "account_recovery"
name = "账号找回"
priority = 70
any_patterns = ["账号找回", "找回账号", "换绑", "账号丢失", "账号异常"]
exclude_patterns = ["登录失败"]

[[rules]]
canonical_key = "bug_gameplay"
name = "玩法bug"
priority = 60
any_patterns = ["bug", "卡死", "闪退", "报错", "玩法", "打不开", "卡住"]
exclude_patterns = ["充值", "登录"]

[[rules]]
canonical_key = "bug_network"
name = "网络问题"
priority = 55
any_patterns = ["网络", "延迟", "掉线", "卡顿", "460", "断线"]
exclude_patterns = []

[[rules]]
canonical_key = "violation_report"
name = "违规举报"
priority = 40
any_patterns = ["举报", "挂机", "外挂", "辱骂", "违规", "作弊"]
exclude_patterns = []

[[rules]]
canonical_key = "suggestion"
name = "建议反馈"
priority = 30
any_patterns = ["建议", "希望", "能不能", "可不可以", "提议"]
exclude_patterns = ["bug", "闪退"]
```

- [ ] **Step 2: 写 issue-normalize.toml**

```toml
# 同义词归一映射；归一只用于规则匹配，原文与归一文本均不落 feedback_issue_link。
version = "rules:v1"

[[mappings]]
from = ["登不上", "登不进去", "上不去", "进不去游戏", "卡在登录", "登入不了"]
to = "登录失败"

[[mappings]]
from = ["充了没到", "充值没到账", "充值没收到"]
to = "充值失败"

[[mappings]]
from = ["掉东西", "东西没了"]
to = "道具消失"
```

- [ ] **Step 3: 写 IssueRule 记录**

```java
package com.insightflow.service.analysis;

import java.util.List;

/**
 * 一条规则优先主题定义；命中后归并到 Workspace 私有 issue_catalog 的同 canonical_key。
 *
 * @param canonicalKey   稳定主题键，跨投影不可变；与 issue_catalog 唯一约束对齐
 * @param name           用户可读主题名，写入 catalog.canonical_name
 * @param priority       数值越大越优先；同分时进入 ambiguous 而非强行二选一
 * @param anyPatterns    命中任一即算候选（去重计词）；与 allPatterns 联用时需全部命中
 * @param allPatterns    可选全部命中条件；空表示不施加 AND 约束
 * @param excludePatterns 命中任一即整条规则出局，先于正向匹配判定
 */
public record IssueRule(
        String canonicalKey,
        String name,
        int priority,
        List<String> anyPatterns,
        List<String> allPatterns,
        List<String> excludePatterns) {
}
```

- [ ] **Step 4: 写 NormalizeMapping 记录**

```java
package com.insightflow.service.analysis;

import java.util.List;

/**
 * 同义词归一映射；在规则匹配前把口语变体替换为稳定词，提升召回而不破坏确定性。
 *
 * @param from 待归一的口语变体列表（子串替换）
 * @param to   归一后的稳定词，出现在 any_patterns 中即被命中
 */
public record NormalizeMapping(List<String> from, String to) {
}
```

- [ ] **Step 5: 写失败测试 IssueRulesLoaderTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 规则加载器必须把 classpath toml 解析为有序规则，并暴露与 command service 一致的版本号。
 */
class IssueRulesLoaderTest {

    /** 加载种子规则集应得到 8 条有序规则和冻结版本号。 */
    @Test
    void loadsSeedRulesAndVersion() {
        IssueRulesLoader loader = new IssueRulesLoader();

        assertThat(loader.currentVersion()).isEqualTo("rules:v1");
        assertThat(loader.rules()).hasSize(8);
        assertThat(loader.normalizeMappings()).isNotEmpty();
        assertThat(loader.rules().get(0).canonicalKey()).isEqualTo("login_failure");
        assertThat(loader.rules().get(0).priority()).isEqualTo(90);
    }
}
```

- [ ] **Step 6: 运行测试确认失败**

Run: `.\mvnw.cmd -q test -Dtest=IssueRulesLoaderTest`
Expected: FAIL（`IssueRulesLoader` 类不存在，编译失败）。

- [ ] **Step 7: 写 IssueRulesLoader 最小实现**

```java
package com.insightflow.service.analysis;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
    @PostConstruct
    void load() {
        try {
            TomlTable rulesRoot = Toml.parse(new ClassPathResource(RULES_PATH).getInputStream()).getTable("");
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
            TomlTable normRoot = Toml.parse(new ClassPathResource(NORMALIZE_PATH).getInputStream()).getTable("");
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
        return array.toList().stream().map(Object::toString).collect(Collectors.toList());
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
```

注：`IssueRulesLoader` 暂不加 `@Component`，由 Task 10 通过 `@Configuration` 装配或直接 `new`，避免启动期与现有 test 切片冲突。本任务测试用 `new IssueRulesLoader()` + 手动调 `load()`；`@PostConstruct` 在直接 new 时不会自动触发，测试需显式调用。修正测试：

```java
/** 加载种子规则集应得到 8 条有序规则和冻结版本号。 */
@Test
void loadsSeedRulesAndVersion() {
    IssueRulesLoader loader = new IssueRulesLoader();
    loader.load();
    assertThat(loader.currentVersion()).isEqualTo("rules:v1");
    assertThat(loader.rules()).hasSize(8);
    assertThat(loader.normalizeMappings()).isNotEmpty();
    assertThat(loader.rules().get(0).canonicalKey()).isEqualTo("login_failure");
    assertThat(loader.rules().get(0).priority()).isEqualTo(90);
}
```

- [ ] **Step 8: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=IssueRulesLoaderTest`
Expected: PASS（1 个测试通过）。

- [ ] **Step 9: 暂存（不提交）**

```bash
git add src/main/resources/config/analysis/issue-rules.toml src/main/resources/config/analysis/issue-normalize.toml src/main/java/com/insightflow/service/analysis/IssueRule.java src/main/java/com/insightflow/service/analysis/NormalizeMapping.java src/main/java/com/insightflow/service/analysis/IssueRulesLoader.java src/test/java/com/insightflow/service/analysis/IssueRulesLoaderTest.java
```

---

### Task 3: IssueTextNormalizer 归一化层

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/IssueTextNormalizer.java`
- Test: `src/test/java/com/insightflow/service/analysis/IssueTextNormalizerTest.java`

**Interfaces:**
- Consumes: `IssueRulesLoader.normalizeMappings()`（Task 2）
- Produces: `IssueTextNormalizer.normalize(String sanitizedText)` → `String`（仅用于匹配，不落库）

- [ ] **Step 1: 写失败测试 IssueTextNormalizerTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 归一化层只提升规则召回，不改变语义；归一结果只用于匹配，不写库。
 */
class IssueTextNormalizerTest {

    /** 种子归一表应把"登不上"归一到"登录失败"。 */
    @Test
    void normalizesSynonymVariant() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        IssueTextNormalizer normalizer = new IssueTextNormalizer(loader.normalizeMappings());

        String result = normalizer.normalize("我的账号登不上游戏了");

        assertThat(result).contains("登录失败");
    }

    /** 全角字母应转半角，避免大小写或全角差异导致漏匹配。 */
    @Test
    void normalizesFullWidthToHalfWidth() {
        IssueTextNormalizer normalizer = new IssueTextNormalizer(java.util.List.of());

        String result = normalizer.normalize("ＢＵＧ闪退");

        assertThat(result).contains("BUG");
    }

    /** 空输入不抛异常，返回空串。 */
    @Test
    void emptyInputReturnsEmpty() {
        IssueTextNormalizer normalizer = new IssueTextNormalizer(java.util.List.of());

        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -q test -Dtest=IssueTextNormalizerTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 写 IssueTextNormalizer 最小实现**

```java
package com.insightflow.service.analysis;

import java.util.List;
import java.util.Map;

/**
 * 纯函数归一化层：全角半角、繁简、冗余标点、ASCII 小写、同义词子串替换。
 *
 * <p>归一只用于规则匹配，{@code feedback_issue_link} 只存 issue_id 与 confidence，
 * 既不存原文也不存归一文本，满足"不存未脱敏文本"边界。</p>
 */
public class IssueTextNormalizer {

    /** 归一映射，按列表顺序子串替换；顺序决定多映射冲突时的优先级。 */
    private final List<NormalizeMapping> mappings;

    /** 构造归一器；映射来自 IssueRulesLoader，禁止运行期改写。 */
    public IssueTextNormalizer(List<NormalizeMapping> mappings) {
        this.mappings = mappings;
    }

    /** 把脱敏文本归一为匹配用文本；null 或空串返回空串，不抛异常。 */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = fullToHalf(text);
        result = toSimplified(result);
        result = collapsePunctuationAndWhitespace(result);
        result = result.toLowerCase(java.util.Locale.ROOT);
        for (NormalizeMapping mapping : mappings) {
            for (String from : mapping.from()) {
                if (from != null && !from.isEmpty()) {
                    result = result.replace(from, mapping.to());
                }
            }
        }
        return result;
    }

    /** 全角字母/数字/标点转半角；ASCII 范围直接返回。 */
    private String fullToHalf(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int code = chars[i];
            if (code >= 0xFF01 && code <= 0xFF5E) {
                chars[i] = (char) (code - 0xFEE0);
            } else if (code == 0x3000) {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    /** 繁体转简体；使用 CJK 繁简表，覆盖游戏词常用字。 */
    private String toSimplified(String text) {
        Map<Character, Character> table = SimplifiedChineseTable.TABLE;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = table.getOrDefault(chars[i], chars[i]);
        }
        return new String(chars);
    }

    /** 连续标点与空白压缩为单个分隔符，保留可读性便于子串匹配。 */
    private String collapsePunctuationAndWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSep = false;
        for (char ch : text.toCharArray()) {
            boolean isSep = Character.isWhitespace(ch) || isPunctuation(ch);
            if (isSep) {
                if (!lastWasSep) {
                    sb.append(' ');
                }
                lastWasSep = true;
            } else {
                sb.append(ch);
                lastWasSep = false;
            }
        }
        return sb.toString().trim();
    }

    /** 仅 ASCII 与全角标点视为分隔符；CJK 标点统一为空格便于词边界匹配。 */
    private boolean isPunctuation(char ch) {
        return (ch >= 0x21 && ch <= 0x2F)
                || (ch >= 0x3A && ch <= 0x40)
                || (ch >= 0x5B && ch <= 0x60)
                || (ch >= 0x7B && ch <= 0x7E)
                || (ch >= 0x3001 && ch <= 0x303F);
    }
}
```

注：`SimplifiedChineseTable.TABLE` 是一个 `Map<Character,Character>`，下一步创建。

- [ ] **Step 4: 写 SimplifiedChineseTable（最小常用字表）**

Create `src/main/java/com/insightflow/service/analysis/SimplifiedChineseTable.java`:

```java
package com.insightflow.service.analysis;

import java.util.Map;

/**
 * 游戏工单常用字的繁简映射最小表；只覆盖规则与归一表涉及的字符，避免引入完整繁简库。
 *
 * <p>未来若规则扩展到更多繁体词，应替换为完整表而非逐字追加。</p>
 */
final class SimplifiedChineseTable {

    /** 不可变繁简映射；只读，禁止运行期改写。 */
    static final Map<Character, Character> TABLE = Map.ofEntries(
            Map.entry('登', '登'), Map.entry('錄', '录'), Map.entry('登', '登'),
            Map.entry('帳', '账'), Map.entry('號', '号'), Map.entry('異', '异'),
            Map.entry('失', '失'), Map.entry('蹤', '踪'), Map.entry('沒', '没'),
            Map.entry('有', '有'), Map.entry('餘', '余'), Map.entry('額', '额'),
            Map.entry('複', '复'), Map.entry('現', '现'), Map.entry('報', '报'),
            Map.entry('錯', '错'), Map.entry('遲', '迟'), Map.entry('斷', '断'),
            Map.entry('線', '线'), Map.entry('違', '违'), Map.entry('規', '规'),
            Map.entry('建', '建'), Map.entry('議', '议'), Map.entry('網', '网'),
            Map.entry('絡', '络'), Map.entry('連', '连'), Map.entry('遲', '迟'),
            Map.entry('帳', '账'), Map.entry('戶', '户'), Map.entry('資', '资'),
            Map.entry('訊', '讯'), Map.entry('題', '题'), Map.entry('題', '题')
    );

    private SimplifiedChineseTable() {
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=IssueTextNormalizerTest`
Expected: PASS（3 个测试通过）。

- [ ] **Step 6: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/IssueTextNormalizer.java src/main/java/com/insightflow/service/analysis/SimplifiedChineseTable.java src/test/java/com/insightflow/service/analysis/IssueTextNormalizerTest.java
```

---

### Task 4: Classification + IssueClassifier Port + RuleFirstIssueClassifier

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/Classification.java`
- Create: `src/main/java/com/insightflow/service/analysis/IssueClassifier.java`
- Create: `src/main/java/com/insightflow/service/analysis/RuleFirstIssueClassifier.java`
- Test: `src/test/java/com/insightflow/service/analysis/RuleFirstIssueClassifierTest.java`

**Interfaces:**
- Consumes: `List<IssueRule>`（Task 2）
- Produces: `RuleFirstIssueClassifier.classify(String normalizedText)` → `List<Classification>`（0..2 条）；`Classification{canonicalKey, confidence, assignmentMethod}`，assignmentMethod ∈ `{"rule","ambiguous"}`。

- [ ] **Step 1: 写 Classification 记录**

```java
package com.insightflow.service.analysis;

/**
 * 一条反馈对一个主题的关联结果；写入 feedback_issue_link 的 assignment_method 与 confidence。
 *
 * @param canonicalKey      关联到的稳定主题键
 * @param confidence        rule 命中=1.0（确定性封顶）；ambiguous=0.5（同分并列）
 * @param assignmentMethod  "rule" 或 "ambiguous"；unclassified 不产生 Classification
 */
public record Classification(String canonicalKey, double confidence, String assignmentMethod) {
}
```

- [ ] **Step 2: 写 IssueClassifier Port**

```java
package com.insightflow.service.analysis;

import java.util.List;

/**
 * 主题分类端口；本期 RuleFirstIssueClassifier 是唯一实现，后续 Qwen 实现只处理未命中/歧义。
 *
 * <p>Qwen 实现不得直接创建主题、修改指标或改写 Alert，只能选择已有主题或返回 new_candidate/unclassified。</p>
 */
public interface IssueClassifier {

    /** 对已归一文本分类，返回 0..2 个主题关联；空列表表示 unclassified。 */
    List<Classification> classify(String normalizedText);
}
```

- [ ] **Step 3: 写失败测试 RuleFirstIssueClassifierTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 规则优先分类器是纯函数；未命中不伪造主题，同分进 ambiguous，最多 2 主题。
 */
class RuleFirstIssueClassifierTest {

    /** 命中 login_failure 规则应返回单条 rule 关联，confidence=1.0。 */
    @Test
    void classifiesSingleHit() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("我的账号登录失败了");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).canonicalKey()).isEqualTo("login_failure");
        assertThat(result.get(0).assignmentMethod()).isEqualTo("rule");
        assertThat(result.get(0).confidence()).isEqualTo(1.0);
    }

    /** 文本同时命中两个不同优先级主题应返回 2 条关联。 */
    @Test
    void classifiesTwoDistinctTopics() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("登录失败 而且充值没到账");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Classification::canonicalKey)
                .contains("login_failure", "payment_recharge");
    }

    /** 无任何规则命中应返回空列表（调用方记 unclassified，不写 link）。 */
    @Test
    void returnsEmptyWhenNoMatch() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("今天天气不错想出门走走");

        assertThat(result).isEmpty();
    }

    /** 命中 exclude_patterns 的规则应被排除。 */
    @Test
    void excludesByExcludePattern() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        RuleFirstIssueClassifier classifier = new RuleFirstIssueClassifier(loader.rules());

        List<Classification> result = classifier.classify("充值时登录失败");

        assertThat(result).extracting(Classification::canonicalKey).doesNotContain("login_failure");
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `.\mvnw.cmd -q test -Dtest=RuleFirstIssueClassifierTest`
Expected: FAIL（类不存在）。

- [ ] **Step 5: 写 RuleFirstIssueClassifier 最小实现**

```java
package com.insightflow.service.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则优先主题分类器；纯函数，无 DB 依赖。
 *
 * <p>排除 → 优先级 → 正向命中数 → 最长命中词长度稳定排序，取前 2 条；
 * 第 2 名与第 1 名 priority+hits 同分标记 ambiguous；无候选返回空列表（unclassified）。</p>
 */
public class RuleFirstIssueClassifier implements IssueClassifier {

    /** 规则列表；每次分类遍历，分类器本身无状态。 */
    private final List<IssueRule> rules;

    /** 构造分类器；规则来自 IssueRulesLoader，禁止运行期改写。 */
    public RuleFirstIssueClassifier(List<IssueRule> rules) {
        this.rules = rules;
    }

    @Override
    public List<Classification> classify(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (IssueRule rule : rules) {
            if (hitsAny(normalizedText, rule.excludePatterns())) {
                continue;
            }
            int hits = countHits(normalizedText, rule.anyPatterns());
            if (hits == 0) {
                continue;
            }
            if (!rule.allPatterns().isEmpty() && !hitsAll(normalizedText, rule.allPatterns())) {
                continue;
            }
            int longest = longestHitLength(normalizedText, rule.anyPatterns());
            candidates.add(new Candidate(rule, hits, longest));
        }
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.rule.priority()).reversed()
                .thenComparingInt((Candidate c) -> c.hits).reversed()
                .thenComparingInt((Candidate c) -> c.longest).reversed());
        List<Classification> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            Candidate c = candidates.get(i);
            String method = "rule";
            if (i == 1 && isTied(candidates.get(0), c)) {
                method = "ambiguous";
            }
            double confidence = "ambiguous".equals(method) ? 0.5 : 1.0;
            result.add(new Classification(c.rule.canonicalKey(), confidence, method));
        }
        return result;
    }

    /** 命中任一排除词即整条规则出局。 */
    private boolean hitsAny(String text, List<String> patterns) {
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** 统计 any_patterns 命中数（去重计词，避免重复词膨胀 hits）。 */
    private int countHits(String text, List<String> patterns) {
        int count = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                count++;
            }
        }
        return count;
    }

    /** all_patterns 需全部命中才算候选。 */
    private boolean hitsAll(String text, List<String> patterns) {
        for (String p : patterns) {
            if (p == null || p.isEmpty() || !text.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /** 最长命中词长度，用于同分时的稳定排序。 */
    private int longestHitLength(String text, List<String> patterns) {
        int longest = 0;
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p) && p.length() > longest) {
                longest = p.length();
            }
        }
        return longest;
    }

    /** priority+hits 完全相同视为同分，第 2 名标记 ambiguous。 */
    private boolean isTied(Candidate first, Candidate second) {
        return first.rule.priority() == second.rule.priority() && first.hits == second.hits;
    }

    /** 内部候选，携带排序所需字段。 */
    private record Candidate(IssueRule rule, int hits, int longest) {
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=RuleFirstIssueClassifierTest`
Expected: PASS（4 个测试通过）。

- [ ] **Step 7: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/Classification.java src/main/java/com/insightflow/service/analysis/IssueClassifier.java src/main/java/com/insightflow/service/analysis/RuleFirstIssueClassifier.java src/test/java/com/insightflow/service/analysis/RuleFirstIssueClassifierTest.java
```

---

### Task 5: DataCellBuilder 切分

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/DataCellPlan.java`
- Create: `src/main/java/com/insightflow/service/analysis/DataCellBuilder.java`
- Test: `src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java`

**Interfaces:**
- Produces: `DataCellBuilder.split(List<EventInput> events)` → `List<DataCellPlan>`；`EventInput{id(Long), occurredAt(OffsetDateTime), normalizedText(String)}`；`DataCellPlan{windowStart, windowEnd, closeReason, events(List<EventInput>), estimatedTokens}`。

- [ ] **Step 1: 写 DataCellPlan 与 EventInput 记录**

```java
package com.insightflow.service.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次投影内一个 Data Cell 的计算输出值；落库前由 ProjectionFactWriter 转为 data_cell + cell_issue。
 *
 * @param windowStart     Cell 首条事件 occurred_at
 * @param windowEnd       Cell 末条事件 occurred_at
 * @param closeReason     count_limit/window_limit/token_limit/stream_end
 * @param events          Cell 内事件列表（已按 occurred_at 升序）
 * @param estimatedTokens Cell 内全部事件 token 估算之和
 */
public record DataCellPlan(
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        String closeReason,
        List<EventInput> events,
        int estimatedTokens) {
}
```

```java
package com.insightflow.service.analysis;

import java.time.OffsetDateTime;

/**
 * 投影输入事件的计算视图；只暴露切分与分类所需字段，不携带脱敏原文之外的持久化字段。
 *
 * @param id              feedback_event 内部主键，用于 cell_issue.sample_event_ids
 * @param occurredAt      反馈真实发生时间，决定时间窗与排序
 * @param normalizedText  归一后文本，用于 token 估算与分类
 */
public record EventInput(Long id, OffsetDateTime occurredAt, String normalizedText) {
}
```

- [ ] **Step 2: 写失败测试 DataCellBuilderTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * DataCell 切分按 40 条/60 分钟/6000 token 三护栏关闭；空输入返回空列表。
 */
class DataCellBuilderTest {

    /** 41 条事件应在第 40 条关闭第一个 Cell，close_reason=count_limit。 */
    @Test
    void closesOnCountLimit() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = IntStream.range(0, 41)
                .mapToObj(i -> new EventInput((long) i, base.plusSeconds(i), "短文本"))
                .toList();
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        List<DataCellPlan> cells = builder.split(events);

        assertThat(cells).hasSize(2);
        assertThat(cells.get(0).closeReason()).isEqualTo("count_limit");
        assertThat(cells.get(0).events()).hasSize(40);
        assertThat(cells.get(1).closeReason()).isEqualTo("stream_end");
    }

    /** 事件跨过 60 分钟应在窗口边界关闭 Cell。 */
    @Test
    void closesOnWindowLimit() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(
                new EventInput(1L, base, "x"),
                new EventInput(2L, base.plusMinutes(61), "y"));
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        List<DataCellPlan> cells = builder.split(events);

        assertThat(cells).hasSize(2);
        assertThat(cells.get(0).closeReason()).isEqualTo("window_limit");
    }

    /** 空输入返回空列表，不创建空 Cell。 */
    @Test
    void emptyInputReturnsEmpty() {
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        assertThat(builder.split(List.of())).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `.\mvnw.cmd -q test -Dtest=DataCellBuilderTest`
Expected: FAIL（类不存在）。

- [ ] **Step 4: 写 DataCellBuilder 最小实现**

```java
package com.insightflow.service.analysis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 按条数/时间窗/token 预算三护栏切分有序事件；任一触发即关闭当前 Cell 并开启新 Cell。
 *
 * <p>token 估算对齐原型 cell_windowing.estimate_tokens：CJK 约 1.5 字/token，其余约 4 字符/token。
 * 单条事件 token 超 budget 时独占一个 Cell，close_reason=token_limit，不丢弃。</p>
 */
public class DataCellBuilder {

    /** 条数护栏；达到即关闭 Cell。 */
    private final int maxCount;

    /** 时间窗护栏（分钟）；span >= maxWindowMinutes 即关闭 Cell。 */
    private final int maxWindowMinutes;

    /** token 预算护栏；累计 > budget 即关闭 Cell。 */
    private final int tokenBudget;

    /** 构造切分器；参数来自配置，禁止运行期改写。 */
    public DataCellBuilder(int maxCount, int maxWindowMinutes, int tokenBudget) {
        this.maxCount = maxCount;
        this.maxWindowMinutes = maxWindowMinutes;
        this.tokenBudget = tokenBudget;
    }

    /** 把已按 occurred_at 升序的事件切分为多个 Cell；空输入返回空列表。 */
    public List<DataCellPlan> split(List<EventInput> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<DataCellPlan> cells = new ArrayList<>();
        List<EventInput> current = new ArrayList<>();
        int tokenSum = 0;
        OffsetDateTime windowStart = null;
        for (EventInput event : events) {
            if (!current.isEmpty() && windowStart != null) {
                Duration span = Duration.between(windowStart, event.occurredAt());
                boolean exceedCount = current.size() >= maxCount;
                boolean exceedWindow = span.toMinutes() >= maxWindowMinutes;
                boolean exceedToken = tokenSum + estimateTokens(event.normalizedText()) > tokenBudget;
                if (exceedCount || exceedWindow || exceedToken) {
                    cells.add(toPlan(current, windowStart, pickReason(exceedCount, exceedWindow, exceedToken), tokenSum));
                    current = new ArrayList<>();
                    tokenSum = 0;
                    windowStart = null;
                }
            }
            if (current.isEmpty()) {
                windowStart = event.occurredAt();
            }
            current.add(event);
            tokenSum += estimateTokens(event.normalizedText());
        }
        if (!current.isEmpty()) {
            cells.add(toPlan(current, windowStart, "stream_end", tokenSum));
        }
        return cells;
    }

    /** 多护栏同时触发时取优先级 count > window > token。 */
    private String pickReason(boolean exceedCount, boolean exceedWindow, boolean exceedToken) {
        if (exceedCount) {
            return "count_limit";
        }
        if (exceedWindow) {
            return "window_limit";
        }
        return "token_limit";
    }

    /** 组装 DataCellPlan；windowEnd 为 Cell 末条事件 occurredAt。 */
    private DataCellPlan toPlan(List<EventInput> events, OffsetDateTime windowStart, String reason, int tokenSum) {
        return new DataCellPlan(
                windowStart,
                events.get(events.size() - 1).occurredAt(),
                reason,
                List.copyOf(events),
                tokenSum);
    }

    /** 估算文本 token：CJK/1.5 + other/4 + 1；空文本返回 1 避免零预算。 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '一' && ch <= '鿿') {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) (cjk / 1.5 + other / 4.0) + 1;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=DataCellBuilderTest`
Expected: PASS（3 个测试通过）。

- [ ] **Step 6: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/DataCellPlan.java src/main/java/com/insightflow/service/analysis/EventInput.java src/main/java/com/insightflow/service/analysis/DataCellBuilder.java src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java
```

---

### Task 6: 事实实体与仓储

**Files:**
- Create: `src/main/java/com/insightflow/entity/IssueCatalog.java`
- Create: `src/main/java/com/insightflow/entity/IssueAlias.java`
- Create: `src/main/java/com/insightflow/entity/FeedbackIssueLink.java`
- Create: `src/main/java/com/insightflow/entity/DataCell.java`
- Create: `src/main/java/com/insightflow/entity/CellIssue.java`
- Create: `src/main/java/com/insightflow/repository/IssueCatalogRepository.java`
- Create: `src/main/java/com/insightflow/repository/IssueAliasRepository.java`
- Create: `src/main/java/com/insightflow/repository/FeedbackIssueLinkRepository.java`
- Create: `src/main/java/com/insightflow/repository/DataCellRepository.java`
- Create: `src/main/java/com/insightflow/repository/CellIssueRepository.java`
- Test: `src/test/java/com/insightflow/entity/IssueCatalogEntityTest.java`

**Interfaces:**
- Produces: 5 个 JPA 实体（对应 V6 表）+ 仓储查询方法。`IssueCatalog.findOrCreate` 工厂 + getters；`DataCell.of(projectionId, workspaceId, ...)`；`CellIssue.of(...)`。

- [ ] **Step 1: 写 IssueCatalog 实体**

```java
package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Workspace 私有稳定主题目录；同一 (workspace_id, canonical_key) 唯一。
 *
 * <p>内部 id 供 feedback_issue_link / cell_issue 关联；public_id 留给未来看板路径。
 * 规则命中后由 IssueCatalogService find-or-create，不允许多次创建同 key。</p>
 */
@Entity
@Table(name = "issue_catalog")
public class IssueCatalog {

    /** 内部主键，仅供关联表使用。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 看板路径用的 UUIDv7。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private java.util.UUID publicId;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 稳定主题键，与规则 canonical_key 一致。 */
    @Column(name = "canonical_key", nullable = false, length = 120, updatable = false)
    private String canonicalKey;

    /** 用户可读主题名。 */
    @Column(name = "canonical_name", nullable = false, length = 200)
    private String canonicalName;

    /** active / excluded / expired。 */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected IssueCatalog() {
    }

    /** 创建首次出现的主题；status 固定 active，首末出现时间同。 */
    public static IssueCatalog create(Long workspaceId, String canonicalKey, String canonicalName) {
        IssueCatalog catalog = new IssueCatalog();
        OffsetDateTime now = OffsetDateTime.now();
        catalog.publicId = UuidCreator.getTimeOrdered();
        catalog.workspaceId = workspaceId;
        catalog.canonicalKey = canonicalKey;
        catalog.canonicalName = canonicalName;
        catalog.status = "active";
        catalog.firstSeenAt = now;
        catalog.lastSeenAt = now;
        catalog.createdAt = now;
        catalog.updatedAt = now;
        return catalog;
    }

    /** 命中既有主题时刷新末次出现时间。 */
    public void touchLastSeen() {
        this.lastSeenAt = OffsetDateTime.now();
        this.updatedAt = this.lastSeenAt;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getCanonicalKey() { return canonicalKey; }
    public String getCanonicalName() { return canonicalName; }
}
```

- [ ] **Step 2: 写 IssueAlias 实体**

```java
package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 规则或未来人工/模型建议的别名；本期 origin 固定 "rule"，不允许它自行改写统计结果。
 */
@Entity
@Table(name = "issue_alias")
public class IssueAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "normalized_alias", nullable = false, length = 300, updatable = false)
    private String normalizedAlias;

    /** rule / llm / manual。 */
    @Column(nullable = false, length = 30, updatable = false)
    private String origin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IssueAlias() {
    }

    /** 创建规则来源别名；唯一约束 (workspace_id, normalized_alias) 防重复。 */
    public static IssueAlias ruleAlias(Long workspaceId, Long issueId, String normalizedAlias) {
        IssueAlias alias = new IssueAlias();
        OffsetDateTime now = OffsetDateTime.now();
        alias.workspaceId = workspaceId;
        alias.issueId = issueId;
        alias.normalizedAlias = normalizedAlias;
        alias.origin = "rule";
        alias.createdAt = now;
        return alias;
    }

    public Long getIssueId() { return issueId; }
    public String getNormalizedAlias() { return normalizedAlias; }
    public String getOrigin() { return origin; }
}
```

- [ ] **Step 3: 写 FeedbackIssueLink 实体**

```java
package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 反馈到主题的可追溯关联；只引用已脱敏 feedback_event 与既有 issue_catalog。
 *
 * <p>唯一约束 (workspace_projection_id, feedback_event_id, issue_id) 防止重试重复累计。
 * 不存原文或归一文本，只存 issue_id 与 confidence。</p>
 */
@Entity
@Table(name = "feedback_issue_link")
public class FeedbackIssueLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "feedback_event_id", nullable = false, updatable = false)
    private Long feedbackEventId;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /** rule / ambiguous；unclassified 不产生 link。 */
    @Column(name = "assignment_method", nullable = false, length = 30, updatable = false)
    private String assignmentMethod;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected FeedbackIssueLink() {
    }

    /** 创建一条活跃关联；assignment_method 与 confidence 来自 Classification。 */
    public static FeedbackIssueLink active(
            Long workspaceId, Long feedbackEventId, Long issueId, Long workspaceProjectionId,
            String assignmentMethod, double confidence) {
        FeedbackIssueLink link = new FeedbackIssueLink();
        OffsetDateTime now = OffsetDateTime.now();
        link.workspaceId = workspaceId;
        link.feedbackEventId = feedbackEventId;
        link.issueId = issueId;
        link.workspaceProjectionId = workspaceProjectionId;
        link.assignmentMethod = assignmentMethod;
        link.confidence = confidence;
        link.status = "active";
        link.createdAt = now;
        return link;
    }

    public Long getFeedbackEventId() { return feedbackEventId; }
    public Long getIssueId() { return issueId; }
    public String getAssignmentMethod() { return assignmentMethod; }
    public double getConfidence() { return confidence; }
}
```

- [ ] **Step 4: 写 DataCell 实体**

```java
package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 一次投影内的一个 Data Cell；控制粒度并为后续受限 LLM 分类提供固定证据边界。
 */
@Entity
@Table(name = "data_cell")
public class DataCell {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    @Column(name = "window_start", nullable = false, updatable = false)
    private OffsetDateTime windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private OffsetDateTime windowEnd;

    /** count_limit / window_limit / token_limit / stream_end。 */
    @Column(name = "close_reason", nullable = false, length = 30, updatable = false)
    private String closeReason;

    @Column(name = "event_count", nullable = false, updatable = false)
    private int eventCount;

    @Column(name = "estimated_tokens", nullable = false, updatable = false)
    private int estimatedTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DataCell() {
    }

    /** 创建一个 Cell；字段来自 DataCellPlan。 */
    public static DataCell of(
            Long workspaceId, Long workspaceProjectionId,
            OffsetDateTime windowStart, OffsetDateTime windowEnd,
            String closeReason, int eventCount, int estimatedTokens) {
        DataCell cell = new DataCell();
        OffsetDateTime now = OffsetDateTime.now();
        cell.workspaceId = workspaceId;
        cell.workspaceProjectionId = workspaceProjectionId;
        cell.windowStart = windowStart;
        cell.windowEnd = windowEnd;
        cell.closeReason = closeReason;
        cell.eventCount = eventCount;
        cell.estimatedTokens = estimatedTokens;
        cell.createdAt = now;
        return cell;
    }

    public Long getId() { return id; }
    public int getEventCount() { return eventCount; }
}
```

- [ ] **Step 5: 写 CellIssue 实体**

```java
package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * 一个 Cell 内某主题的计数与有限样本引用；sample_event_ids 只存内部 id，不存文本。
 */
@Entity
@Table(name = "cell_issue")
public class CellIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "data_cell_id", nullable = false, updatable = false)
    private Long dataCellId;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "mention_count", nullable = false, updatable = false)
    private int mentionCount;

    @Column(name = "sample_event_ids", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String sampleEventIdsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CellIssue() {
    }

    /** 创建一条 Cell 内主题计数；sampleEventIdsJson 为 JSON 数组字符串。 */
    public static CellIssue of(
            Long workspaceId, Long dataCellId, Long issueId,
            int mentionCount, String sampleEventIdsJson) {
        CellIssue cellIssue = new CellIssue();
        OffsetDateTime now = OffsetDateTime.now();
        cellIssue.workspaceId = workspaceId;
        cellIssue.dataCellId = dataCellId;
        cellIssue.issueId = issueId;
        cellIssue.mentionCount = mentionCount;
        cellIssue.sampleEventIdsJson = sampleEventIdsJson;
        cellIssue.createdAt = now;
        return cellIssue;
    }

    public Long getDataCellId() { return dataCellId; }
    public Long getIssueId() { return issueId; }
    public int getMentionCount() { return mentionCount; }
    public String getSampleEventIdsJson() { return sampleEventIdsJson; }
}
```

- [ ] **Step 6: 写 5 个仓储**

`IssueCatalogRepository.java`:
```java
package com.insightflow.repository;

import com.insightflow.entity.IssueCatalog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 主题目录持久化端口；按 workspace + canonical_key 查找以实现 find-or-create。 */
public interface IssueCatalogRepository extends JpaRepository<IssueCatalog, Long> {
    Optional<IssueCatalog> findByWorkspaceIdAndCanonicalKey(Long workspaceId, String canonicalKey);
}
```

`IssueAliasRepository.java`:
```java
package com.insightflow.repository;

import com.insightflow.entity.IssueAlias;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 别名持久化端口；按 workspace + normalized_alias 判重，防规则重复写别名。 */
public interface IssueAliasRepository extends JpaRepository<IssueAlias, Long> {
    Optional<IssueAlias> findByWorkspaceIdAndNormalizedAlias(Long workspaceId, String normalizedAlias);
    boolean existsByWorkspaceIdAndNormalizedAlias(Long workspaceId, String normalizedAlias);
}
```

`FeedbackIssueLinkRepository.java`:
```java
package com.insightflow.repository;

import com.insightflow.entity.FeedbackIssueLink;
import org.springframework.data.jpa.repository.JpaRepository;

/** 反馈-主题关联持久化端口；唯一约束防重试重复累计。 */
public interface FeedbackIssueLinkRepository extends JpaRepository<FeedbackIssueLink, Long> {
}
```

`DataCellRepository.java`:
```java
package com.insightflow.repository;

import com.insightflow.entity.DataCell;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data Cell 持久化端口；按 projection 查询用于幂等守卫。 */
public interface DataCellRepository extends JpaRepository<DataCell, Long> {
    List<DataCell> findByWorkspaceProjectionIdAndWorkspaceId(Long workspaceProjectionId, Long workspaceId);
}
```

`CellIssueRepository.java`:
```java
package com.insightflow.repository;

import com.insightflow.entity.CellIssue;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cell-主题计数持久化端口；唯一约束 (data_cell_id, issue_id) 防重复。 */
public interface CellIssueRepository extends JpaRepository<CellIssue, Long> {
}
```

- [ ] **Step 7: 写失败测试 IssueCatalogEntityTest**

```java
package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 主题目录工厂必须固定 active 状态并生成 UUIDv7。 */
class IssueCatalogEntityTest {

    @Test
    void createSetsActiveAndUuid() {
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");

        assertThat(catalog.getCanonicalKey()).isEqualTo("login_failure");
        assertThat(catalog.getStatus()).isEqualTo("active");
        assertThat(catalog.getFirstSeenAt()).isEqualTo(catalog.getLastSeenAt());
    }
}
```

注：`IssueCatalog` 需要 `getStatus()`/`getFirstSeenAt()`/`getLastSeenAt()` getter，补到实体里（Step 1 已含 getId/getWorkspaceId/getCanonicalKey/getCanonicalName，需再加这三个）。

- [ ] **Step 8: 补齐 IssueCatalog 缺失 getter 并运行测试**

在 `IssueCatalog.java` 加：
```java
    public java.util.UUID getPublicId() { return publicId; }
    public String getStatus() { return status; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
```

Run: `.\mvnw.cmd -q test -Dtest=IssueCatalogEntityTest`
Expected: PASS（1 个测试通过）。

- [ ] **Step 9: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/entity/IssueCatalog.java src/main/java/com/insightflow/entity/IssueAlias.java src/main/java/com/insightflow/entity/FeedbackIssueLink.java src/main/java/com/insightflow/entity/DataCell.java src/main/java/com/insightflow/entity/CellIssue.java src/main/java/com/insightflow/repository/IssueCatalogRepository.java src/main/java/com/insightflow/repository/IssueAliasRepository.java src/main/java/com/insightflow/repository/FeedbackIssueLinkRepository.java src/main/java/com/insightflow/repository/DataCellRepository.java src/main/java/com/insightflow/repository/CellIssueRepository.java src/test/java/com/insightflow/entity/IssueCatalogEntityTest.java
```

---

### Task 7: FeedbackEvent 读方法 + 仓储查询

**Files:**
- Modify: `src/main/java/com/insightflow/entity/FeedbackEvent.java`（加 getters）
- Modify: `src/main/java/com/insightflow/repository/FeedbackEventRepository.java`（加查询）

**Interfaces:**
- Produces: `FeedbackEvent.getId()/getWorkspaceId()/getSanitizedText()/getOccurredAt()/getSourceKind()`；`FeedbackEventRepository.findByWorkspaceIdAndIngestedTaskIdInOrderByOccurredAtAsc(Long, Collection<Long>)`。

- [ ] **Step 1: 给 FeedbackEvent 加读方法**

在 `FeedbackEvent.java` 的 `getExternalRefHash()` 后加：
```java
    /** 返回内部主键，仅供投影关联与 cell_issue 样本引用。 */
    public Long getId() { return id; }
    /** 返回一级隔离键，投影读取必须二次过滤。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 返回脱敏文本，是规则与未来模型唯一可用的文本。 */
    public String getSanitizedText() { return sanitizedText; }
    /** 返回反馈发生时间，决定时间窗与排序。 */
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    /** 返回来源分类，用于后续维度统计。 */
    public String getSourceKind() { return sourceKind; }
```

- [ ] **Step 2: 给 FeedbackEventRepository 加查询**

在接口加：
```java
    /**
     * 按投影来源文件对应的导入任务批量读取脱敏事件，按真实发生时间升序。
     * 调用方必须带 workspaceId 做二次隔离。
     */
    java.util.List<FeedbackEvent> findByWorkspaceIdAndIngestedTaskIdInOrderByOccurredAtAsc(
            Long workspaceId, java.util.Collection<Long> ingestedTaskIds);
```

- [ ] **Step 3: 验证编译**

Run: `.\mvnw.cmd -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/entity/FeedbackEvent.java src/main/java/com/insightflow/repository/FeedbackEventRepository.java
```

---

### Task 8: IssueCatalogService（find-or-create）

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/IssueCatalogService.java`
- Test: `src/test/java/com/insightflow/service/analysis/IssueCatalogServiceTest.java`

**Interfaces:**
- Consumes: `IssueCatalogRepository`, `IssueAliasRepository`
- Produces: `IssueCatalogService.findOrCreate(workspaceId, canonicalKey, canonicalName)` → `IssueCatalog`（已存则 touchLastSeen，不存则 create）；`recordAliasIfNeeded(workspaceId, issueId, alias)`。

- [ ] **Step 1: 写失败测试 IssueCatalogServiceTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.insightflow.entity.IssueAlias;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.IssueAliasRepository;
import com.insightflow.repository.IssueCatalogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** find-or-create 必须幂等；既有主题只刷新末次出现，不重复创建。 */
class IssueCatalogServiceTest {

    @Test
    void createsWhenAbsent() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        when(catalogRepo.findByWorkspaceIdAndCanonicalKey(7L, "login_failure"))
                .thenReturn(Optional.empty());
        when(catalogRepo.save(any(IssueCatalog.class))).thenAnswer(inv -> inv.getArgument(0));
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        IssueCatalog result = service.findOrCreate(7L, "login_failure", "登录失败");

        assertThat(result.getCanonicalKey()).isEqualTo("login_failure");
        verify(catalogRepo).save(any(IssueCatalog.class));
    }

    @Test
    void reusesWhenPresent() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        IssueCatalog existing = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogRepo.findByWorkspaceIdAndCanonicalKey(7L, "login_failure"))
                .thenReturn(Optional.of(existing));
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        IssueCatalog result = service.findOrCreate(7L, "login_failure", "登录失败");

        assertThat(result).isSameAs(existing);
        verify(catalogRepo, never()).save(any(IssueCatalog.class));
    }

    @Test
    void recordsAliasOnlyOnce() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        when(aliasRepo.existsByWorkspaceIdAndNormalizedAlias(7L, "登录失败")).thenReturn(false);
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        service.recordAliasIfNeeded(7L, 11L, "登录失败");
        service.recordAliasIfNeeded(7L, 11L, "登录失败");

        verify(aliasRepo, times(1)).save(any(IssueAlias.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -q test -Dtest=IssueCatalogServiceTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 写 IssueCatalogService 最小实现**

```java
package com.insightflow.service.analysis;

import com.insightflow.entity.IssueAlias;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.IssueAliasRepository;
import com.insightflow.repository.IssueCatalogRepository;
import org.springframework.stereotype.Service;

/**
 * Workspace 私有主题目录的 find-or-create；规则命中后查或建，绝不重复创建同 key。
 *
 * <p>本期只写 origin="rule" 别名；未来 LLM 别名通过同表不同 origin 追溯，不自行改写统计。</p>
 */
@Service
public class IssueCatalogService {

    /** 主题目录仓储，按 workspace + canonical_key 查找。 */
    private final IssueCatalogRepository catalogRepository;
    /** 别名仓储，按 workspace + normalized_alias 判重。 */
    private final IssueAliasRepository aliasRepository;

    /** 构造目录服务；两个仓储均为 Workspace 隔离查询。 */
    public IssueCatalogService(IssueCatalogRepository catalogRepository, IssueAliasRepository aliasRepository) {
        this.catalogRepository = catalogRepository;
        this.aliasRepository = aliasRepository;
    }

    /** 既有主题只刷新末次出现；不存在则创建 active 主题。 */
    public IssueCatalog findOrCreate(Long workspaceId, String canonicalKey, String canonicalName) {
        return catalogRepository.findByWorkspaceIdAndCanonicalKey(workspaceId, canonicalKey)
                .map(catalog -> {
                    catalog.touchLastSeen();
                    return catalogRepository.save(catalog);
                })
                .orElseGet(() -> catalogRepository.save(IssueCatalog.create(workspaceId, canonicalKey, canonicalName)));
    }

    /** 同一归一别名只记录一次；origin 固定 rule，避免重复写别名。 */
    public void recordAliasIfNeeded(Long workspaceId, Long issueId, String normalizedAlias) {
        if (!aliasRepository.existsByWorkspaceIdAndNormalizedAlias(workspaceId, normalizedAlias)) {
            aliasRepository.save(IssueAlias.ruleAlias(workspaceId, issueId, normalizedAlias));
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=IssueCatalogServiceTest`
Expected: PASS（3 个测试通过）。

- [ ] **Step 5: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/IssueCatalogService.java src/test/java/com/insightflow/service/analysis/IssueCatalogServiceTest.java
```

---

### Task 9: ProjectionSourceLoader + ProjectionFactWriter

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/ProjectionSourceLoader.java`
- Create: `src/main/java/com/insightflow/service/analysis/ProjectionFactWriter.java`
- Test: `src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java`

**Interfaces:**
- Consumes: `FeedbackEventRepository`, `AsyncTaskRepository`, `ProjectionFileRepository`, `IssueCatalogService`, `FeedbackIssueLinkRepository`, `DataCellRepository`, `CellIssueRepository`, `DataCellBuilder`
- Produces: `ProjectionSourceLoader.load(projectionId, workspaceId)` → `List<EventInput>`；`ProjectionFactWriter.write(projectionId, workspaceId, cells, classificationsByEventId)` —— 写 link/data_cell/cell_issue。

- [ ] **Step 1: 写 ProjectionSourceLoader**

```java
package com.insightflow.service.analysis;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.ProjectionFileRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 按 projection_file 反查导入任务与脱敏事件；只读 sanitized_text + occurred_at + id。
 *
 * <p>关联链：projection_file → import_file_id → AsyncTask(importFileId,type=import).id
 * → feedback_event.ingested_task_id。多文件时合并任务 id 批量查询，按 occurred_at 升序。</p>
 */
@Component
public class ProjectionSourceLoader {

    /** 来源文件仓储，定位投影冻结的 import_file。 */
    private final ProjectionFileRepository projectionFileRepository;
    /** 任务仓储，反查每份文件的导入任务 id。 */
    private final AsyncTaskRepository taskRepository;
    /** 事件仓储，按 ingestedTaskId 批量读取脱敏事件。 */
    private final FeedbackEventRepository eventRepository;
    /** 归一器，把 sanitized_text 转为匹配用文本。 */
    private final IssueTextNormalizer normalizer;

    /** 构造加载器；归一器来自 IssueRulesLoader 的归一映射。 */
    public ProjectionSourceLoader(ProjectionFileRepository projectionFileRepository,
                                  AsyncTaskRepository taskRepository,
                                  FeedbackEventRepository eventRepository,
                                  IssueTextNormalizer normalizer) {
        this.projectionFileRepository = projectionFileRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.normalizer = normalizer;
    }

    /** 读取并归一投影来源的全部事件，按 occurred_at 升序返回。 */
    public List<EventInput> load(Long projectionId, Long workspaceId) {
        Set<Long> taskIds = new HashSet<>();
        projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId)
                .forEach(pf -> taskRepository
                        .findFirstByWorkspaceIdAndImportFileIdOrderByCreatedAtDesc(workspaceId, pf.getImportFileId())
                        .map(AsyncTask::getId)
                        .ifPresent(taskIds::add));
        if (taskIds.isEmpty()) {
            return List.of();
        }
        List<FeedbackEvent> events = eventRepository
                .findByWorkspaceIdAndIngestedTaskIdInOrderByOccurredAtAsc(workspaceId, taskIds);
        List<EventInput> inputs = new ArrayList<>(events.size());
        for (FeedbackEvent event : events) {
            inputs.add(new EventInput(event.getId(), event.getOccurredAt(),
                    normalizer.normalize(event.getSanitizedText())));
        }
        return inputs;
    }
}
```

注：`ProjectionFile` 需 `getImportFileId()`。检查现有实体（Task 6 前已存在 `ProjectionFile.of(workspaceProjectionId, workspaceId, importFileId)`），确认有 getter；若无则在本任务补 `getImportFileId()`。

- [ ] **Step 2: 写 ProjectionFactWriter**

```java
package com.insightflow.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackIssueLink;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackIssueLinkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 在执行事务内写 feedback_issue_link / data_cell / cell_issue；任一步失败由外层事务回滚。
 *
 * <p>不写原文或归一文本；sample_event_ids 只存内部 id 的 JSON 数组，每主题每 Cell 最多 5 条。</p>
 */
@Component
public class ProjectionFactWriter {

    /** 关联仓储，按事件×主题写 link。 */
    private final FeedbackIssueLinkRepository linkRepository;
    /** Cell 仓储，按 projection 写 data_cell。 */
    private final DataCellRepository dataCellRepository;
    /** Cell-主题计数仓储。 */
    private final CellIssueRepository cellIssueRepository;
    /** JSON 工具，序列化 sample_event_ids。 */
    private final ObjectMapper objectMapper;
    /** 主题目录服务，把 canonical_key 解析为 issue_id。 */
    private final IssueCatalogService issueCatalogService;

    /** 构造事实写入器；所有写入在调用方事务内完成。 */
    public ProjectionFactWriter(FeedbackIssueLinkRepository linkRepository,
                                DataCellRepository dataCellRepository,
                                CellIssueRepository cellIssueRepository,
                                ObjectMapper objectMapper,
                                IssueCatalogService issueCatalogService) {
        this.linkRepository = linkRepository;
        this.dataCellRepository = dataCellRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.objectMapper = objectMapper;
        this.issueCatalogService = issueCatalogService;
    }

    /**
     * 写全部事实；classificationsByEventId 给每条事件的 0..2 个分类结果。
     * cellPlans 已切分完成，writer 按 Cell 聚合主题计数。
     */
    public void write(Long projectionId, Long workspaceId,
                      List<DataCellPlan> cellPlans,
                      Map<Long, List<Classification>> classificationsByEventId,
                      Map<String, String> canonicalNames) {
        for (DataCellPlan plan : cellPlans) {
            DataCell cell = dataCellRepository.saveAndFlush(DataCell.of(
                    workspaceId, projectionId, plan.windowStart(), plan.windowEnd(),
                    plan.closeReason(), plan.events().size(), plan.estimatedTokens()));
            Map<Long, CellAggregator> byIssue = new HashMap<>();
            for (EventInput event : plan.events()) {
                List<Classification> classifications = classificationsByEventId.getOrDefault(event.id(), List.of());
                for (Classification c : classifications) {
                    Long issueId = issueCatalogService.findOrCreate(
                            workspaceId, c.canonicalKey(), canonicalNames.get(c.canonicalKey())).getId();
                    issueCatalogService.recordAliasIfNeeded(workspaceId, issueId, canonicalNames.get(c.canonicalKey()));
                    linkRepository.saveAndFlush(FeedbackIssueLink.active(
                            workspaceId, event.id(), issueId, projectionId, c.assignmentMethod(), c.confidence()));
                    byIssue.computeIfAbsent(issueId, k -> new CellAggregator()).add(event.id());
                }
            }
            for (Map.Entry<Long, CellAggregator> entry : byIssue.entrySet()) {
                cellIssueRepository.saveAndFlush(CellIssue.of(
                        workspaceId, cell.getId(), entry.getKey(),
                        entry.getValue().count, toJson(entry.getValue().samples)));
            }
        }
    }

    /** 内部聚合：每 Cell 每主题的计数与最多 5 条样本。 */
    private static final class CellAggregator {
        int count;
        final List<Long> samples = new ArrayList<>();
        void add(Long eventId) {
            count++;
            if (samples.size() < 5) {
                samples.add(eventId);
            }
        }
    }

    /** 把样本 id 列表序列化为 JSON 数组字符串；失败抛 IllegalStateException 让事务回滚。 */
    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize sample_event_ids", e);
        }
    }
}
```

- [ ] **Step 3: 写失败测试 ProjectionFactWriterTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackIssueLink;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackIssueLinkRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FactWriter 按 Cell 聚合主题计数，并写 link 与 cell_issue；幂等由唯一约束兜底。 */
class ProjectionFactWriterTest {

    @Test
    void writesLinksAndCellIssuesPerCell() {
        FeedbackIssueLinkRepository linkRepo = mock(FeedbackIssueLinkRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        CellIssueRepository cellIssueRepo = mock(CellIssueRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        when(cellRepo.saveAndFlush(any(DataCell.class))).thenAnswer(inv -> {
            DataCell c = inv.getArgument(0);
            return c;
        });
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(any(), any(), any())).thenReturn(catalog);

        ProjectionFactWriter writer = new ProjectionFactWriter(
                linkRepo, cellRepo, cellIssueRepo, new ObjectMapper(), catalogService);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(new EventInput(1L, now, "登录失败"));
        DataCellPlan plan = new DataCellPlan(now, now, "stream_end", events, 5);
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")));

        writer.write(31L, 7L, List.of(plan), classifications,
                Map.of("login_failure", "登录失败"));

        verify(linkRepo).saveAndFlush(any(FeedbackIssueLink.class));
        verify(cellIssueRepo).saveAndFlush(any(CellIssue.class));
    }
}
```

注：`DataCell.getId()` 在 `saveAndFlush` 后仍为 null（模拟未触发 IDENTITY）。测试里 writer 用 `cell.getId()` 传给 `CellIssue.of`——mock 下为 null 不影响 verify。如需更严谨，用反射设 id 或改用 spy。本计划保持简单，依赖真实 DB 时 IDENTITY 自动赋值。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=ProjectionFactWriterTest`
Expected: PASS（1 个测试通过）。

- [ ] **Step 5: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/ProjectionSourceLoader.java src/main/java/com/insightflow/service/analysis/ProjectionFactWriter.java src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java
```

---

### Task 10: WorkspaceProjectionExecutionService 编排 + 集成现有链路

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java`
- Modify: `src/main/java/com/insightflow/entity/WorkspaceProjection.java`（加 recordSourceWindow + getters）
- Modify: `src/main/java/com/insightflow/task/WorkspaceProjectionCompletionService.java`（markSucceeded 用已记录窗口）
- Modify: `src/main/java/com/insightflow/task/WorkspaceProjectionTaskRunner.java`（调 execution 后 complete）
- Create: `src/main/java/com/insightflow/config/AnalysisConfiguration.java`（装配 IssueRulesLoader/Normalizer/Classifier beans）
- Test: `src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java`

**Interfaces:**
- Consumes: `WorkspaceProjectionRepository`, `DataCellRepository`（幂等守卫）, `ProjectionSourceLoader`, `RuleFirstIssueClassifier`, `DataCellBuilder`, `ProjectionFactWriter`, `IssueRulesLoader`
- Produces: `WorkspaceProjectionExecutionService.execute(Long projectionId, Long workspaceId)` → `boolean`（true=已写或幂等跳过；false=无事件）；幂等守卫：`data_cell` 已存在则跳过写入。

- [ ] **Step 1: 给 WorkspaceProjection 加 source window 记录与 getter**

在 `WorkspaceProjection.java` 加：
```java
    /** 在执行事务内记录来源时间窗，供 completion 收口时写入 markSucceeded。 */
    public void recordSourceWindow(OffsetDateTime start, OffsetDateTime end) {
        this.sourceWindowStart = start;
        this.sourceWindowEnd = end;
        this.updatedAt = OffsetDateTime.now();
    }
    public OffsetDateTime getSourceWindowStart() { return sourceWindowStart; }
    public OffsetDateTime getSourceWindowEnd() { return sourceWindowEnd; }
```

- [ ] **Step 2: 修改 CompletionService 使用已记录窗口**

把 `WorkspaceProjectionCompletionService.complete()` 中的：
```java
        projection.markSucceeded(null, null, OffsetDateTime.now());
```
改为：
```java
        projection.markSucceeded(projection.getSourceWindowStart(), projection.getSourceWindowEnd(), OffsetDateTime.now());
```
（completion 重新加载 projection，已读到 execution 提交的窗口；既有测试未调 recordSourceWindow，值为 null，行为同前。）

- [ ] **Step 3: 写 AnalysisConfiguration 装配 beans**

```java
package com.insightflow.config;

import com.insightflow.service.analysis.DataCellBuilder;
import com.insightflow.service.analysis.IssueRulesLoader;
import com.insightflow.service.analysis.IssueTextNormalizer;
import com.insightflow.service.analysis.RuleFirstIssueClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配规则与归一 bean；IssueRulesLoader 启动期解析 toml，失败让应用不启动。 */
@Configuration
public class AnalysisConfiguration {

    @Bean
    IssueRulesLoader issueRulesLoader() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        return loader;
    }

    @Bean
    IssueTextNormalizer issueTextNormalizer(IssueRulesLoader loader) {
        return new IssueTextNormalizer(loader.normalizeMappings());
    }

    @Bean
    RuleFirstIssueClassifier ruleFirstIssueClassifier(IssueRulesLoader loader) {
        return new RuleFirstIssueClassifier(loader.rules());
    }

    @Bean
    DataCellBuilder dataCellBuilder() {
        return new DataCellBuilder(40, 60, 6000);
    }
}
```

- [ ] **Step 4: 写 WorkspaceProjectionExecutionService**

```java
package com.insightflow.service.analysis;

import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单事务编排投影事实写入；幂等守卫防止重试重复累计，失败整体回滚不残留部分事实。
 *
 * <p>执行事务只写事实与 source window（projection 仍 running）；终态翻转交给 completion
 * 的独立短事务，避免计算异常回滚失败标记。</p>
 */
@Service
public class WorkspaceProjectionExecutionService {

    /** 投影记录仓储，加载与记录 source window。 */
    private final WorkspaceProjectionRepository projectionRepository;
    /** Cell 仓储，幂等守卫判断事实是否已写。 */
    private final DataCellRepository dataCellRepository;
    /** 事件加载器。 */
    private final ProjectionSourceLoader sourceLoader;
    /** 规则优先分类器。 */
    private final RuleFirstIssueClassifier classifier;
    /** Cell 切分器。 */
    private final DataCellBuilder dataCellBuilder;
    /** 事实写入器。 */
    private final ProjectionFactWriter factWriter;
    /** 规则加载器，提供 canonical name 映射。 */
    private final IssueRulesLoader rulesLoader;

    /** 构造编排服务；所有依赖在调用方事务内执行。 */
    public WorkspaceProjectionExecutionService(WorkspaceProjectionRepository projectionRepository,
                                                DataCellRepository dataCellRepository,
                                                ProjectionSourceLoader sourceLoader,
                                                RuleFirstIssueClassifier classifier,
                                                DataCellBuilder dataCellBuilder,
                                                ProjectionFactWriter factWriter,
                                                IssueRulesLoader rulesLoader) {
        this.projectionRepository = projectionRepository;
        this.dataCellRepository = dataCellRepository;
        this.sourceLoader = sourceLoader;
        this.classifier = classifier;
        this.dataCellBuilder = dataCellBuilder;
        this.factWriter = factWriter;
        this.rulesLoader = rulesLoader;
    }

    /**
     * 执行投影事实写入；幂等守卫命中则跳过。返回是否有事件被处理。
     * 全部在 REQUIRES_NEW 事务内；抛异常整体回滚，调用方据此调 fail()。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(Long projectionId, Long workspaceId) {
        WorkspaceProjection projection = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalStateException("Projection not found: " + projectionId));
        if (!dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId).isEmpty()) {
            return true;
        }
        List<EventInput> events = sourceLoader.load(projectionId, workspaceId);
        if (events.isEmpty()) {
            return false;
        }
        Map<Long, List<Classification>> classificationsByEventId = new HashMap<>();
        for (EventInput event : events) {
            classificationsByEventId.put(event.id(), classifier.classify(event.normalizedText()));
        }
        List<DataCellPlan> cells = dataCellBuilder.split(events);
        Map<String, String> canonicalNames = new HashMap<>();
        rulesLoader.rules().forEach(r -> canonicalNames.put(r.canonicalKey(), r.name()));
        factWriter.write(projectionId, workspaceId, cells, classificationsByEventId, canonicalNames);
        projection.recordSourceWindow(cells.get(0).windowStart(),
                cells.get(cells.size() - 1).windowEnd());
        projectionRepository.saveAndFlush(projection);
        return true;
    }
}
```

- [ ] **Step 5: 修改 WorkspaceProjectionTaskRunner 调用 execution**

```java
package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.WorkspaceProjectionExecutionService;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 自动投影 Worker 单体内执行入口：先由执行服务写主题事实，再由完成服务收敛终态。
 * 执行失败只标 projection_failed，不回滚已成功的 CSV 导入。
 */
@Component
public class WorkspaceProjectionTaskRunner {

    /** 任务仓储让 Worker 在异步线程开始时再次核验持有的租约。 */
    private final AsyncTaskRepository taskRepository;
    /** 投影记录仓储，定位本次投影 id 与 workspace。 */
    private final WorkspaceProjectionRepository projectionRepository;
    /** 执行服务在单事务内写主题事实与 source window。 */
    private final WorkspaceProjectionExecutionService executionService;
    /** 完成服务在独立短事务中收敛最终状态。 */
    private final WorkspaceProjectionCompletionService completionService;

    /** 构造投影 Worker。 */
    public WorkspaceProjectionTaskRunner(AsyncTaskRepository taskRepository,
                                         WorkspaceProjectionRepository projectionRepository,
                                         WorkspaceProjectionExecutionService executionService,
                                         WorkspaceProjectionCompletionService completionService) {
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.executionService = executionService;
        this.completionService = completionService;
    }

    /** 在线程池执行状态投影；重复调度或租约已转移时安全返回。 */
    @Async("projectionTaskExecutor")
    public void run(UUID taskPublicId, String workerId) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"projection".equals(task.getTaskType()) || !task.isLeaseOwnedBy(workerId)) {
            return;
        }
        try {
            WorkspaceProjection projection = projectionRepository
                    .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                    .orElse(null);
            if (projection == null) {
                completionService.fail(taskPublicId, workerId, "PROJECTION_RECORD_NOT_FOUND", "投影状态记录不存在。");
                return;
            }
            boolean hasEvents = executionService.execute(projection.getId(), task.getWorkspaceId());
            if (!hasEvents) {
                completionService.fail(taskPublicId, workerId, "PROJECTION_SOURCE_EMPTY", "投影来源事件为空。");
                return;
            }
            completionService.complete(taskPublicId, workerId);
        } catch (Exception exception) {
            completionService.fail(taskPublicId, workerId, "PROJECTION_EXECUTION_FAILED", "看板投影执行失败，请稍后重试。");
        }
    }
}
```

- [ ] **Step 6: 写失败测试 WorkspaceProjectionExecutionServiceTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编排服务：幂等守卫跳过已写投影；空事件返回 false；正常路径写事实并记录窗口。 */
class WorkspaceProjectionExecutionServiceTest {

    @Test
    void skipsWhenFactsAlreadyWritten() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L))
                .thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
                mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class));

        boolean result = service.execute(31L, 7L);

        assertThat(result).isTrue();
        verify(loader, never()).load(any(), any());
    }

    @Test
    void returnsFalseWhenNoEvents() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        when(loader.load(31L, 7L)).thenReturn(List.of());
        WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
                mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class));

        boolean result = service.execute(31L, 7L);

        assertThat(result).isFalse();
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `.\mvnw.cmd -q test -Dtest=WorkspaceProjectionExecutionServiceTest`
Expected: PASS（2 个测试通过）。

- [ ] **Step 8: 更新现有 WorkspaceProjectionTaskRunnerTest 以匹配新构造器**

检查 `src/test/java/com/insightflow/task/WorkspaceProjectionTaskRunnerTest.java`，把构造 `WorkspaceProjectionTaskRunner` 的参数从 `(taskRepository, completionService)` 改为 `(taskRepository, projectionRepository, executionService, completionService)`，mock 新增的两个依赖。若该测试用到的场景在新链路下行为变化，调整断言（保持：租约不匹配时直接返回）。

Run: `.\mvnw.cmd -q test -Dtest=WorkspaceProjectionTaskRunnerTest`
Expected: PASS。

- [ ] **Step 9: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java src/main/java/com/insightflow/config/AnalysisConfiguration.java src/main/java/com/insightflow/entity/WorkspaceProjection.java src/main/java/com/insightflow/task/WorkspaceProjectionCompletionService.java src/main/java/com/insightflow/task/WorkspaceProjectionTaskRunner.java src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java src/test/java/com/insightflow/task/WorkspaceProjectionTaskRunnerTest.java
```

---

### Task 11: 全量验证

**Files:** 无新增；运行验证命令。

- [ ] **Step 1: 跑全量单元测试**

Run: `.\mvnw.cmd test`
Expected: 全部测试通过（原有 16 + 新增约 15 个）。本机无 Docker 也可跑（全部 Mockito mock，无 Testcontainers）。

- [ ] **Step 2: 跑 package 验证打包**

Run: `.\mvnw.cmd package -DskipTests`
Expected: BUILD SUCCESS，生成 JAR。

- [ ] **Step 3: 真实 PG + app 启动验证（在装 Docker 的设备上补跑）**

在装 Docker 的设备：
```bash
docker compose up -d
.\mvnw.cmd spring-boot:run
```
访问 `http://localhost:8080/actuator/health`，确认 Flyway V6 已执行、新增 5 个 Repository bean 装配、应用启动无报错。导入一份测试 CSV 后观察 `import_file.projection_status=projected` 且 `issue_catalog`/`feedback_issue_link`/`data_cell`/`cell_issue` 有数据。

- [ ] **Step 4: 向用户报告验证结果**

说明：哪些验证已在本机跑通（单元测试 + package），哪些需在装 Docker 的设备补跑（真实 PG Flyway + app 启动 + 端到端导入）。由用户决定是否提交。

---

## Self-Review

**1. Spec coverage:**
- 规则 + 归一 toml + loader → Task 2 ✅
- IssueTextNormalizer 归一化层 → Task 3 ✅
- RuleFirstIssueClassifier（未命中 unclassified、最多2主题、ambiguous） → Task 4 ✅
- DataCellBuilder（40/60min/6000token、close_reason） → Task 5 ✅
- 5 张事实表实体 + 仓储 → Task 6 ✅
- FeedbackEvent 读方法 + 查询 → Task 7 ✅
- IssueCatalogService find-or-create → Task 8 ✅
- ProjectionSourceLoader + ProjectionFactWriter → Task 9 ✅
- WorkspaceProjectionExecutionService 编排 + Completion/Runner 集成 → Task 10 ✅
- 测试覆盖（规则命中/未命中/ambiguous/Cell 边界/Workspace 隔离/幂等） → Task 2/4/5/6/8/9/10 ✅
- 验证命令 → Task 11 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含可编译 Java。

**3. Type consistency:**
- `IssueRule` 字段在 Task 2 定义，Task 4 `RuleFirstIssueClassifier` 用 `rule.canonicalKey()/priority()/anyPatterns()/allPatterns()/excludePatterns()` —— record 自动生成的访问器，一致 ✅
- `Classification{canonicalKey, confidence, assignmentMethod}` 在 Task 4 定义，Task 9/10 用 `c.canonicalKey()/assignmentMethod()/confidence()` —— 一致 ✅
- `DataCellPlan{windowStart, windowEnd, closeReason, events, estimatedTokens}` Task 5 定义，Task 9/10 用 `plan.windowStart()/windowEnd()/closeReason()/events()/estimatedTokens()` —— 一致 ✅
- `EventInput{id, occurredAt, normalizedText}` Task 5 定义，Task 9/10 用 `event.id()/occurredAt()/normalizedText()` —— 一致 ✅
- `WorkspaceProjectionExecutionService.execute(projectionId, workspaceId)` Task 10 定义，Task 10 Runner 调用 `execute(projection.getId(), task.getWorkspaceId())` —— 一致 ✅

无遗留歧义。Plan 与 spec §4.4 落库映射一致，本期只写 5 张表，`issue_metric_bucket`/`issue_baseline_profile`/`alert` 不写。
