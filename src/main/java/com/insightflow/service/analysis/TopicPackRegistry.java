package com.insightflow.service.analysis;

import com.insightflow.entity.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 启动期扫描并注册所有可用 Topic Pack，支持按 Workspace 绑定解析实际生效的 Pack。
 *
 * <p>MVP 从 classpath {@code config/analysis/packs/{dir}/pack.toml} 发现 Pack 目录并加载；
 * Workspace {@code topic_pack_id} 为 null 时回退 {@code defaultPackDirectory} 对应的 Pack。</p>
 *
 * <p><b>L1 规则切换边界：</b>投影流水线通过 {@link #resolveForWorkspace(Workspace)} 取得 Pack 规则，
 * 用于<b>新投影</b>的 {@link RuleFirstIssueClassifier}；历史 link 若仍使用旧 8 类 issue key，
 * 不与 topic_* 做运行期 alias 映射——零命中仍写 {@link TopicPackDefaults#TOPIC_GENERAL_KEY}。</p>
 */
public class TopicPackRegistry {

    private final String defaultPackDirectory;
    private final Map<String, TopicPackLoader> loadersByPackId = new LinkedHashMap<>();
    private String defaultPackId;

    public TopicPackRegistry(String defaultPackDirectory) {
        this.defaultPackDirectory = defaultPackDirectory;
    }

    /**
     * 扫描 packs 目录并加载全部 Pack；任一 Pack 校验失败则阻止应用启动。
     */
    public void load() {
        TopicPackLoader defaultLoader = registerDirectory(defaultPackDirectory);
        defaultPackId = defaultLoader.packId();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] packFiles = resolver.getResources("classpath:config/analysis/packs/*/pack.toml");
            for (Resource resource : packFiles) {
                String directory = extractPackDirectory(resource);
                if (directory != null && !directory.startsWith("_")) {
                    registerDirectory(directory);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to scan topic packs", exception);
        }
    }

    private TopicPackLoader registerDirectory(String directory) {
        TopicPackLoader loader = new TopicPackLoader(directory);
        loader.load();
        loadersByPackId.putIfAbsent(loader.packId(), loader);
        return loader;
    }

    /** 从 pack.toml 资源路径解析 Pack 目录名，如 game-chaoziran。 */
    private String extractPackDirectory(Resource resource) throws IOException {
        String uri = resource.getURI().toString();
        int packsIndex = uri.indexOf("/packs/");
        int packTomlIndex = uri.lastIndexOf("/pack.toml");
        if (packsIndex < 0 || packTomlIndex <= packsIndex) {
            return null;
        }
        return uri.substring(packsIndex + "/packs/".length(), packTomlIndex);
    }

    /** 全局默认 Pack 标识（配置回退值）。 */
    public String defaultPackId() {
        return defaultPackId;
    }

    /** 按 pack_id 获取已加载的 Pack；不存在时抛 IllegalArgumentException。 */
    public TopicPackLoader requireByPackId(String packId) {
        TopicPackLoader loader = loadersByPackId.get(packId);
        if (loader == null) {
            throw new IllegalArgumentException("Unknown topic pack: " + packId);
        }
        return loader;
    }

    /** 解析 Workspace 实际生效的 Pack：优先绑定值，否则全局默认。 */
    public TopicPackLoader resolveForWorkspace(Workspace workspace) {
        if (workspace.getTopicPackId() != null && !workspace.getTopicPackId().isBlank()) {
            return requireByPackId(workspace.getTopicPackId());
        }
        return requireByPackId(defaultPackId);
    }

    /** 列出全部已注册 Pack 的摘要，供前端 Pack 切换下拉使用。 */
    public List<TopicPackSummary> listSummaries() {
        return loadersByPackId.values().stream()
                .map(loader -> new TopicPackSummary(loader.packId(), loader.packVersion(), loader.displayName()))
                .toList();
    }

    /** 返回不可变 Pack 映射，供测试或诊断使用。 */
    public Map<String, TopicPackLoader> loadersByPackId() {
        return Collections.unmodifiableMap(loadersByPackId);
    }

    /** 对外 Pack 列表项；不含规则正文，仅身份与展示名。 */
    public record TopicPackSummary(String packId, String packVersion, String displayName) {
    }
}
