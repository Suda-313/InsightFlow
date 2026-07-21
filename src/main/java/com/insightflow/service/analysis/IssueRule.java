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
