package com.ticketrush.assistant.kb;

import com.ticketrush.assistant.config.AiProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Spec §24.3 ingestion: knowledge/*.md -> one chunk per "##" section (long sections split by paragraph to ~1,600
 * chars, about 400 tokens) -> deterministic ids + SHA-256 content hash -> only new or changed chunks are embedded
 * and upserted; points no longer produced are deleted. Re-running is therefore cheap and idempotent.
 */
@Service
public class KnowledgeIngestionService {

    public record IngestionReport(int documents, int chunks, int unchanged, int deleted) {}

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final int MAX_CHUNK_CHARS = 1_600;

    private final VectorStore vectorStore;
    private final QdrantClient qdrant;
    private final String collection;
    private final String defaultLocation;
    private final String kbVersion;

    KnowledgeIngestionService(VectorStore vectorStore, QdrantClient qdrant, AiProperties properties,
                              @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collection,
                              @Value("${ticketrush.ai.knowledge-location:classpath:knowledge/*.md}") String defaultLocation) {
        this.vectorStore = vectorStore;
        this.qdrant = qdrant;
        this.collection = collection;
        this.defaultLocation = defaultLocation;
        this.kbVersion = properties.kbVersion();
    }

    public IngestionReport ingest() {
        return ingest(defaultLocation);
    }

    public synchronized IngestionReport ingest(String locationPattern) {
        Resource[] resources = resources(locationPattern);
        List<Document> chunks = new ArrayList<>();
        for (Resource r : resources) chunks.addAll(chunk(r));

        Map<String, String> existing = existingHashes();
        List<Document> changed = chunks.stream()
                .filter(d -> !d.getMetadata().get("contentHash").equals(existing.get(d.getId()))).toList();
        Set<String> produced = chunks.stream().map(Document::getId).collect(Collectors.toSet());
        List<String> stale = existing.keySet().stream().filter(id -> !produced.contains(id)).toList();

        if (!changed.isEmpty()) vectorStore.add(changed);       // embeds and upserts only what changed
        if (!stale.isEmpty()) vectorStore.delete(stale);
        IngestionReport report = new IngestionReport(resources.length, chunks.size(), chunks.size() - changed.size(), stale.size());
        log.info("Knowledge ingestion {}: {}", locationPattern, report);
        return report;
    }

    /** "# Title" then "## Section" blocks; each section becomes one or more chunks. */
    static List<Document> chunk(Resource resource, String kbVersion) {
        String source = resource.getFilename();
        String text;
        try {
            text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String title = text.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).trim()).orElse(source);
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                current = sections.computeIfAbsent(line.substring(3).trim(), k -> new StringBuilder());
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        List<Document> out = new ArrayList<>();
        sections.forEach((section, body) -> {
            List<String> parts = split(body.toString().trim());
            for (int i = 0; i < parts.size(); i++) {
                String content = title + " — " + section + "\n\n" + parts.get(i);
                Map<String, Object> meta = new HashMap<>();
                meta.put("source", source);
                meta.put("title", title);
                meta.put("section", section);
                meta.put("contentHash", sha256(content));
                meta.put("kbVersion", kbVersion);
                out.add(Document.builder().id(ChunkIdFactory.id(source, section, i).toString()).text(content).metadata(meta).build());
            }
        });
        return out;
    }

    private List<Document> chunk(Resource r) {
        return chunk(r, kbVersion);
    }

    /** Paragraph-preserving split to about 400 tokens per chunk. */
    private static List<String> split(String body) {
        if (body.length() <= MAX_CHUNK_CHARS) return List.of(body);
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String paragraph : body.split("\n\n")) {
            if (part.length() > 0 && part.length() + paragraph.length() > MAX_CHUNK_CHARS) {
                parts.add(part.toString().trim());
                part.setLength(0);
            }
            part.append(paragraph).append("\n\n");
        }
        if (part.length() > 0) parts.add(part.toString().trim());
        return parts;
    }

    /** point id -> contentHash of everything currently in the collection. */
    private Map<String, String> existingHashes() {
        Map<String, String> out = new HashMap<>();
        Common.PointId offset = null;
        try {
            do {
                var request = Points.ScrollPoints.newBuilder().setCollectionName(collection).setLimit(256)
                        .setWithPayload(WithPayloadSelectorFactory.include(List.of("contentHash")));
                if (offset != null) request.setOffset(offset);
                var page = qdrant.scrollAsync(request.build()).get();
                for (var p : page.getResultList()) {
                    JsonWithInt.Value hash = p.getPayloadMap().get("contentHash");
                    out.put(p.getId().getUuid(), hash == null ? "" : hash.getStringValue());
                }
                offset = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
            } while (offset != null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not read the knowledge collection", e);
        }
        return out;
    }

    private static Resource[] resources(String pattern) {
        try {
            return new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
