package com.synapse.backend.controllers;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.services.ConversationClient;
import com.synapse.backend.services.CsvHealthAndHealService;
import com.synapse.backend.services.CsvStorageService;
import com.synapse.backend.services.DatasetMetadataService;
import com.synapse.backend.services.RedisCacheSupport;
import com.synapse.backend.services.RequestIdentity;
import com.synapse.backend.tools.SqlQueryTool;
import com.synapse.backend.models.SqlResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@Profile({"dataset", "legacy"})
@RequestMapping("/api/csv")
public class CsvController {

    private static final Duration DATASET_CACHE_TTL = Duration.ofMinutes(60);

    private final CsvHealthAndHealService healthAndHealService;
    private final CsvStorageService storageService;
    private final DatasetMetadataService metadataService;
    private final ConversationClient conversationClient;
    private final RequestIdentity requestIdentity;
    private final SqlQueryTool sqlQueryTool;
    private final RedisCacheSupport cache;

    public CsvController(CsvHealthAndHealService healthAndHealService,
                         CsvStorageService storageService,
                         DatasetMetadataService metadataService,
                         ConversationClient conversationClient,
                         RequestIdentity requestIdentity,
                         SqlQueryTool sqlQueryTool,
                         RedisCacheSupport cache) {
        this.healthAndHealService = healthAndHealService;
        this.storageService = storageService;
        this.metadataService = metadataService;
        this.conversationClient = conversationClient;
        this.requestIdentity = requestIdentity;
        this.sqlQueryTool = sqlQueryTool;
        this.cache = cache;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "conversationId", required = false) String conversationId,
                                       Authentication authentication,
                                       @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
                                       @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid file type. Only CSV files (.csv) are accepted."
            ));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "The uploaded file is empty."
            ));
        }

        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "conversationId is required. Please create a conversation first."
            ));
        }

        RequestIdentity.Identity identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        if (!conversationClient.existsForUser(conversationId, identity)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] bytes = file.getBytes();
            CsvDocument doc = healthAndHealService.inspectAndHeal(originalFilename, bytes);

            // Create dataset table in conversation schema
            String tableName = "dataset_" + doc.getId().replace("-", "_");
            storageService.createDatasetTable(conversationId, doc.getId(), tableName, doc.getHealthReport().getColumns());

            // Parse and insert data
            List<List<String>> dataRows = parseCsvData(doc.getHealedCsvText());
            storageService.insertData(conversationId, tableName, dataRows, doc.getHeaders());

            // Extract and store metadata
            var metadata = metadataService.extractMetadata(conversationId, doc.getId(), tableName,
                    doc.getHealthReport().getColumns());
            String metadataJson = metadataService.metadataToJson(metadata);

            // Save document metadata to database
            storageService.save(doc, conversationId, identity.subject(), metadataJson);

            // Automatically link the document to the active conversation
            if (!conversationClient.attachDocument(conversationId, doc.getId(), originalFilename, identity)) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("documentId", doc.getId());
            response.put("filename", doc.getFilename());
            response.put("originalSizeBytes", doc.getOriginalSizeBytes());
            response.put("healedSizeBytes", doc.getHealedSizeBytes());
            response.put("headers", doc.getHeaders());
            response.put("previewRows", doc.getPreviewRows());
            response.put("healthReport", doc.getHealthReport());
            response.put("metadata", metadata);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to read file: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Error processing CSV: " + e.getMessage()));
        }
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<?> getDocument(@PathVariable("documentId") String documentId, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication, forwardedSub, forwardedUserId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CsvDocument doc = opt.get();
        return ResponseEntity.ok(Map.of(
                "documentId", doc.getId(),
                "filename", doc.getFilename(),
                "originalSizeBytes", doc.getOriginalSizeBytes(),
                "healedSizeBytes", doc.getHealedSizeBytes(),
                "headers", doc.getHeaders(),
                "previewRows", doc.getPreviewRows(),
                "healthReport", doc.getHealthReport()
        ));
    }

    @GetMapping("/{documentId}/preview")
    public ResponseEntity<?> getPreview(@PathVariable("documentId") String documentId, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication, forwardedSub, forwardedUserId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CsvDocument doc = opt.get();
        return ResponseEntity.ok(Map.of(
                "documentId", doc.getId(),
                "filename", doc.getFilename(),
                "headers", doc.getHeaders(),
                "previewRows", doc.getPreviewRows()
        ));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<?> downloadHealedCsv(@PathVariable("documentId") String documentId, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication, forwardedSub, forwardedUserId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CsvDocument doc = opt.get();
        ByteArrayResource resource = new ByteArrayResource(doc.getHealedContent());
        String outFilename = "healed_" + doc.getFilename();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outFilename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(doc.getHealedContent().length)
                .body(resource);
    }

    private Optional<CsvDocument> ownedDocument(String documentId, Authentication authentication,
            String forwardedSub, String forwardedUserId) {
        RequestIdentity.Identity identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        return storageService.getByIdForUser(documentId, identity.subject());
    }

    private List<List<String>> parseCsvData(String csvText) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new InputStreamReader(
                new ByteArrayInputStream(csvText.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            parser.forEach(record -> {
                List<String> row = new ArrayList<>();
                for (String value : record) {
                    row.add(value);
                }
                rows.add(row);
            });
        }
        return rows.size() > 1 ? rows.subList(1, rows.size()) : new ArrayList<>();
    }

    @GetMapping("/internal/csv/{documentId}")
    public ResponseEntity<?> getDocumentInternal(@PathVariable String documentId,
            @RequestParam String conversationId, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        return storageService.getByIdForConversationAndUser(documentId, conversationId, identity.subject())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/internal/csv/{documentId}/context")
    public ResponseEntity<?> datasetContextInternal(@PathVariable String documentId,
            @RequestParam String conversationId, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        var metadataJson = storageService.getMetadataJsonForConversationAndUser(
                documentId, conversationId, identity.subject());
        if (metadataJson.isEmpty()) return ResponseEntity.notFound().build();
        var metadata = metadataService.metadataFromJson(metadataJson.get());
        return ResponseEntity.ok(metadataService.formatMetadataForSystemPrompt(metadata));
    }

    @PostMapping("/internal/dataset/query")
    public ResponseEntity<?> queryDatasetInternal(@RequestBody Map<String, String> body, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        String conversationId = body.get("conversationId");
        String documentId = body.get("documentId");
        if (!storageService.belongsToConversationForUser(documentId, conversationId, identity.subject())) {
            return ResponseEntity.notFound().build();
        }
        SqlResult result = sqlQueryTool.queryDataset(conversationId, documentId, body.get("query"));
        return result == null ? ResponseEntity.unprocessableEntity().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/internal/dataset/schema")
    public ResponseEntity<?> schemaInternal(@RequestParam String conversationId, @RequestParam String documentId,
            Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        if (!storageService.belongsToConversationForUser(documentId, conversationId, identity.subject())) {
            return ResponseEntity.notFound().build();
        }
        String cacheKey = "dataset:schema:" + identity.subject() + ":" + documentId;
        Optional<String> cached = cache.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }
        String schema = sqlQueryTool.getDatasetSchema(conversationId, documentId);
        cache.put(cacheKey, schema, DATASET_CACHE_TTL);
        return ResponseEntity.ok(schema);
    }

    @GetMapping("/internal/dataset/row-count")
    public ResponseEntity<?> rowCountInternal(@RequestParam String conversationId, @RequestParam String documentId,
            Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        if (!storageService.belongsToConversationForUser(documentId, conversationId, identity.subject())) {
            return ResponseEntity.notFound().build();
        }
        String cacheKey = "dataset:rowcount:" + identity.subject() + ":" + documentId;
        Optional<String> cached = cache.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }
        String rowCount = sqlQueryTool.getRowCount(conversationId, documentId);
        cache.put(cacheKey, rowCount, DATASET_CACHE_TTL);
        return ResponseEntity.ok(rowCount);
    }
}
