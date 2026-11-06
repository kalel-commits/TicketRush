package com.ticketrush.assistant.admin;

import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import com.ticketrush.assistant.kb.KnowledgeIngestionService.IngestionReport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (SecurityConfig): re-ingest the knowledge base after editing knowledge/*.md (spec §24.8). */
@RestController
class ReindexController {

    private final KnowledgeIngestionService ingestion;

    ReindexController(KnowledgeIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/api/assistant/admin/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IngestionReport reindex() {
        return ingestion.ingest();
    }
}
