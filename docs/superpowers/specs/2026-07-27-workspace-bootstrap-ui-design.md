# 首个工作区创建入口设计

## 背景

新数据库完成 Owner 初始化后只创建组织成员关系，不会隐式创建 Workspace。知识库上传依赖当前 `workspaceId`，因此在没有可见 Workspace 时按钮会保持禁用，且前端没有创建入口。

## 方案

在全局侧边栏的当前工作区区域增加空状态。仅当 `GET /api/v1/workspaces` 返回空数组时，展示工作区名称输入框和“创建工作区”按钮。提交复用现有受 JWT 保护的 `POST /api/v1/workspaces`，成功后将响应的 `publicId` 写入现有 Workspace Store；应用内已有页面随该状态变化加载数据。

不在 Owner bootstrap 时自动创建 Workspace，不新增 API、数据表或权限规则。后端仍是唯一的授权与名称校验边界；前端只显示服务端返回的失败信息。

## 交互与错误处理

- 名称为空或正在提交时，创建按钮禁用。
- 成功后隐藏空状态并显示当前 Workspace 的短 ID，知识库上传条件随 `workspaceId` 自动满足。
- 非 2xx 响应显示服务端错误，保留输入值以便修正重试。
- 已有 Workspace 时不显示创建控件，不改变既有首个工作区自动选中逻辑。

## 验收与测试

- 无可见 Workspace 的 Owner 可以输入名称、创建并立即选中返回的 `publicId`。
- 创建失败不会写入 `workspaceId`，并能显示错误。
- 有可见 Workspace 时仍自动选中列表第一项，且不显示创建控件。
- 使用现有 Node 运行时前端测试覆盖上述状态变化，并执行前端构建。
