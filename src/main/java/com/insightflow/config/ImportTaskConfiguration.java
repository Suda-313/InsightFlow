package com.insightflow.config;

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

    /**
     * 投影任务有独立的小线程池，防止后续主题/指标计算占满 CSV 解析线程，且仍不引入外部 Worker。
     */
    @Bean("projectionTaskExecutor")
    public ThreadPoolTaskExecutor projectionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("projection-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 报告生成任务使用独立线程池，避免 LLM 调用阻塞导入或投影任务。
     */
    @Bean("analysisReportTaskExecutor")
    public ThreadPoolTaskExecutor analysisReportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("analysis-report-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 调查取证使用单独的小线程池，避免告警突发时与 CSV 导入、LLM 报告争夺执行资源。
     */
    @Bean("investigationTaskExecutor")
    public ThreadPoolTaskExecutor investigationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("investigation-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** RAG 单题模型调用独立限流；最多两条卡住请求，避免耗尽其他业务 Worker。*/
    @Bean("ragEvaluationCallExecutor")
    public ThreadPoolTaskExecutor ragEvaluationCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("rag-evaluation-call-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** RAG 整批任务独立于单题调用池，调度线程只负责领取，不被模型请求阻塞。*/
    @Bean("ragEvaluationTaskExecutor")
    public ThreadPoolTaskExecutor ragEvaluationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("rag-evaluation-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
