package com.insightflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * InsightFlow 的唯一应用入口。
 *
 * <p>V1 保持模块化单体：HTTP API、异步任务调度与后续受限 Agent Runtime 均在同一进程中部署，
 * 通过包边界而非独立服务拆分来控制复杂度。</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class InsightFlowApplication {

    /**
     * 启动 Spring Boot 容器。
     *
     * <p>基础设施连接信息只从环境变量或本地配置读取，不能在此处写入任何生产密钥。</p>
     */
    public static void main(String[] args) {
        SpringApplication.run(InsightFlowApplication.class, args);
    }
}
