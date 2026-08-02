package com.insightflow.service.analysis;

import java.util.Map;

/**
 * 游戏工单常用字的繁简映射最小表；只覆盖规则与归一表涉及的字符，避免引入完整繁简库。
 *
 * <p>为什么用最小表：当前 issue-rules.toml 与 issue-normalize.toml 只触及有限的繁体字，
 * 引入完整繁简库会扩大维护面、增加静态初始化成本，且会掩盖"哪些字符真正被规则使用"。
 * 只保留规则覆盖的字符，便于评审与版本绑定。</p>
 *
 * <p>扩展策略：未来若规则扩展到更多繁体词，应整体替换为完整繁简表（或引入成熟库），
 * 而非逐字追加到本表，避免维护碎片化与重复 key 风险；替换时需同步更新 rule_version。</p>
 *
 * <p>边界：本表为只读静态常量，禁止运行期改写，保证归一确定性与幂等重投影一致。</p>
 */
final class SimplifiedChineseTable {

    /** 不可变繁简映射；只读，禁止运行期改写。 */
    static final Map<Character, Character> TABLE = Map.ofEntries(
            // 登录/账号相关：玩家高频反馈"登不上/账号异常"等，繁体变体必须归一到简体模式
            Map.entry('錄', '录'),
            Map.entry('帳', '账'),
            Map.entry('號', '号'),
            Map.entry('戶', '户'),
            Map.entry('連', '连'),
            // 异常/追踪类：覆盖"异常/失联"等工单常用词的繁体写法
            Map.entry('異', '异'),
            Map.entry('蹤', '踪'),
            Map.entry('沒', '没'),
            Map.entry('現', '现'),
            Map.entry('報', '报'),
            Map.entry('錯', '错'),
            Map.entry('斷', '断'),
            Map.entry('線', '线'),
            Map.entry('違', '违'),
            Map.entry('規', '规'),
            Map.entry('題', '题'),
            // 余额/支付类：充值退款工单中"余额/金额/资料"等词的繁体
            Map.entry('餘', '余'),
            Map.entry('額', '额'),
            Map.entry('複', '复'),
            Map.entry('資', '资'),
            Map.entry('訊', '讯'),
            // 网络/连接类：网络/连接故障工单的繁体
            Map.entry('議', '议'),
            Map.entry('網', '网'),
            Map.entry('絡', '络'),
            // 迟到/延迟类：卡顿/延迟工单的繁体
            Map.entry('遲', '迟')
    );

    private SimplifiedChineseTable() {
        // 工具表不应被实例化；所有访问通过 TABLE 静态常量
    }
}
