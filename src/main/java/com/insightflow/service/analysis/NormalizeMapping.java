package com.insightflow.service.analysis;

import java.util.List;

/**
 * 同义词归一映射；在规则匹配前把口语变体替换为稳定词，提升召回而不破坏确定性。
 *
 * @param from 待归一的口语变体列表（子串替换）
 * @param to   归一后的稳定词，出现在 any_patterns 中即被命中
 */
public record NormalizeMapping(List<String> from, String to) {
}
