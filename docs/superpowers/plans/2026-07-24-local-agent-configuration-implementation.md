# 本地 Agent 配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让开发者通过 Git 忽略的本地配置文件自动启用 Agent，而不将模型密钥提交到仓库。

**Architecture:** 主配置将 `local` 作为默认 Profile；Spring Boot 启动时自动合并本机的 `application-local.yml`。示例文件只包含占位符，真实本地配置由 `.gitignore` 排除，环境变量仍可覆盖配置值。

**Tech Stack:** Spring Boot 3.5、YAML Profile、JUnit 5、Maven。

## Global Constraints

- 真实 DashScope 密钥不得写入受 Git 管理的文件、示例文件、测试或日志。
- 不新增 dotenv 依赖，不修改 Docker、生产部署或模型调用逻辑。
- 默认缺少本地文件时仍可启动基础分析功能，Agent 保持禁用。
- 代码注释使用中文；改动遵循 KISS / YAGNI。
- 不执行 Git 提交或推送，除非用户后续明确要求。

---

### Task 1: 默认加载安全的本地 Agent Profile

**Files:**
- Modify: `D:/yuqiagent/src/main/resources/application.yml`
- Modify: `D:/yuqiagent/.gitignore`
- Create: `D:/yuqiagent/src/main/resources/application-local.yml.example`
- Create: `D:/yuqiagent/src/test/java/com/insightflow/config/LocalAgentProfileConfigurationTest.java`
- Modify: `D:/yuqiagent/docs/project-development-log.md`

**Interfaces:**
- Consumes: `spring.profiles.default`、`spring.ai.openai.api-key`、`insightflow.agent.enabled`。
- Produces: 默认尝试加载 `local` Profile；本机 `application-local.yml` 可覆盖 Agent 开关与密钥，示例文件不含真实密钥。

- [x] **Step 1: 写出失败测试**

```java
@Test
void loadsLocalProfileAsDefaultWithoutRequiringAgentKey() {
    new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                assertThat(context.getEnvironment().getDefaultProfiles()).contains("local");
                assertThat(context.getEnvironment().getProperty("insightflow.agent.enabled"))
                        .isEqualTo("false");
                });
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=LocalAgentProfileConfigurationTest test`

Expected: FAIL，`local` 尚未出现在默认 Profile 中。

- [x] **Step 3: 写入最小配置实现**

在 `application.yml` 的 `spring` 节点新增：

```yaml
  profiles:
    default: local
```

在 `.gitignore` 新增：

```gitignore
src/main/resources/application-local.yml
```

新建不含真实密钥的 `application-local.yml.example`：

```yaml
# 复制为 application-local.yml 后仅在本机填写真实值；该文件已被 Git 忽略。
insightflow:
  agent:
    enabled: true

spring:
  ai:
    openai:
      api-key: your-dashscope-api-key
```

- [x] **Step 4: 运行测试确认通过**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=LocalAgentProfileConfigurationTest test`

Expected: PASS；`local` 是默认 Profile，且缺少本机文件时仍维持安全默认值。

- [x] **Step 5: 记录配置边界并全量验证**

在 `docs/project-development-log.md` 新增记录，说明本地 Profile 的用途、密钥不入库边界和验证结果。

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd test; git diff --check`

Expected: Maven 全量测试通过且 `git diff --check` 无空白错误；`git status --short` 不显示 `src/main/resources/application-local.yml`。

## Self-Review

- Spec coverage：本计划覆盖默认 Profile、本机私有文件、示例文件、Git 忽略、无密钥安全启动、文档和验证。
- Placeholder scan：无 TBD、TODO 或未定义的实现步骤；示例密钥为明确的非真实占位符。
- Type consistency：测试读取的 `spring.profiles.default`、`insightflow.agent.enabled` 与现有 YAML 属性名一致。

## Execution Handoff

本计划仅包含一个可独立验证的配置任务；后续执行将按测试先行完成该任务，不使用子代理以避免对共享工作区产生不必要并发修改。
