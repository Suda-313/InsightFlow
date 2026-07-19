package com.insightflow.importing.application;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * CSV 导入的受限本地执行器配置。
 *
 * <p>V1 有意使用模块化单体内的有限线程池而非新增 MQ/独立 Worker；任务状态仍写入数据库，
 * 以便下一阶段演进领取、租约和恢复机制时不改变 API 契约。</p>
 */
@Configuration
public class ImportTaskConfiguration {

    /**
     * 限制并发导入数，避免多个大 CSV 同时解析挤占 Web 请求线程和数据库连接。
     */
    @Bean("importTaskExecutor")
    public ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("import-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
