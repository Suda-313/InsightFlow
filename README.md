# InsightFlow

面向中小产品与运营团队的持续用户反馈预警与证据化决策平台。

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
- 邮箱与中国大陆手机号的基础 PII 脱敏，以及对应单元测试。

当前尚未实现主题归并、异常预警、Agent、Skill、Harness 与前端页面。

## CSV 导入接口（本地验证）

1. `POST /api/v1/workspaces` 创建 Workspace；
2. `POST /api/v1/workspaces/{workspaceId}/imports/files`，以 `multipart/form-data` 的 `file` 字段上传 UTF-8 CSV；
3. `POST /api/v1/workspaces/{workspaceId}/imports/files/{fileId}/mapping` 保存四个必填字段的映射；
4. 带 `Idempotency-Key` 调用 `POST .../{fileId}/start`，获得 `202 Accepted`；
5. 轮询 `GET .../{fileId}/result`，查看成功、重复与失败行计数。

必填映射字段为 `feedback_text`、`occurred_at`、`source`、`external_ref`；`dimensions` 可选映射 `version`、`channel` 等扩展列。
