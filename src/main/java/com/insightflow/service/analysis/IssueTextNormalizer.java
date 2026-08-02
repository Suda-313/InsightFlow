package com.insightflow.service.analysis;

import java.util.List;
import java.util.Map;

/**
 * 纯函数归一化层：全角半角、繁简、冗余标点、ASCII 小写、同义词子串替换。
 *
 * <p>归一只用于规则匹配，{@code feedback_issue_link} 只存 issue_id 与 confidence，
 * 既不存原文也不存归一文本，满足"不存未脱敏文本"边界。</p>
 *
 * <p>设计约束：无状态、确定性、无外部 IO；同一输入在任何时点都得到同一归一结果，
 * 便于幂等重投影与离线复算。</p>
 */
public class IssueTextNormalizer {

    /** 归一映射，按列表顺序子串替换；顺序决定多映射冲突时的优先级。 */
    private final List<NormalizeMapping> mappings;

    /** 构造归一器；映射来自 IssueRulesLoader，禁止运行期改写。 */
    public IssueTextNormalizer(List<NormalizeMapping> mappings) {
        this.mappings = mappings;
    }

    /**
     * 把脱敏文本归一为匹配用文本；null 或空串返回空串，不抛异常。
     *
     * <p>边界：本方法只产出临时匹配文本，调用方不得把它写库或回传前端，
     * 避免泄漏未脱敏原文片段。</p>
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 流水线顺序固定，后步依赖前步输出，不可随意调整：
        // 1) 全角→半角：先把全角字母/数字转成 ASCII，后续 lowercase 才能真正小写；
        //    全角标点先转成半角或空格，collapse 才能按统一规则压缩。
        // 2) 繁→简：在压缩/小写前做字符级归一，避免繁体字被当成不同字符导致同义词漏替换。
        // 3) 标点/空白压缩：统一分隔符为单空格，稳定词边界，便于后续子串匹配。
        // 4) ASCII 小写：放在 collapse 之后、同义词替换之前，确保 synonyms 以小写形态比对，
        //    同时避免 collapse 对 lowercase 后字符的意外处理差异。
        // 5) 同义词子串替换：最后执行，基于已稳定的归一文本做口语变体→稳定词替换，
        //    让 issue-rules.toml 的 any_patterns 命中率最大化。
        String result = fullToHalf(text);
        result = toSimplified(result);
        result = collapsePunctuationAndWhitespace(result);
        result = result.toLowerCase(java.util.Locale.ROOT);
        for (NormalizeMapping mapping : mappings) {
            for (String from : mapping.from()) {
                if (from != null && !from.isEmpty()) {
                    result = result.replace(from, mapping.to());
                }
            }
        }
        return result;
    }

    /**
     * 全角字母/数字/标点转半角；ASCII 范围直接返回。
     *
     * <p>业务目的：玩家反馈常以全角字符（如"ＢＵＧ"、"１２３"）输入，而规则模式均为
     * 半角小写，不做这一步会直接漏匹配，降低召回。</p>
     *
     * <p>区间说明：0xFF01–0xFF5E 覆盖全角 ASCII 可见字符（全角 ! 到 ~），
     * 减 0xFEE0 即映射到半角 0x21–0x7E；0x3000 是全角空格，单独映射为普通空格，
     * 交给后续 collapse 统一处理。</p>
     */
    private String fullToHalf(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int code = chars[i];
            if (code >= 0xFF01 && code <= 0xFF5E) {
                chars[i] = (char) (code - 0xFEE0);
            } else if (code == 0x3000) {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    /**
     * 繁体转简体；使用 CJK 繁简表，覆盖游戏词常用字。
     *
     * <p>业务目的：港澳台玩家反馈可能含繁体（如"帳號異常"），规则模式统一用简体，
     * 归一后才能命中"账号异常"等 any_patterns。</p>
     *
     * <p>边界：只做单字映射，不做分词或语义改写，保证归一可逆、可复算；
     * 未命中的字符原样返回，不影响专有名词与 emoji。</p>
     */
    private String toSimplified(String text) {
        Map<Character, Character> table = SimplifiedChineseTable.TABLE;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = table.getOrDefault(chars[i], chars[i]);
        }
        return new String(chars);
    }

    /**
     * 连续标点与空白压缩为单个分隔符，保留可读性便于子串匹配。
     *
     * <p>业务目的：玩家常随手输入多个空格/标点（如"登录 ， 失败"），
     * 压缩为单空格后规则只需匹配一种分隔形态，召回更稳。</p>
     *
     * <p>边界：压缩为空格而非删除，保留词边界便于同义词子串替换与排他模式识别；
     * 首尾空白 trim，避免边界空格干扰匹配。</p>
     */
    private String collapsePunctuationAndWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSep = false;
        for (char ch : text.toCharArray()) {
            boolean isSep = Character.isWhitespace(ch) || isPunctuation(ch);
            if (isSep) {
                if (!lastWasSep) {
                    sb.append(' ');
                }
                lastWasSep = true;
            } else {
                sb.append(ch);
                lastWasSep = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * 判断字符是否为分隔符：仅 ASCII 与 CJK 标点视为分隔符。
     *
     * <p>业务目的：CJK 标点（如中文逗号、顿号）在玩家文本中常充当词边界，
     * 统一当分隔符让 collapse 把它们压成单空格，避免"账号，异常"与"账号 异常"
     * 被当成不同文本导致规则漏匹配。</p>
     *
     * <p>区间说明：0x21–0x2F、0x3A–0x40、0x5B–0x60、0x7B–0x7E 覆盖 ASCII 标点
     * （不含字母数字）；0x3001–0x303F 覆盖 CJK 标点（、。〃〄々等）。</p>
     */
    private boolean isPunctuation(char ch) {
        return (ch >= 0x21 && ch <= 0x2F)
                || (ch >= 0x3A && ch <= 0x40)
                || (ch >= 0x5B && ch <= 0x60)
                || (ch >= 0x7B && ch <= 0x7E)
                || (ch >= 0x3001 && ch <= 0x303F);
    }
}
