package com.insightflow.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 固定脱敏评测上下文加载器。
 *
 * <p>fixture 不连接业务数据库，保证同一份金标集无论在本地、CI 还是不同时间运行，
 * 输入给模型的事实边界都一致。</p>
 */
@Component
public class EvaluationFixtureLoader {

    /** fixture 标识与 classpath 资源的显式映射，禁止根据外部输入拼接资源路径。 */
    private static final Map<String, String> FIXTURE_RESOURCES = Map.of(
            "game-support:v1", "evaluation/fixtures/game-support-v1.txt");

    /**
     * 按金标用例的 fixtureId 返回固定数据上下文；未知标识必须失败，不能退回真实工作区数据。
     */
    public String load(String fixtureId) {
        String resourcePath = FIXTURE_RESOURCES.get(fixtureId);
        if (resourcePath == null) {
            throw new IllegalArgumentException("未知评测 fixture: " + fixtureId);
        }
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载评测 fixture: " + fixtureId, exception);
        }
    }
}
