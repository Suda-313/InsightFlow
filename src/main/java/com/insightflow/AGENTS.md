# Java 后端局部协作规范

本文件补充根目录 `AGENTS.md`，适用于 `src/main/java/com/insightflow/` 下的后端代码。

## 分层与数据边界

- 保持模块化单体和现有 MVC 分层：Controller 负责 HTTP 契约，Service 承载用例和业务规则，Repository 只访问持久化数据。
- Controller 不直接编排 Repository，不返回 JPA 实体，不暴露内部自增主键；外部契约使用 `public_id` 与明确 DTO。
- 所有查询与写入都必须按 `workspace_id` 限定；跨工作区读取、通过内部 ID 越过 Workspace 校验或批量操作未过滤 Workspace 均视为缺陷。
- 数据库结构只能通过新的前向 Flyway 迁移演进，不修改已应用的迁移；迁移、实体与 API 契约必须同时说明其业务边界。

## 任务、错误与验证

- 异步任务必须维持现有租约、幂等、状态机和失败可诊断性；不要用内存状态替代持久化任务状态。
- 错误响应使用现有统一异常契约，不向客户端泄露堆栈、密钥、内部 ID 或原始导入数据。
- 修改 Java 业务逻辑时先补充或调整相关 JUnit 测试；至少运行受影响测试，跨层或迁移改动还应运行 `./mvnw.cmd test`。
