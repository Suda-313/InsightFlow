package com.insightflow.knowledge;

import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code knowledge-republish} Profile：失效并删除旧版本后，从 {@code docs/knowledge-sources} 重新发布语料。
 */
@Component
@Profile("knowledge-republish")
@Conditional(AgentApiKeyPresentCondition.class)
public class KnowledgeCorpusRepublishCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusRepublishCliRunner.class);

    private final KnowledgeCorpusRepublishService republishService;
    private final ApplicationContext applicationContext;

    public KnowledgeCorpusRepublishCliRunner(
            KnowledgeCorpusRepublishService republishService, ApplicationContext applicationContext) {
        this.republishService = republishService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        String workspace = requiredOption(args, "workspace");
        UUID workspacePublicId = UUID.fromString(workspace);
        List<KnowledgeCorpusRepublishService.RepublishDocumentResult> results =
                republishService.republishAll(workspacePublicId);

        long succeeded = results.stream().filter(KnowledgeCorpusRepublishService.RepublishDocumentResult::succeeded).count();
        long failed = results.size() - succeeded;
        log.info("KNOWLEDGE_REPUBLISH complete total={} succeeded={} failed={}", results.size(), succeeded, failed);
        for (KnowledgeCorpusRepublishService.RepublishDocumentResult result : results) {
            if (result.succeeded()) {
                log.info(
                        "  OK document_id={} title={} version_no={} source={}",
                        result.documentPublicId(),
                        result.title(),
                        result.publishedVersionNo(),
                        result.sourceName());
            } else {
                log.error(
                        "  FAIL document_id={} title={} error={}",
                        result.documentPublicId(),
                        result.title(),
                        result.errorMessage());
            }
        }

        int exitCode = SpringApplication.exit(applicationContext, () -> failed == 0 ? 0 : 4);
        System.exit(exitCode);
    }

    private static String requiredOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
            throw new IllegalArgumentException("缺少 --" + name);
        }
        return args.getOptionValues(name).get(0);
    }
}
