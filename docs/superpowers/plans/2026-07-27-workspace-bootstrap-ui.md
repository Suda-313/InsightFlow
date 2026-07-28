# 首个工作区创建入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让已初始化但尚无 Workspace 的 Owner 能在前端创建并立即选中第一个工作区。

**Architecture:** Workspace Store 保留可见 Workspace 列表和创建命令，作为当前 `workspaceId` 的唯一写入点。应用侧边栏仅在列表为空时展示创建表单；表单复用既有受 JWT 保护的 `POST /api/v1/workspaces`，成功后由 Store 写入响应 `publicId`，失败时不改变当前工作区。

**Tech Stack:** Vue 3、Pinia、Node.js 内置测试运行器、现有 Spring Boot Workspace API。

## Global Constraints

- 不新增后端 API、数据表、权限规则或自动创建的业务数据。
- 所有业务数据继续以服务端 `workspace_id` 隔离；前端仅使用公开的 `publicId`。
- 创建失败不得写入 `workspaceId`；不保存令牌、密码或其他敏感信息。
- 不提交或推送 Git；关键能力完成后更新 `docs/project-development-log.md`。

---

### Task 1: Workspace Store 的空状态与创建命令

**Files:**
- Modify: `frontend/src/stores/workspace.js`
- Modify: `frontend/package.json`
- Create: `frontend/test/workspace-runtime-state.test.mjs`

**Interfaces:**
- Consumes: `GET /api/v1/workspaces` 返回 `WorkspaceResponse[]`，每项含 `publicId` 与 `name`。
- Consumes: `POST /api/v1/workspaces` 请求体为 `{ name }`，成功返回 `WorkspaceResponse`。
- Produces: Store 的 `workspaces`、`workspaceId`、`loading`、`createWorkspace(name)`；创建成功返回 Workspace，失败抛出后端错误且不改变 `workspaceId`。

- [ ] **Step 1: 写失败测试**

在 `workspace-runtime-state.test.mjs` 创建测试，断言 Store 声明可见列表和 `createWorkspace`，创建请求使用 `POST /api/v1/workspaces` 与 `{ name }`，且成功路径将响应 `publicId` 写入 `workspaceId`。

- [ ] **Step 2: 运行测试确认失败**

运行：`npm.cmd --prefix frontend test -- workspace-runtime-state.test.mjs`

预期：失败，指出当前 Store 未声明 `workspaces` 或 `createWorkspace`。

- [ ] **Step 3: 实现最小 Store 行为**

在 `workspace.js`：

```js
const workspaces = ref([])

async function createWorkspace(name) {
  const response = await fetch('/api/v1/workspaces', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  })
  const workspace = await response.json()
  if (!response.ok) throw new Error(workspace?.error?.message || '创建工作区失败')
  workspaces.value = [workspace, ...workspaces.value]
  workspaceId.value = workspace.publicId
  return workspace
}
```

`init()` 成功读取列表后同步 `workspaces`，列表为空时显式保持 `workspaceId=''`。在 `frontend/package.json` 的 `test` 脚本末尾加入 `test/workspace-runtime-state.test.mjs`，确保全量前端测试包含该回归。

- [ ] **Step 4: 运行测试确认通过**

运行：`npm.cmd --prefix frontend test -- workspace-runtime-state.test.mjs`

预期：通过。

### Task 2: 侧边栏创建表单与错误反馈

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/test/workspace-runtime-state.test.mjs`

**Interfaces:**
- Consumes: `store.workspaces`、`store.workspaceId`、`store.loading` 和 `store.createWorkspace(name)`。
- Produces: 无 Workspace 时的名称输入、创建按钮和失败提示；已有 Workspace 时不展示表单。

- [ ] **Step 1: 扩展失败测试**

在同一测试文件中断言 `App.vue`：

```js
assert.match(appView, /v-if="!store\.workspaceId"/)
assert.match(appView, /@click="createWorkspace"/)
assert.match(appView, /workspaceError/)
assert.match(appView, /store\.createWorkspace\(workspaceName\.value\.trim\(\)\)/)
```

测试名称说明其防止的回归：无工作区时用户只能看到禁用业务按钮、无法创建第一个工作区。

- [ ] **Step 2: 运行测试确认失败**

运行：`npm.cmd --prefix frontend test -- workspace-runtime-state.test.mjs`

预期：失败，指出 `App.vue` 缺少创建表单状态或提交调用。

- [ ] **Step 3: 实现最小侧边栏表单**

在 `App.vue` 的侧边栏底部：

```vue
<form v-if="!store.workspaceId" @submit.prevent="createWorkspace">
  <input v-model="workspaceName" :disabled="workspaceCreating" maxlength="100" required>
  <button :disabled="workspaceCreating || !workspaceName.trim()">
    {{ workspaceCreating ? '创建中…' : '创建工作区' }}
  </button>
  <p v-if="workspaceError">{{ workspaceError }}</p>
</form>
```

并在 `<script setup>` 中新增本地 `ref` 状态和 `createWorkspace()`：提交期间禁用按钮；成功后清空名称和错误；失败时保留名称并显示错误；不直接写入 `store.workspaceId`。

- [ ] **Step 4: 运行前端全量测试与构建**

运行：

```powershell
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
git diff --check
```

预期：测试和构建退出码为 0；构建如仍出现既有 Vite CSS `@import` 顺序警告，仅记录，不作为本任务改动。

- [ ] **Step 5: 更新开发记录**

在 `docs/project-development-log.md` 增加已验证的记录，说明空 Workspace 会使知识库上传禁用、原因是 Owner 初始化不自动创建业务 Workspace、前端通过显式受保护命令创建并立即选中，以及实际运行的验证命令。
