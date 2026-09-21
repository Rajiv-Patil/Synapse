package com.synapse.backend.controllers;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.services.CsvHealthAndHealService;
import com.synapse.backend.services.CsvStorageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/csv")
public class CsvController {

    private final CsvHealthAndHealService healthAndHealService;
    private final CsvStorageService storageService;

    public CsvController(CsvHealthAndHealService healthAndHealService, CsvStorageService storageService) {
        this.healthAndHealService = healthAndHealService;
        this.storageService = storageService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file) {
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

        try {
            byte[] bytes = file.getBytes();
            CsvDocument doc = healthAndHealService.inspectAndHeal(originalFilename, bytes);
            storageService.save(doc);

            Map<String, Object> response = new HashMap<>();
            response.put("documentId", doc.getId());
            response.put("filename", doc.getFilename());
            response.put("originalSizeBytes", doc.getOriginalSizeBytes());
            response.put("healedSizeBytes", doc.getHealedSizeBytes());
            response.put("headers", doc.getHeaders());
            response.put("previewRows", doc.getPreviewRows());
            response.put("healthReport", doc.getHealthReport());

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
    public ResponseEntity<?> getDocument(@PathVariable String documentId) {
        Optional<CsvDocument> opt = storageService.getById(documentId);
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
    public ResponseEntity<?> getPreview(@PathVariable String documentId) {
        Optional<CsvDocument> opt = storageService.getById(documentId);
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
    public ResponseEntity<?> downloadHealedCsv(@PathVariable String documentId) {
        Optional<CsvDocument> opt = storageService.getById(documentId);
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
}
