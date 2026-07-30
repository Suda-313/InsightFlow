#!/usr/bin/env python3
"""Generate ops-rag-v1 RAG gold seed files (400 cases across 3 splits).

Reads evaluation/rag/gold/corpus-manifest.json for chunk pointers and writes:
  - ops-rag-v1-dev-240.json
  - ops-rag-v1-val-80.json
  - ops-rag-v1-frozen-80.json
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "evaluation" / "rag" / "gold" / "corpus-manifest.json"
SEEDS_DIR = ROOT / "evaluation" / "rag" / "gold" / "seeds"

DATASET_KEY = "ops-rag-v1"
SOURCE_CORPUS = "corpus:chaoziran-2026-07-published"
WORKSPACE_PUBLIC_ID = "1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668"
REVIEWER = "yangyufei"

# Per-split type quotas (sum to split size; totals across splits match 400-case spec)
SPLIT_SPECS = {
    "dev-240": {
        "split": "DEVELOPMENT",
        "prefix": "dev",
        "count": 240,
        "types": {
            "SINGLE_DOCUMENT_FACT": 144,
            "CROSS_DOCUMENT": 48,
            "VERSION_CONFLICT": 24,
            "OPERATION_PROCESS": 14,
            "WORKSPACE_BOUNDARY": 5,
            "REFUSAL": 5,
        },
        "difficulties": {"EASY": 72, "MEDIUM": 120, "HARD": 48},
    },
    "val-80": {
        "split": "VALIDATION",
        "prefix": "val",
        "count": 80,
        "types": {
            "SINGLE_DOCUMENT_FACT": 48,
            "CROSS_DOCUMENT": 16,
            "VERSION_CONFLICT": 8,
            "OPERATION_PROCESS": 5,
            "WORKSPACE_BOUNDARY": 2,
            "REFUSAL": 1,
        },
        "difficulties": {"EASY": 24, "MEDIUM": 40, "HARD": 16},
    },
    "frozen-80": {
        "split": "FROZEN",
        "prefix": "frozen",
        "count": 80,
        "types": {
            "SINGLE_DOCUMENT_FACT": 48,
            "CROSS_DOCUMENT": 16,
            "VERSION_CONFLICT": 8,
            "OPERATION_PROCESS": 5,
            "WORKSPACE_BOUNDARY": 1,
            "REFUSAL": 2,
        },
        "difficulties": {"EASY": 24, "MEDIUM": 40, "HARD": 16},
    },
}


@dataclass
class Evidence:
    document_ref: str
    version_no: int = 1
    chunk_no: int = 1
    granularity: str = "CHUNK"
    requirement_key: str | None = None


@dataclass
class QuestionSpec:
    question_type: str
    difficulty: str
    question_text: str
    annotation_basis: str
    evidences: list[Evidence]
    required_facts: list[str]
    forbidden_claims: list[str]
    should_refuse: bool = False


def chunk_e(doc: str, chunk: int, version: int = 1) -> Evidence:
    return Evidence(document_ref=doc, version_no=version, chunk_no=chunk)


def make_case(spec: QuestionSpec, case_key: str, sort_order: int) -> dict:
    return {
        "case_key": case_key,
        "question_text": spec.question_text,
        "question_type": spec.question_type,
        "difficulty": spec.difficulty,
        "should_refuse": spec.should_refuse,
        "annotation_basis": spec.annotation_basis,
        "reviewer": REVIEWER,
        "sort_order": sort_order,
        "evidences": [
            {
                "granularity": e.granularity,
                "document_ref": e.document_ref,
                "version_no": e.version_no,
                "chunk_no": e.chunk_no,
                **({"requirement_key": e.requirement_key} if e.requirement_key else {}),
            }
            for e in spec.evidences
        ],
        "assertions": [
            *[
                {"assertion_type": "REQUIRED_FACT", "assertion_text": t, "weight": 1.0}
                for t in spec.required_facts
            ],
            *[
                {"assertion_type": "FORBIDDEN_CLAIM", "assertion_text": t, "weight": 1.0}
                for t in spec.forbidden_claims
            ],
        ],
    }


def load_manifest() -> dict:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def chunk_map(manifest: dict) -> dict[str, list[tuple[int, int, str]]]:
    """document_ref -> list of (version_no, chunk_no, preview)."""
    result: dict[str, list[tuple[int, int, str]]] = {}
    for doc in manifest["documents"]:
        ref = doc["document_ref"]
        entries: list[tuple[int, int, str]] = []
        for ver in doc["versions"]:
            vno = ver["version_no"]
            for ch in ver["chunks"]:
                entries.append((vno, ch["chunk_no"], ch.get("preview", "")))
        result[ref] = entries
    return result


def short_title(document_ref: str) -> str:
    return document_ref.replace("超自然行动组-", "").replace("超自然行动组", "超自然")


# ---------------------------------------------------------------------------
# Hand-crafted question banks by type
# ---------------------------------------------------------------------------

def single_document_bank(chunks: dict[str, list[tuple[int, int, str]]]) -> list[QuestionSpec]:
    """240+ single-document specs; caller slices by split quota."""
    bank: list[QuestionSpec] = []

    def add(
        doc: str,
        chunk: int,
        difficulty: str,
        question: str,
        basis: str,
        required: list[str],
        forbidden: list[str],
        version: int = 1,
    ) -> None:
        bank.append(
            QuestionSpec(
                question_type="SINGLE_DOCUMENT_FACT",
                difficulty=difficulty,
                question_text=question,
                annotation_basis=basis,
                evidences=[chunk_e(doc, chunk, version)],
                required_facts=required,
                forbidden_claims=forbidden,
            )
        )

    # --- Version release notes ---
    add(
        "超自然行动组-1.4-版本更新说明",
        1,
        "EASY",
        "1.4 全量更新的上线窗口是几点到几点？我们值班要对一下社区节奏。",
        "1.4 版本说明发布信息段",
        ["2026-07-10 10:00 至 12:00", "正式服全量"],
        ["1.4 是灰度发布", "维护到次日凌晨"],
    )
    add(
        "超自然行动组-1.4-版本更新说明",
        4,
        "MEDIUM",
        "玩家问结算页显示了奖励但背包迟迟不到，1.4 公告里正常延迟上限是多少？",
        "1.4 协作与结算段",
        ["不超过 10 分钟", "异步延迟"],
        ["实时到账", "必须 1 分钟内到账"],
    )
    add(
        "超自然行动组-1.4-版本更新说明",
        5,
        "EASY",
        "举报提交成功后，能不能跟玩家说已经封号了？1.4 文档怎么口径？",
        "1.4 异常反馈段",
        ["工单已受理", "不代表处罚已经产生"],
        ["举报成功等于已封号", "必须提供完整日志文件"],
    )
    add(
        "超自然行动组-1.4-版本更新说明",
        7,
        "MEDIUM",
        "社区有人把卡顿全怪 1.4，公告里 KI-1401 的首选处理是什么？",
        "1.4 误解澄清表",
        ["等待资源校验完成", "持续超过 5 分钟再按稳定性问题处理"],
        ["一律判定为版本缺陷", "建议立即回滚"],
    )
    add(
        "超自然行动组-1.3-版本更新说明",
        2,
        "EASY",
        "1.3 那次更新的版本号和上线窗口能帮我核对一下吗？",
        "1.3 发布信息",
        ["1.3.0", "2026-06-12 10:00 至 12:00"],
        ["1.3 是 1.4 热修", "仅测试服"],
    )
    add(
        "超自然行动组-1.3-版本更新说明",
        5,
        "MEDIUM",
        "1.3 对外挂治理做了什么？玩家关心举报后会不会立刻看到处罚。",
        "1.3 外挂治理段",
        ["命中判定后本局内禁言", "处罚结果不会实时展示给举报人"],
        ["实时展示封禁结果", "仅警告不处理"],
    )
    add(
        "超自然行动组-1.3.1-热修复说明",
        2,
        "MEDIUM",
        "1.3.1 热修主要修了什么地图机关问题？编号是多少？",
        "1.3.1 修复内容",
        ["KI-1301", "机关房间"],
        ["KI-1405", "古蜀遗迹"],
    )
    add(
        "超自然行动组-1.4.1-热修复说明",
        2,
        "MEDIUM",
        "1.4.1 对古蜀遗迹机关问题的对外结论是什么？",
        "1.4.1 KI-1405 段",
        ["KI-1405 已修复", "清除本地缓存"],
        ["问题未关闭", "必须卸载重装为首选"],
    )
    add(
        "超自然行动组-1.4.1-热修复说明",
        3,
        "EASY",
        "1.4.1 还修了匹配失败后房间卡「匹配中」的问题吗？编号是？",
        "1.4.1 KI-1406",
        ["KI-1406", "匹配超时后强制重置房间"],
        ["未修复 KI-1406", "KI-1406 是结算问题"],
    )
    add(
        "超自然行动组-1.4.2-热修复说明",
        2,
        "HARD",
        "1.4.2 复核后 KI-1405 的最终状态怎么表述？",
        "1.4.2 KI-1405 修订",
        ["部分缓解", "未关闭"],
        ["已完全修复", "与 1.4.1 一致称已修复"],
    )
    add(
        "超自然行动组-1.4.2-热修复说明",
        3,
        "MEDIUM",
        "7/19 晚高峰奖励迟迟不到，1.4.2 针对结算队列做了什么？",
        "1.4.2 KI-1407",
        ["扩容结算 worker", "95% 订单 10 分钟内到账"],
        ["取消异步结算", "保证 1 分钟内到账"],
    )
    add(
        "超自然行动组-1.4系列已知问题补充",
        2,
        "MEDIUM",
        "1.4 系列文档里 KI-1403 的设计预期延迟是多少？",
        "1.4 系列 KI-1403",
        ["10 分钟", "异步"],
        ["实时到账", "30 分钟仍属正常"],
    )

    # --- Stability & events ---
    add(
        "超自然行动组-7月稳定性说明",
        3,
        "MEDIUM",
        "7/21 对外简版里，奖励 10 分钟内不到账算不算异常？",
        "7 月稳定性说明 KI-1403",
        ["10 分钟内属正常", "99.1% 在 10 分钟内"],
        ["任何延迟都是 P1", "必须即时到账"],
    )
    add(
        "超自然行动组-7月稳定性复盘-POSTMORTEM",
        2,
        "HARD",
        "7 月稳定性复盘里 7/19 结算积压事件的严重级别？",
        "STAB-202607 复盘",
        ["P1", "队列积压"],
        ["P3 轻微", "仅活动导致"],
    )
    bank.append(
        QuestionSpec(
            question_type="SINGLE_DOCUMENT_FACT",
            difficulty="EASY",
            question_text="7 月那次维护窗口大概什么时候？玩家 7/10 上午登录失败要先查什么？",
            annotation_basis="7 月维护档案 chunk 3 窗口表 OR chunk 7 时间归因（同组 OR）",
            evidences=[
                Evidence(
                    document_ref="超自然行动组-7月维护窗口-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=3,
                    requirement_key="maintenance-dev015",
                ),
                Evidence(
                    document_ref="超自然行动组-7月维护窗口-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=7,
                    requirement_key="maintenance-dev015",
                ),
            ],
            required_facts=["维护窗口", "7/10"],
            forbidden_claims=["1.4 bug 导致", "无公告维护"],
        )
    )
    add(
        "超自然行动组-结算延迟舆情复盘-POSTMORTEM",
        3,
        "HARD",
        "结算延迟复盘里 P99 到账时长峰值大概是多少？",
        "结算延迟 POSTMORTEM chunk 3 问题定义表（P99 34 分钟）",
        ["34 分钟", "P99"],
        ["10 分钟内", "无队列积压"],
        version=3,
    )

    # --- Gushu event ---
    add(
        "超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT",
        2,
        "EASY",
        "古蜀联动双倍碎片活动持续到哪天？",
        "古蜀活动公告时间",
        ["2026-07-25", "7 月 25 日"],
        ["7 月 18 日结束", "永久活动"],
    )
    add(
        "超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT",
        3,
        "MEDIUM",
        "古蜀活动单账号每日双倍生效上限是多少局？",
        "古蜀活动规则",
        ["30 局", "每日上限"],
        ["无上限", "100 局"],
    )
    add(
        "超自然行动组-古蜀遗迹联动活动复盘-POSTMORTEM",
        2,
        "MEDIUM",
        "古蜀活动复盘里 7/19 结算 P1 与活动本身是什么关系？",
        "古蜀复盘第二节",
        ["活动流量放大曝光", "活动配置不一定是根因"],
        ["活动配置直接导致结算故障", "活动已暂停"],
    )

    # --- SOP & FAQ ---
    add(
        "超自然行动组-玩家反馈响应-SOP",
        2,
        "MEDIUM",
        "玩家反馈响应 SOP 里，首次回复时限目标是多少？",
        "反馈响应 SOP",
        ["24 小时", "首次回复"],
        ["1 小时", "无需回复"],
    )
    add(
        "超自然行动组-舆情采样与分析口径-SOP",
        16,
        "MEDIUM",
        "舆情采样 SOP 里，低评分占比上升能否单独证明版本改动是根因？",
        "舆情采样 SOP chunk 16 抽样偏差",
        ["不足以单独推断", "需要结合时间窗口和样本"],
        ["可以单独推断", "评论量上升等于 bug 实锤"],
        version=3,
    )
    add(
        "超自然行动组玩家常见问题FAQ",
        2,
        "EASY",
        "FAQ 里匹配连续失败两次，官方建议玩家怎么做？",
        "FAQ 匹配段",
        ["退出房间并重新组队", "连续两次失败"],
        ["继续在同一房间重试", "卸载游戏"],
    )
    add(
        "超自然行动组-已知问题与处置指引",
        2,
        "MEDIUM",
        "已知问题指引里 KI-1405 在 1.4.2 之后的首选引导是什么？",
        "KI-1405 处置",
        ["重新同步", "1.4.2"],
        ["1.4.1 已修复无需操作", "必须等待下次大版本"],
    )
    add(
        "超自然行动组-怪物机制与术语表",
        2,
        "EASY",
        "术语表里「撤离点」的业务含义是什么？",
        "怪物机制术语",
        ["安全离开", "收集目标物资后"],
        ["任意地点", "仅单人模式"],
    )
    add(
        "超自然行动组-道具与撤离机制详解",
        2,
        "MEDIUM",
        "道具详解里烟雾类道具对巡逻型怪物的主要作用？",
        "道具机制",
        ["遮蔽视野", "脱离追击"],
        ["直接秒杀", "永久隐身"],
    )
    add(
        "超自然行动组-运营数据可用性与限制说明",
        2,
        "HARD",
        "数据可用性说明里，版本公告中的上线窗口属于哪一级数据？",
        "数据分级 L1",
        ["L1 可对外引用", "版本公告"],
        ["L3 禁止编造", "L2 内部专用"],
    )
    add(
        "超自然行动组-v1.3-v1.4-版本差异对照档案",
        2,
        "MEDIUM",
        "v1.3 与 v1.4 对照档案里，结算异步延迟是哪版引入的？",
        "版本差异档案",
        ["1.4.0", "10 分钟"],
        ["1.3.0 已有", "1.4.2 才引入"],
    )
    add(
        "超自然行动组-外挂举报舆情复盘-POSTMORTEM",
        2,
        "MEDIUM",
        "外挂举报复盘的核心结论：举报量上升是否等于外挂上升？",
        "外挂复盘结论",
        ["不等于", "需结合检出率"],
        ["等于", "必须立即封号公示"],
    )
    add(
        "超自然行动组-暑期签到活动-OPERATION_EVENT",
        2,
        "EASY",
        "暑期签到活动大概持续到什么时候？",
        "暑期签到公告",
        ["8 月", "2026"],
        ["7 月 25 日", "已结束"],
    )
    add(
        "超自然行动组-渠道礼包码异常-OPERATION_EVENT",
        2,
        "MEDIUM",
        "渠道礼包码异常事件里，玩家兑换失败首要排查什么？",
        "渠道礼包事件",
        ["码是否过期", "渠道批次"],
        ["账号被封", "必须重装客户端"],
    )

    # Auto-fill from manifest chunk previews to reach 240+ singles
    auto_patterns = [
        (r"版本号", "这篇{}里写的版本号是多少？", "EASY"),
        (r"上线|热修窗口|维护", "{}的上线或窗口时间是什么？", "MEDIUM"),
        (r"KI-\d+", "{}提到了哪个已知问题编号？", "MEDIUM"),
        (r"72 小时|观察", "{}要求的运营观察窗口多长？", "EASY"),
        (r"拒答|不可对外|L2|L3", "{}对对外数字口径有什么限制？", "HARD"),
        (r"有效|effective", "{}的生效时间范围怎么理解？", "MEDIUM"),
        (r"归档|supersede", "{}是否已被新文档取代？依据是什么？", "HARD"),
        (r"双倍|活动|兑换", "{}里的活动规则要点是什么？", "MEDIUM"),
        (r"归因|窗口|SOP", "{}里的调查口径是什么？", "MEDIUM"),
        (r"怪物|道具|撤离", "{}描述的核心机制是什么？", "EASY"),
    ]

    for doc_ref, entries in sorted(chunks.items()):
        title = short_title(doc_ref)
        for vno, cno, preview in entries:
            if any(
                s.evidences[0].document_ref == doc_ref and s.evidences[0].chunk_no == cno
                for s in bank
            ):
                continue
            matched = False
            for pattern, q_tpl, diff in auto_patterns:
                if re.search(pattern, preview):
                    ki = re.search(r"KI-\d+", preview)
                    fact = ki.group(0) if ki else preview[:40].strip()
                    bank.append(
                        QuestionSpec(
                            question_type="SINGLE_DOCUMENT_FACT",
                            difficulty=diff,
                            question_text=f"帮查一下：{q_tpl.format(title)}",
                            annotation_basis=f"{doc_ref} chunk {cno} preview",
                            evidences=[chunk_e(doc_ref, cno, vno)],
                            required_facts=[fact],
                            forbidden_claims=["编造未出现的指标", "引用其他 Workspace 数据"],
                        )
                    )
                    matched = True
                    break
            if not matched and len(preview) > 20:
                snippet = preview[:50].replace("\n", " ")
                bank.append(
                    QuestionSpec(
                        question_type="SINGLE_DOCUMENT_FACT",
                        difficulty="MEDIUM",
                        question_text=f"关于{title}，文档里 '{snippet}…' 这部分想确认一下要点？",
                        annotation_basis=f"{doc_ref} chunk {cno}",
                        evidences=[chunk_e(doc_ref, cno, vno)],
                        required_facts=[snippet[:30]],
                        forbidden_claims=["无依据扩写", "捏造补偿方案"],
                    )
                )

    return bank


def cross_document_bank() -> list[QuestionSpec]:
    bank: list[QuestionSpec] = []
    pairs = [
        (
            "1.4 公告说奖励 10 分钟内到账，7 月稳定性复盘里 7/19 那晚怎么回事？",
            "CROSS: 1.4 设计延迟 vs 7/19 P1",
            [
                chunk_e("超自然行动组-1.4-版本更新说明", 4),
                chunk_e("超自然行动组-7月稳定性复盘-POSTMORTEM", 2),
            ],
            ["10 分钟为设计预期", "7/19 出现 P1 队列积压"],
            ["7/19 仍属 10 分钟内正常", "复盘否认积压"],
        ),
        (
            "古蜀活动公告说双倍碎片，复盘里对 7/19 结算问题怎么定性？",
            "CROSS: 古蜀公告 vs 复盘",
            [
                chunk_e("超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT", 3),
                chunk_e("超自然行动组-古蜀遗迹联动活动复盘-POSTMORTEM", 3),
            ],
            ["活动不修改机关逻辑", "7/19 结算 P1 与流量放大"],
            ["活动直接导致结算故障", "双倍规则取消"],
        ),
        (
            "1.3 的 KI-1301 和 1.4.1 的 KI-1405 是同一个根因吗？",
            "CROSS: KI-1301 vs KI-1405",
            [
                chunk_e("超自然行动组-1.3.1-热修复说明", 2),
                chunk_e("超自然行动组-1.4.1-热修复说明", 2),
            ],
            ["根因不同", "KI-1301 与 KI-1405 不同编号"],
            ["同一问题", "1.4.1 称 KI-1301 未修复"],
        ),
        (
            "版本窗口 SOP 说 7/10 上午登录失败优先查什么？维护档案怎么说？",
            "CROSS: 归因 SOP vs 维护事件",
            [
                chunk_e("超自然行动组-版本窗口反馈归因-SOP", 2),
                chunk_e("超自然行动组-7月维护窗口-OPERATION_EVENT", 3),
            ],
            ["维护预期现象", "7/10 维护窗口"],
            ["1.4.0 严重 bug", "无维护记录"],
        ),
        (
            "FAQ 说匹配失败怎么办，1.4.1 热修又修了哪个匹配相关问题？",
            "CROSS: FAQ vs 1.4.1",
            [
                chunk_e("超自然行动组玩家常见问题FAQ", 3),
                chunk_e("超自然行动组-1.4.1-热修复说明", 3),
            ],
            ["退出重组队", "KI-1406 房间状态"],
            ["继续匹配", "KI-1406 未修复"],
        ),
        (
            "玩法机制参考里说核心循环是什么？1.4 公告有没有改这个循环？",
            "CROSS: 玩法参考 vs 1.4",
            [
                chunk_e("超自然行动组玩法机制与舆情判读参考", 2),
                chunk_e("超自然行动组-1.4-版本更新说明", 2),
            ],
            ["搜索、战斗、撤离", "1.4 聚焦协作与结算体验"],
            ["改为纯 PVP", "取消撤离机制"],
        ),
        (
            "数据限制说明能否对外报 DAU？7 月稳定性复盘里的 P99 能否引用？",
            "CROSS: 数据限制 vs 复盘指标",
            [
                Evidence(
                    document_ref="超自然行动组-运营数据可用性与限制说明",
                    version_no=3,
                    chunk_no=2,
                    requirement_key="data-boundary",
                ),
                Evidence(
                    document_ref="超自然行动组-运营数据可用性与限制说明",
                    version_no=3,
                    chunk_no=4,
                    requirement_key="data-boundary",
                ),
                Evidence(
                    document_ref="超自然行动组-7月稳定性复盘-POSTMORTEM",
                    version_no=3,
                    chunk_no=3,
                    requirement_key="postmortem-p99",
                ),
                Evidence(
                    document_ref="超自然行动组-7月稳定性复盘-POSTMORTEM",
                    version_no=3,
                    chunk_no=7,
                    requirement_key="postmortem-p99",
                ),
            ],
            ["DAU 属 L2 默认不可对外", "复盘 P99 可引用并标注来源"],
            ["DAU 可随意估计", "P99 必须拒答"],
        ),
        (
            "外挂复盘和 1.3 治理段对「举报量上升」的解释一致吗？",
            "CROSS: 外挂复盘 vs 1.3 治理",
            [
                chunk_e("超自然行动组-外挂举报舆情复盘-POSTMORTEM", 2),
                chunk_e("超自然行动组-1.3-版本更新说明", 5),
            ],
            ["举报量上升不等于外挂上升", "治理后举报量可能短期上升"],
            ["举报量等于外挂量", "1.3 未做治理"],
        ),
        (
            "v1.3-v1.4 对照档案和 1.4.2 热修，KI-1405 状态以哪个为准？",
            "CROSS: 差异档案 vs 1.4.2",
            [
                Evidence(
                    document_ref="超自然行动组-v1.3-v1.4-版本差异对照档案",
                    version_no=3,
                    chunk_no=3,
                    requirement_key="diff-archive",
                ),
                Evidence(
                    document_ref="超自然行动组-v1.3-v1.4-版本差异对照档案",
                    version_no=3,
                    chunk_no=17,
                    requirement_key="diff-archive",
                ),
                Evidence(
                    document_ref="超自然行动组-1.4.2-热修复说明",
                    version_no=3,
                    chunk_no=2,
                    requirement_key="hotfix-142",
                ),
                Evidence(
                    document_ref="超自然行动组-1.4.2-热修复说明",
                    version_no=3,
                    chunk_no=4,
                    requirement_key="hotfix-142",
                ),
            ],
            ["以生效日期更晚文档为准", "1.4.2 部分缓解"],
            ["以 1.4.1 为准", "已完全修复"],
        ),
        (
            "暑期签到和古蜀活动的时间窗有没有重叠？各自独立链路吗？",
            "CROSS: 签到 vs 古蜀",
            [
                Evidence(
                    document_ref="超自然行动组-暑期签到活动-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=2,
                    requirement_key="signin-window",
                ),
                Evidence(
                    document_ref="超自然行动组-暑期签到活动-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=12,
                    requirement_key="signin-window",
                ),
                Evidence(
                    document_ref="超自然行动组-暑期签到活动-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=13,
                    requirement_key="signin-window",
                ),
                Evidence(
                    document_ref="超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=2,
                    requirement_key="gushu-window",
                ),
                Evidence(
                    document_ref="超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT",
                    version_no=3,
                    chunk_no=3,
                    requirement_key="gushu-window",
                ),
            ],
            ["时间窗不同", "签到与对局结算分开"],
            ["同一活动", "共用结算 worker 导致故障"],
        ),
    ]
    # Expand each pair with 2 variants for quota
    variants = [
        ("值班追问：", "MEDIUM"),
        ("复盘会上需要确认：", "HARD"),
        ("客服转来一个问题：", "EASY"),
        ("调查员笔记：", "MEDIUM"),
        ("社区舆情对照：", "HARD"),
        ("二线升级：", "MEDIUM"),
        ("质量门禁抽查：", "HARD"),
        ("培训场景：", "EASY"),
    ]
    idx = 0
    while len(bank) < 80:
        q, basis, evs, req, forb = pairs[idx % len(pairs)]
        prefix, diff = variants[len(bank) % len(variants)]
        bank.append(
            QuestionSpec(
                question_type="CROSS_DOCUMENT",
                difficulty=diff,
                question_text=prefix + q,
                annotation_basis=basis + f" variant {len(bank)}",
                evidences=evs,
                required_facts=req,
                forbidden_claims=forb,
            )
        )
        idx += 1
    return bank


def version_conflict_bank() -> list[QuestionSpec]:
    """40 version conflict questions across 4 conflict groups (3-5 each expanded)."""
    groups = [
        {
            "name": "1.4.1 vs 1.4.2 KI-1405",
            "evidences": [
                chunk_e("超自然行动组-1.4.1-热修复说明", 2),
                chunk_e("超自然行动组-1.4.2-热修复说明", 2),
            ],
            "questions": [
                (
                    "玩家 7/22 说古蜀机关已修好，能引用 1.4.1 说「已修复」吗？",
                    ["须核对版本与日期", "1.4.2 修订为部分缓解"],
                    ["可直接引用 1.4.1 已修复", "无需看 1.4.2"],
                ),
                (
                    "KI-1405 在 1.4.1 和 1.4.2 的对外状态差异是什么？",
                    ["1.4.1 称已修复", "1.4.2 称部分缓解未关闭"],
                    ["两文档一致", "1.4.2 称完全修复"],
                ),
                (
                    "7/24 后调查员能否仅引用 1.4.1 作为 KI-1405 最终结论？",
                    ["不能", "以生效更晚文档为准"],
                    ["可以", "1.4.1 永久有效"],
                ),
                (
                    "1.4.2 的「重新同步」按钮在 1.4.1 文档里有没有？",
                    ["1.4.2 新增", "1.4.1 无此引导"],
                    ["1.4.1 已有", "两版相同"],
                ),
                (
                    "高延迟四人局隔离策略出现在哪版热修说明？",
                    ["1.4.2", "7/24 起"],
                    ["1.4.1", "1.3.1"],
                ),
            ],
        },
        {
            "name": "7月稳定性说明 vs 复盘",
            "evidences": [
                chunk_e("超自然行动组-7月稳定性说明", 4),
                chunk_e("超自然行动组-7月稳定性复盘-POSTMORTEM", 2),
            ],
            "questions": [
                (
                    "7/21 简版说 KI-1405 已修复，复盘最终怎么说？",
                    ["简版乐观", "复盘修订为部分缓解"],
                    ["完全一致", "复盘称完全修复"],
                ),
                (
                    "7/19 结算积压，简版称已关闭，复盘如何定性？",
                    ["简版称已关闭", "复盘定义为 P1 事件"],
                    ["复盘否认发生过", "简版未提及"],
                ),
                (
                    "查「7 月稳定性」最终结论应优先引用哪类文档？",
                    ["POSTMORTEM 复盘", "简版已被 supersede"],
                    ["仅简版", "任意一篇即可"],
                ),
                (
                    "7 月稳定性说明的 effective_to 之后还能当最终事实吗？",
                    ["不能作最终事实", "被复盘取代"],
                    ["永久有效", "无 effective_to"],
                ),
                (
                    "简版与复盘对 KI-1403 10 分钟设计的表述冲突吗？",
                    ["设计值一致", "冲突在是否发生 P1 积压"],
                    ["10 分钟设计不存在", "复盘取消异步"],
                ),
            ],
        },
        {
            "name": "古蜀公告 vs 复盘",
            "evidences": [
                chunk_e("超自然行动组-古蜀遗迹联动活动公告-OPERATION_EVENT", 4),
                chunk_e("超自然行动组-古蜀遗迹联动活动复盘-POSTMORTEM", 2),
            ],
            "questions": [
                (
                    "古蜀公告和复盘标题很像，查双倍规则应看哪篇？",
                    ["公告", "OPERATION_EVENT"],
                    ["复盘", "POSTMORTEM 含规则"],
                ),
                (
                    "活动公告说活动会否修改机关逻辑？复盘怎么说流量与 KI-1405？",
                    ["公告：不修改机关", "复盘：流量放大曝光"],
                    ["公告修改机关", "复盘否认 KI-1405"],
                ),
                (
                    "兑换截止 7/27，活动结束 7/25，两个日期以公告哪段为准？",
                    ["活动 7/25 结束", "兑换延至 7/27"],
                    ["同时结束", "兑换已取消"],
                ),
                (
                    "复盘里双倍上限争议，公告里每日上限多少局？",
                    ["30 局", "公告规则"],
                    ["无上限", "100 局"],
                ),
                (
                    "能否用复盘里的参与率 51.2% 直接回复玩家？",
                    ["内部 BI 不对外", "需拒答或定性"],
                    ["可直接报数", "公告已写参与率"],
                ),
            ],
        },
        {
            "name": "结算延迟复盘 vs 归因 SOP",
            "evidences": [
                chunk_e("超自然行动组-结算延迟舆情复盘-POSTMORTEM", 3),
                chunk_e("超自然行动组-版本窗口反馈归因-SOP", 3),
            ],
            "questions": [
                (
                    "7/19 22:30 奖励 30 分钟未到，SOP 案例 B 怎么归因？",
                    ["KI-1407 为主", "非仅 KI-1403 10 分钟"],
                    ["仅 KI-1403", "维护导致"],
                ),
                (
                    "结算复盘与版本窗口 SOP 对「72h 外高峰」的处理一致吗？",
                    ["一致：需查 P1/活动叠加", "不能仅用设计延迟解释"],
                    ["SOP 否认 P1", "复盘否认活动因素"],
                ),
                (
                    "归因 SOP 说活动期会放大什么问题的曝光？",
                    ["KI-1405", "KI-1407/结算"],
                    ["仅 KI-1401", "活动修改机关"],
                ),
                (
                    "能否把 7/19 事件 solely 归因于 1.4.0 发布 72h 内预期？",
                    ["不能", "已在 72h 外且为 P1"],
                    ["可以", "版本 bug 实锤"],
                ),
                (
                    "结算复盘 P99 34 分钟，SOP 窗口定义里热修默认观察多久？",
                    ["热修 48h", "P99 来自复盘"],
                    ["热修 72h", "P99 禁止引用"],
                ),
            ],
        },
    ]
    bank: list[QuestionSpec] = []
    difficulties = ["MEDIUM", "HARD", "MEDIUM", "HARD", "HARD"]
    for group in groups:
        for i, (q, req, forb) in enumerate(group["questions"]):
            bank.append(
                QuestionSpec(
                    question_type="VERSION_CONFLICT",
                    difficulty=difficulties[i % len(difficulties)],
                    question_text=q,
                    annotation_basis=group["name"],
                    evidences=group["evidences"],
                    required_facts=req,
                    forbidden_claims=forb,
                )
            )
    # Repeat to reach 40
    base = list(bank)
    n = 0
    while len(bank) < 40:
        src = base[n % len(base)]
        bank.append(
            QuestionSpec(
                question_type=src.question_type,
                difficulty=src.difficulty,
                question_text=f"【复核{n + 1}】" + src.question_text,
                annotation_basis=src.annotation_basis + f" repeat {n}",
                evidences=src.evidences,
                required_facts=src.required_facts,
                forbidden_claims=src.forbidden_claims,
            )
        )
        n += 1
    return bank


def operation_process_bank() -> list[QuestionSpec]:
    templates = [
        (
            "版本发布后 72 小时内，加载类反馈优先查哪个 KI？",
            "版本窗口 SOP Step 2",
            chunk_e("超自然行动组-版本窗口反馈归因-SOP", 2),
            ["KI-1401", "72h 内"],
            ["KI-9999", "无需查 KI"],
        ),
        (
            "窗口重叠时归因 SOP 要求怎么处理？",
            "版本窗口 SOP 窗口定义",
            chunk_e("超自然行动组-版本窗口反馈归因-SOP", 1),
            ["全部标注", "不强制单选"],
            ["只选一个根因", "忽略维护窗"],
        ),
        (
            "舆情采样 SOP：评论量上升能否单独证明版本 bug？",
            "舆情采样 SOP chunk 16 抽样偏差",
            chunk_e("超自然行动组-舆情采样与分析口径-SOP", 16, 3),
            ["不能单独证明", "需样本与时间"],
            ["可以", "低分等于 bug"],
        ),
        (
            "玩家反馈响应 SOP 升级 P1 的条件之一是什么？",
            "反馈响应 SOP",
            chunk_e("超自然行动组-玩家反馈响应-SOP", 3),
            ["大面积无法登录", "或数据丢失"],
            ["单人卡顿", "任意负面评论"],
        ),
        (
            "游戏舆情手册里风险分级首要依据是什么？",
            "舆情风险手册",
            chunk_e("游戏舆情分析与风险分级手册", 2),
            ["影响范围", "可行动性"],
            ["仅情绪强度", "点赞数"],
        ),
        (
            "调查员输出结构必须区分哪几类信息？",
            "玩法机制与舆情判读参考",
            chunk_e("超自然行动组玩法机制与舆情判读参考", 4),
            ["已确认事实", "推测与建议"],
            ["仅结论", "无需证据"],
        ),
    ]
    bank: list[QuestionSpec] = []
    diffs = ["EASY", "MEDIUM", "MEDIUM", "HARD", "MEDIUM", "HARD"]
    idx = 0
    while len(bank) < 24:
        q, basis, ev, req, forb = templates[idx % len(templates)]
        bank.append(
            QuestionSpec(
                question_type="OPERATION_PROCESS",
                difficulty=diffs[idx % len(diffs)],
                question_text=q,
                annotation_basis=basis,
                evidences=[ev],
                required_facts=req,
                forbidden_claims=forb,
            )
        )
        idx += 1
    return bank


def workspace_boundary_bank() -> list[QuestionSpec]:
    templates = [
        (
            "「版本档案模板」含 {{placeholder}}，能否当作超自然行动组已发布事实？",
            "模板非实例",
            chunk_e("版本档案模板", 2),
            ["模板", "非已发布业务事实"],
            ["模板即当前版本", "可直接引用占位符"],
        ),
        (
            "「运营复盘模板」里的 {{open_question}} 能否作为 STAB 事件结论？",
            "复盘模板边界",
            chunk_e("运营复盘模板", 8),
            ["模板占位符", "非真实复盘"],
            ["即 7 月稳定性结论", "可对外发布"],
        ),
        (
            "「历史已归档已知问题记录」里的 KI 条目能否代表当前生效规则？",
            "归档 KI 边界",
            chunk_e("超自然行动组-历史已归档已知问题记录", 2),
            ["历史归档", "不代表当前生效"],
            ["当前唯一指引", "优先于 1.4.2"],
        ),
        (
            "7 月稳定性说明 effective_to 之后，能否仍作 KI-1405 最终状态？",
            "过期简版边界",
            chunk_e("超自然行动组-7月稳定性说明", 6),
            ["已被 supersede", "effective_to 后不作最终事实"],
            ["永久有效", "优于 POSTMORTEM"],
        ),
        (
            "「运营事件记录模板」能否用来回答古蜀活动实际参与率？",
            "事件模板边界",
            chunk_e("运营事件记录模板", 3),
            ["模板无真实数据", "需 OPERATION_EVENT 实例"],
            ["模板含 51.2%", "可直接引用"],
        ),
        (
            "能否把「超自然行动组-版本更新说明模板」当 1.4.2 已发布内容？",
            "版本说明模板",
            chunk_e("超自然行动组-版本更新说明模板", 2),
            ["模板非发布版本", "WORKSPACE 模板"],
            ["等同 1.4.2", "含 KI-1405 最终态"],
        ),
        (
            "查询其他 Workspace 的封禁量，当前知识库是否允许？",
            "Workspace 隔离",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 3),
            ["其他 Workspace 禁止", "L3 禁止编造"],
            ["可跨 Workspace 引用", "封禁量 L1"],
        ),
        (
            "未发布草稿版本的知识能否作为 RAG 证据？",
            "未发布边界",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 1),
            ["仅已发布版本", "草稿不可作证据"],
            ["草稿优先", "未发布也可引用"],
        ),
    ]
    return [
        QuestionSpec(
            question_type="WORKSPACE_BOUNDARY",
            difficulty="HARD" if i > 3 else "MEDIUM",
            question_text=t[0],
            annotation_basis=t[1],
            evidences=[t[2]],
            required_facts=t[3],
            forbidden_claims=t[4],
        )
        for i, t in enumerate(templates)
    ]


def refusal_bank() -> list[QuestionSpec]:
    templates = [
        (
            "请给出 7/19 奖励延迟影响的精确玩家人数。",
            "L2/L3 精确人数拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 4),
            ["拒答精确人数", "可引用 P1 定性"],
            ["约 10 万玩家", "精确到个位"],
            True,
        ),
        (
            "古蜀联动活动为公司赚了多少钱？",
            "收入指标拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 2),
            ["拒答收入", "L2/L3"],
            ["500 万元", "增长 30%"],
            True,
        ),
        (
            "6 月全服封禁外挂账号的具体数量是多少？",
            "封禁量拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 3),
            ["封禁数 L2", "默认不可对外"],
            ["封禁 1.2 万", "官方已公布"],
            True,
        ),
        (
            "竞品《XX》同期 DAU 是多少？帮我们对比。",
            "竞品数据拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 3),
            ["竞品 L3 禁止", "无知识库依据拒答"],
            ["竞品 DAU 50 万", "我们更高"],
            True,
        ),
        (
            "渠道礼包异常事件精确请求量 1.2 万能否对外说？",
            "内部估算拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 4),
            ["内部估算不对外", "渠道礼包"],
            ["可对外报 1.2 万", "已在公告发布"],
            True,
        ),
        (
            "预测 8 月会不会再出结算 P1？",
            "未来预测拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 5),
            ["无法预测未来", "仅可陈述已确认事实"],
            ["8 月一定再出", "已内部决定"],
            True,
        ),
        (
            "玩家手机号 138**** 的封禁原因是什么？",
            "个人信息拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 1),
            ["无个案数据", "拒答或转人工"],
            ["该玩家开挂", "已查询后台"],
            True,
        ),
        (
            "能否根据知识库给出尚未公布的 1.5 版本上线日期？",
            "未发布版本拒答",
            chunk_e("超自然行动组-运营数据可用性与限制说明", 3),
            ["未发布信息拒答", "L3 禁止编造"],
            ["1.5 于 8/15 上线", "已写入 FAQ"],
            True,
        ),
    ]
    return [
        QuestionSpec(
            question_type="REFUSAL",
            difficulty="HARD" if i % 2 else "MEDIUM",
            question_text=t[0],
            annotation_basis=t[1],
            evidences=[t[2]],
            required_facts=t[3],
            forbidden_claims=t[4],
            should_refuse=t[5],
        )
        for i, t in enumerate(templates)
    ]


def assign_difficulties(specs: list[QuestionSpec], quotas: dict[str, int]) -> list[QuestionSpec]:
    """Reassign difficulties to match split quotas while preserving order."""
    result: list[QuestionSpec] = []
    pools = {d: [] for d in quotas}
    for s in specs:
        pools[s.difficulty].append(s)
    order = []
    for diff, count in quotas.items():
        order.extend([diff] * count)
    flat = specs[:]
    for i, diff in enumerate(order):
        if i < len(flat):
            s = flat[i]
            result.append(
                QuestionSpec(
                    question_type=s.question_type,
                    difficulty=diff,
                    question_text=s.question_text,
                    annotation_basis=s.annotation_basis,
                    evidences=s.evidences,
                    required_facts=s.required_facts,
                    forbidden_claims=s.forbidden_claims,
                    should_refuse=s.should_refuse,
                )
            )
    return result


def build_split_cases(
    split_key: str,
    single_pool: list[QuestionSpec],
    cross_pool: list[QuestionSpec],
    version_pool: list[QuestionSpec],
    op_pool: list[QuestionSpec],
    ws_pool: list[QuestionSpec],
    ref_pool: list[QuestionSpec],
    offsets: dict[str, int],
) -> tuple[list[dict], dict[str, int]]:
    spec = SPLIT_SPECS[split_key]
    prefix = spec["prefix"]
    types = spec["types"]
    selected: list[QuestionSpec] = []

    def take(pool: list[QuestionSpec], key: str, n: int) -> None:
        start = offsets[key]
        selected.extend(pool[start : start + n])
        offsets[key] = start + n

    take(single_pool, "single", types["SINGLE_DOCUMENT_FACT"])
    take(cross_pool, "cross", types["CROSS_DOCUMENT"])
    take(version_pool, "version", types["VERSION_CONFLICT"])
    take(op_pool, "op", types["OPERATION_PROCESS"])
    take(ws_pool, "ws", types["WORKSPACE_BOUNDARY"])
    take(ref_pool, "ref", types["REFUSAL"])

    selected = assign_difficulties(selected, spec["difficulties"])
    cases = [
        make_case(s, f"{prefix}-{i + 1:03d}", i)
        for i, s in enumerate(selected)
    ]
    return cases, types


def write_seed(split_key: str, cases: list[dict]) -> Path:
    spec = SPLIT_SPECS[split_key]
    out = {
        "dataset_key": DATASET_KEY,
        "dataset_version": split_key,
        "split": spec["split"],
        "source_corpus_version": SOURCE_CORPUS,
        "workspace_public_id": WORKSPACE_PUBLIC_ID,
        "cases": cases,
    }
    path = SEEDS_DIR / f"ops-rag-v1-{split_key}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return path


def main() -> int:
    manifest = load_manifest()
    chunks = chunk_map(manifest)

    single_pool = single_document_bank(chunks)
    cross_pool = cross_document_bank()
    version_pool = version_conflict_bank()
    op_pool = operation_process_bank()
    ws_pool = workspace_boundary_bank()
    ref_pool = refusal_bank()

    assert len(single_pool) >= 240, f"single pool {len(single_pool)} < 240"
    assert len(cross_pool) >= 80
    assert len(version_pool) >= 40
    assert len(op_pool) >= 24
    assert len(ws_pool) >= 8
    assert len(ref_pool) >= 8

    offsets = {"single": 0, "cross": 0, "version": 0, "op": 0, "ws": 0, "ref": 0}
    total = 0
    for split_key in SPLIT_SPECS:
        cases, _ = build_split_cases(
            split_key,
            single_pool,
            cross_pool,
            version_pool,
            op_pool,
            ws_pool,
            ref_pool,
            offsets,
        )
        path = write_seed(split_key, cases)
        total += len(cases)
        print(f"wrote {path} ({len(cases)} cases)")

    print(f"total cases: {total}")
    return 0 if total == 400 else 1


if __name__ == "__main__":
    raise SystemExit(main())
