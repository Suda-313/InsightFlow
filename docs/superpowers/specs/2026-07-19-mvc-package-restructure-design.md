# MVC 包结构重组设计

## 目标

将 InsightFlow 从按业务模块分层的包结构，重组为更易阅读的“传统 MVC + 独立异步任务模块”结构。重构只移动 Java 包和更新引用，不改变类名、数据库结构、Flyway 迁移、API 路径、JSON 契约、异步任务状态机或业务行为。

## 目标包结构

```text
com.insightflow
  controller/
  service/
    importing/
  task/
  entity/
  repository/
  storage/
  config/
  dto/
    importing/
  common/
    exception/
```

## 类归属

| 目标包 | 类 |
|---|---|
| `controller` | `FileImportController`、`WorkspaceController` |
| `service` | `FileImportService`、`WorkspaceService` |
| `service.importing` | `CsvFormatSupport`、`CsvPreviewReader`、`HashingService`、`ImportMappingValidator`、`PiiSanitizer` |
| `task` | `ImportTaskCommandService`、`ImportTaskCompletionService`、`ImportTaskLeaseService`、`ImportTaskRunner`、`ImportTaskScheduler` |
| `entity` | `AsyncTask`、`FeedbackEvent`、`FeedbackSource`、`ImportFile`、`Workspace` |
| `repository` | 五个 Spring Data JPA Repository |
| `storage` | `MinioRawImportObjectStorage`、`RawImportObjectStorage`、`RawObjectStorageException` |
| `config` | `ImportTaskConfiguration`、`MinioStorageConfiguration` |
| `dto.importing` | `ImportMapping`、`ImportTaskPayload`、`ImportTaskResult` |
| `common.exception` | `ApiExceptionHandler`、`ImportFileNotFoundException`、`ImportValidationException`、`WorkspaceNotFoundException` |

`InsightFlowApplication` 保持在根包，确保 Spring Boot 对所有子包继续进行组件扫描。

## 运行链路

```text
controller → service → repository/entity
                   ↓
                 task → storage/repository
```

- `controller` 只处理 HTTP 输入、状态码与响应投影。
- `service` 编排同步业务用例，例如上传、预览、保存映射和发起导入。
- `task` 负责长流程的任务创建、租约领取、调度、执行和终态收敛。
- `entity` 和 `repository` 分别承载数据模型与持久化访问。
- `storage` 封装 MinIO；`config` 承载 Spring 配置；`dto` 承载非持久化请求、映射和任务载荷。

## 不变项

- 保持模块化单体，不引入微服务、多 Agent、消息队列或新的数据库组件。
- 保持所有 `workspace_id` 隔离、内部自增主键和对外 UUIDv7 的既有规则。
- 不修改 V1--V5 Flyway 文件，不修改 PostgreSQL 表结构。
- 不修改 REST 路径、请求/响应字段、错误码或前端调用方式。
- 不重命名任何 Java 类，不新增自动化测试；执行现有 Maven 测试和本地 CSV 回归。

## 实施顺序

1. 使用 IDE/重构工具批量移动非 Controller 类，并由编译器更新 `package` 与 `import`。
2. 移动 Controller 和异常处理器，确认 Spring 组件扫描和异常处理仍生效。
3. 全局搜索旧包名前缀，更新测试、README 和注释中的包路径描述。
4. 运行 Maven 测试、打包和本地 CSV 端到端回归。
5. 单独提交这一无行为变化的结构重组，便于与功能提交区分。

## 验收标准

- 旧包前缀 `com.insightflow.importing`、`workspace`、`shared.api` 不再出现在 Java 源文件的 package/import 声明中。
- `mvnw.cmd test` 全部通过，`mvnw.cmd package` 成功。
- 应用能启动并执行 V5 后的 CSV 上传、映射、幂等启动与结果轮询。
- Git diff 只包含包路径、目录移动、相关 import、README/注释和本设计/计划文档；无业务逻辑、SQL 或 API 契约变化。
