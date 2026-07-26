# TapTap 评价区间导出 Implementation Plan

> **For agentic workers:** 在当前会话内按任务顺序执行；不修改主应用代码，也不提交 Git 变更。

**Goal:** 通过已授权的 TapTap 评论分页接口导出《超自然行动组》2026-07-12 至 2026-07-25 的评论 CSV。

**Architecture:** 使用 Scrapling 的普通 HTTP 抓取器，以最新排序和固定低频逐页读取。解析响应中的最小字段，按评论 ID 去重，在遇到早于起始日的评论时停止，并生成 UTF-8 CSV。

**Tech Stack:** Python 3.12、Scrapling 0.4.11、CSV 标准库。

## Global Constraints

- 授权范围仅限 TapTap 应用 ID `714123` 的公开评价接口。
- 仅使用普通请求，不进行登录、验证码或反爬对抗。
- 不保存 Cookie、授权头、用户主页、头像、设备信息或账号标识。
- 结果仅保存为本次导出的 CSV，不提交第三方评论正文至 Git。

---

### Task 1: 验证接口契约与排序

**Files:**
- Create: 系统临时目录中的采集运行文件

- [ ] 使用 `sort=newest` 请求一页，验证响应是 JSON 且包含评论列表、评论 ID、创建时间和正文。
- [ ] 记录列表字段及分页偏移方式；若缺少任一必要字段则停止，不生成不完整数据。

### Task 2: 低频分页采集与时间筛选

**Files:**
- Create: 系统临时目录中的去重结果

- [ ] 按固定偏移逐页请求，每页之间等待 1 秒。
- [ ] 将时间转换为 Asia/Shanghai，保留闭区间 `2026-07-12 00:00:00` 至 `2026-07-25 23:59:59`。
- [ ] 以评论 ID 去重；最新排序中首次遇到早于起始日的记录后结束。

### Task 3: CSV 输出与验证

**Files:**
- Create: `output/taptap-review-2026-07-12-to-2026-07-25.csv`

- [ ] 写入 UTF-8 CSV，列为 `feedback_text,occurred_at,source,external_ref,rating,platform,source_url`。
- [ ] 验证表头、编码、时间范围、非空评论 ID 和去重结果。
- [ ] 输出记录数、日期最小/最大值与 SHA-256，作为交付核验依据。
