package com.ticketrush.assistant;

import com.ticketrush.assistant.kb.KnowledgeIngestionService;
import com.ticketrush.assistant.kb.KnowledgeIngestionService.IngestionReport;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §24.3: idempotent ingestion keyed by deterministic ids + content hashes (Review Focus 5). */
class KnowledgeIngestionIT extends AssistantTestBase {

    @Autowired
    KnowledgeIngestionService ingestion;
    @Autowired
    VectorStore vectorStore;
    @Autowired
    QdrantClient qdrant;

    @TempDir
    Path dir;

    long points() throws Exception {
        return qdrant.countAsync("ticketrush_kb").get();
    }

    /** A writable copy of the bundled knowledge base. */
    String copyKnowledge() throws Exception {
        for (var r : new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md")) {
            Files.write(dir.resolve(r.getFilename()), r.getContentAsByteArray());
        }
        return "file:" + dir.toAbsolutePath().toString().replace('\\', '/') + "/*.md";
    }

    @AfterEach
    void restoreBundledKnowledge() {
        ingestion.ingest();   // later tests (and the next class) expect the real knowledge base
    }

    @Test
    void ingestingTwiceChangesNothing() throws Exception {
        IngestionReport first = ingestion.ingest();
        long count = points();
        IngestionReport second = ingestion.ingest();

        assertThat(first.documents()).isEqualTo(5);
        assertThat(second.unchanged()).isEqualTo(second.chunks());
        assertThat(second.deleted()).isZero();
        assertThat(points()).isEqualTo(count).isEqualTo(second.chunks());
    }

    @Test
    void editingOneSectionReembedsOnlyThatSection() throws Exception {
        String location = copyKnowledge();
        IngestionReport base = ingestion.ingest(location);
        Path faq = dir.resolve("faq.md");
        Files.writeString(faq, Files.readString(faq).replace("Browse the events list.", "Browse the events list on the home page."));

        IngestionReport after = ingestion.ingest(location);

        assertThat(after.chunks()).isEqualTo(base.chunks());
        assertThat(after.unchanged()).isEqualTo(after.chunks() - 1);
        assertThat(after.deleted()).isZero();
    }

    @Test
    void deletingASectionRemovesItsPoints() throws Exception {
        String location = copyKnowledge();
        IngestionReport base = ingestion.ingest(location);
        Path account = dir.resolve("account-help.md");
        String text = Files.readString(account);
        Files.writeString(account, text.substring(0, text.indexOf("## How do I contact support?")));

        IngestionReport after = ingestion.ingest(location);

        assertThat(after.deleted()).isEqualTo(1);
        assertThat(after.chunks()).isEqualTo(base.chunks() - 1);
        assertThat(points()).isEqualTo(after.chunks());
    }

    @Test
    void retrievalFindsThePolicyThatAnswersTheQuestion() {
        ingestion.ingest();
        List<Document> cancel = vectorStore.similaritySearch(SearchRequest.builder().query("Can I cancel a confirmed ticket?").topK(4).build());
        List<Document> seats = vectorStore.similaritySearch(SearchRequest.builder().query("How many seats can I buy?").topK(4).build());

        assertThat(cancel).extracting(d -> d.getMetadata().get("source")).contains("cancellation-refund-policy.md");
        assertThat(seats.getFirst().getMetadata().get("source")).isEqualTo("booking-policy.md");
    }

    @Test
    void reindexEndpointIsAdminOnly() throws Exception {
        mvc.perform(post("/api/assistant/admin/reindex").header(AUTHORIZATION, adminToken()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documents").value(5))
                .andExpect(jsonPath("$.chunks").isNumber());
        mvc.perform(post("/api/assistant/admin/reindex").header(AUTHORIZATION, userToken(7))).andExpect(status().isForbidden());
    }
}
