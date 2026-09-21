package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDocument {
    private String id;
    private String filename;
    private long originalSizeBytes;
    private long healedSizeBytes;
    private byte[] originalContent;
    private byte[] healedContent;
    private String healedCsvText;
    @Builder.Default
    private List<String> headers = new ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> previewRows = new ArrayList<>();
    private CsvHealthCheckReport healthReport;
    @Builder.Default
    private Instant uploadedAt = Instant.now();
}
