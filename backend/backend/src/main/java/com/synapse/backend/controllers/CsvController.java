// FILE: main/java/com/synapse/backend/controllers/CsvController.java
package com.synapse.backend.controllers;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.services.ConversationService;
import com.synapse.backend.services.CsvHealthAndHealService;
import com.synapse.backend.services.CsvStorageService;
import com.synapse.backend.services.DatasetMetadataService;
import com.synapse.backend.services.UserService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/csv")
public class CsvController {

    private final CsvHealthAndHealService healthAndHealService;
    private final CsvStorageService storageService;
    private final DatasetMetadataService metadataService;
    private final ConversationService conversationService;
    private final UserService userService;

    public CsvController(CsvHealthAndHealService healthAndHealService,
                         CsvStorageService storageService,
                         DatasetMetadataService metadataService,
                         ConversationService conversationService,
                         UserService userService) {
        this.healthAndHealService = healthAndHealService;
        this.storageService = storageService;
        this.metadataService = metadataService;
        this.conversationService = conversationService;
        this.userService = userService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "conversationId", required = false) String conversationId,
                                       Authentication authentication) {
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

        var userOpt = userService.findBySub(extractUserSub(authentication));
        if (userOpt.isEmpty() || conversationService.getByIdAndUser(conversationId, userOpt.get().getId()).isEmpty()) {
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
            storageService.save(doc, conversationId, metadataJson);

            // Automatically link the document to the active conversation
            if (!conversationService.attachDocument(conversationId, userOpt.get().getId(), doc.getId(), originalFilename)) {
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
    public ResponseEntity<?> getDocument(@PathVariable("documentId") String documentId, Authentication authentication) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication);
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
    public ResponseEntity<?> getPreview(@PathVariable("documentId") String documentId, Authentication authentication) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication);
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
    public ResponseEntity<?> downloadHealedCsv(@PathVariable("documentId") String documentId, Authentication authentication) {
        Optional<CsvDocument> opt = ownedDocument(documentId, authentication);
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

    private Optional<CsvDocument> ownedDocument(String documentId, Authentication authentication) {
        return userService.findBySub(extractUserSub(authentication))
                .flatMap(user -> storageService.getByIdForUser(documentId, user.getId()));
    }

    private String extractUserSub(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            return oidcUser.getSubject();
        }
        return authentication != null ? authentication.getName() : null;
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
}
