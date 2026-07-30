package com.insightflow.evaluation.rag.gold.importing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code rag-gold-import} Profile 启动时一次性导入运营 RAG 金标 seed。
 *
 * <p>不提供 HTTP 入口；由 {@code scripts/import-rag-gold-dataset.ps1} 或运维手动激活 Profile 触发。
 * 导入完成后进程正常退出，便于 CI/脚本编排。</p>
 */
@Component
@Profile("rag-gold-import")
public class RagGoldImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagGoldImportRunner.class);

    private static final Path MANIFEST =
            Path.of("evaluation", "rag", "gold", "corpus-manifest.json");

    private final RagGoldSeedImporter importer;
    private final ApplicationContext applicationContext;

    public RagGoldImportRunner(RagGoldSeedImporter importer, ApplicationContext applicationContext) {
        this.importer = importer;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Path> seedPaths = resolveSeedPaths(args);
        List<RagGoldSeedImporter.ImportResult> results = new ArrayList<>();
        for (Path seedPath : seedPaths) {
            results.add(importer.importSeed(seedPath, MANIFEST));
        }
        log.info("RAG gold import complete: {} dataset(s)", results.size());
        for (RagGoldSeedImporter.ImportResult result : results) {
            log.info(
                    "  {}:{} public_id={} checksum={} cases={} status={}",
                    result.datasetKey(),
                    result.datasetVersion(),
                    result.datasetPublicId(),
                    result.checksum(),
                    result.caseCount(),
                    result.status());
        }
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(exitCode);
    }

    private List<Path> resolveSeedPaths(ApplicationArguments args) {
        if (args.containsOption("seed")) {
            return args.getOptionValues("seed").stream().map(Path::of).toList();
        }
        return List.of(
                Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-dev-240.json"),
                Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-val-80.json"),
                Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-frozen-80.json"));
    }
}
