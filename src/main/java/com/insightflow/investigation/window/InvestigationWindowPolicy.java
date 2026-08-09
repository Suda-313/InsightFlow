package com.insightflow.investigation.window;

import com.insightflow.entity.Alert;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 为告警调查提供无需模型即可执行的安全默认窗口选择。
 *
 * <p>当前 classification 仅是 EWMA 运行期属性并以 Alert evidence JSON 旁路保存，
 * 尚未形成可靠的强类型持久化契约。因此第一版统一选择 WEEKLY，而不解析脆弱 JSON
 * 或将瞬态分类作为重试时可能变化的决策输入。</p>
 */
@Component
public class InvestigationWindowPolicy {

    /** 返回稳定周窗口；保留独立组件以便将来在持久化分类稳定后扩展规则。 */
    public InvestigationWindowSelection defaultFor(Alert alert) {
        Objects.requireNonNull(alert, "告警不能为空");
        return InvestigationWindowSelection.WEEKLY;
    }
}
