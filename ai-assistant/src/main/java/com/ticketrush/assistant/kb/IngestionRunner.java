package com.ticketrush.assistant.kb;

import com.ticketrush.assistant.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup ingestion (spec §24.3). Ingestion is idempotent (unchanged chunks are skipped), so it simply runs on every
 * start: a fresh Qdrant gets the bundled knowledge base, an edited one gets exactly the changed sections.
 */
@Component
class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final KnowledgeIngestionService ingestion;
    private final AiProperties properties;

    IngestionRunner(KnowledgeIngestionService ingestion, AiProperties properties) {
        this.ingestion = ingestion;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.ingestOnStartup()) return;
        try {
            ingestion.ingest();
        } catch (RuntimeException e) {   // Qdrant down: start anyway (health shows DOWN, chat answers 503); reindex later
            log.warn("Startup ingestion failed, use POST /api/assistant/admin/reindex once Qdrant is back: {}", e.toString());
        }
    }
}
