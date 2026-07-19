# InsightFlow

面向中小产品与运营团队的持续用户反馈预警与证据化决策平台。

## 项目结构

后端采用便于协作理解的 MVC 风格包结构：`controller` 负责 HTTP 接口，`service` 承载业务编排，`task` 承载异步 CSV 导入运行时，`entity` 与 `repository` 负责 PostgreSQL 持久化，`storage` 封装 MinIO 原始文件存取，`dto` 承载接口与任务数据结构，`config` 集中 Spring 配置，`common.exception` 统一错误响应。

这仍是一个模块化单体；目录调整不改变 CSV 导入 API、数据库结构、异步任务行为或工作区隔离规则。

## 本地启动

1. 复制 `.env.example` 为 `.env`，仅在本地修改密码；
2. 启动基础服务：`docker compose up -d`；
3. 使用 Maven Wrapper 运行后端：`./mvnw spring-boot:run`（Windows：`mvnw.cmd spring-boot:run`）；
4. 访问 `http://localhost:8080/actuator/health` 检查服务状态。

## 当前已实现

- Workspace 隔离、Flyway 迁移、PostgreSQL / Redis / MinIO 本地基础设施；
- CSV 上传、受控表头/脱敏样例预览、字段映射校验；
- 原始 CSV 存入 MinIO，分析库仅存脱敏后的 `feedback_event`；
- 异步导入任务、外部引用哈希去重、导入结果计数；
- 导入成功后自动创建独立的投影任务，并通过租约、重试和专用线程池将文件推进为 `projected`；
- 邮箱与中国大陆手机号的基础 PII 脱敏，以及对应单元测试。

当前投影只完成可靠的状态闭环；主题归并、Data Cell、趋势指标、EWMA 预警、Agent、Skill、Harness 与前端页面尚未实现。

## 开发交接

换电脑或交给新的 Codex 继续开发前，请先阅读 [docs/HANDOFF.md](docs/HANDOFF.md)。其中记录了分支、环境、已完成模块、验证结果、不可突破的边界与下一阶段任务。

## CSV 导入接口（本地验证）

1. `POST /api/v1/workspaces` 创建 Workspace；
2. `POST /api/v1/workspaces/{workspaceId}/imports/files`，以 `multipart/form-data` 的 `file` 字段上传 UTF-8 CSV；
3. `POST /api/v1/workspaces/{workspaceId}/imports/files/{fileId}/mapping` 保存四个必填字段的映射；
4. 带 `Idempotency-Key` 调用 `POST .../{fileId}/start`，获得 `202 Accepted`；
5. 轮询 `GET .../{fileId}/result`，查看成功、重复与失败行计数。

必填映射字段为 `feedback_text`、`occurred_at`、`source`、`external_ref`；`dimensions` 可选映射 `version`、`channel` 等扩展列。
