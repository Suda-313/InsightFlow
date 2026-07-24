# 本地 Agent 配置设计

## 目标

让开发者在本机启动 Spring Boot 启动类时自动加载模型密钥和 Agent 开关，无需每次在终端或 IDE 中重复设置环境变量，同时不将密钥写入 Git 仓库。

## 方案

- 在主配置中将 `local` 设为默认 Profile；未提供其他 Profile 时，Spring Boot 自动尝试加载 `application-local.yml`。
- 新增 `src/main/resources/application-local.yml`，只保存 `AGENT_ENABLED=true` 和本机的 DashScope 密钥。
- 将该本地配置文件加入 `.gitignore`；仓库只提交不含密钥的 `application-local.yml.example`，说明必填项。
- 保留现有环境变量覆盖能力：部署环境或 IDE Run Configuration 显式设置的属性优先级更高，不受本地文件限制。

## 行为与边界

- 本地文件存在：启动类直接运行，聊天和 Agent 模型能力启用。
- 本地文件不存在：继续按当前安全默认值启动基础分析功能，Agent 不装配，不因缺少密钥失败。
- 不在 README、示例文件、测试输出或开发日志中记录真实密钥。
- 本次不引入 dotenv 依赖，也不修改 Docker、生产部署或模型调用逻辑。

## 验收

1. `application-local.yml` 不出现在 `git status` 中。
2. 使用示例文件创建本地配置并填入密钥后，可直接从 IDE 启动类启用模型。
3. 删除本地配置后，`./mvnw.cmd test` 仍可通过且基础服务保持可启动。
