package com.insightflow.evaluation.rag;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期性领取 RAG 任务，使进程重启后的 queued 或过期租约任务可以恢复。*/
@Component
public class RagEvaluationScheduler {
    private final RagEvaluationTaskService taskService;
    private final RagEvaluationTaskRunner taskRunner;
    private final String workerId = "rag-evaluation-worker-" + UUID.randomUUID();

    /** 调度器不做模型工作，只把已经领取的任务交给独立 Worker。*/
    public RagEvaluationScheduler(RagEvaluationTaskService taskService, RagEvaluationTaskRunner taskRunner) {
        this.taskService = taskService;
        this.taskRunner = taskRunner;
    }

    /** 固定延迟扫描保证上轮调度结束后再开始下一轮，防止缓慢数据库连接重叠堆积。*/
    @Scheduled(fixedDelayString = "${insightflow.evaluation.rag.dispatch-delay-ms:5000}")
    public void scheduledDispatch() {
        taskService.claimNext(workerId).ifPresent(taskId -> taskRunner.run(taskId, workerId));
    }
}
