// FILE: main/java/com/synapse/backend/services/ChatProcessingService.java
package com.synapse.backend.services;

import com.synapse.backend.models.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatProcessingService {

    private final CsvStorageService csvStorageService;
    private final SqlAgentService sqlAgentService;
    private final DatasetMetadataService metadataService;
    private final JdbcTemplate jdbcTemplate;

    public ChatProcessingService(CsvStorageService csvStorageService,
                                 DatasetMetadataService metadataService, JdbcTemplate jdbcTemplate,
                                 Optional<SqlAgentService> sqlAgentService) {
        this.csvStorageService = csvStorageService;
        this.metadataService = metadataService;
        this.jdbcTemplate = jdbcTemplate;
        this.sqlAgentService = sqlAgentService.orElse(null);
    }

    public String processQuery(String prompt, String conversationId, String documentId) {
        if (prompt == null || prompt.isBlank()) {
            return "Please provide a question or instruction.";
        }

        String lower = prompt.toLowerCase().trim();

        if (documentId == null || documentId.isBlank()) {
            if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
                return "Hello. I am your Synapse Data Assistant.\n\nPlease attach a CSV file to inspect its health, auto-heal corruptions, and analyze columns.";
            }
            return "No CSV file is currently attached to this conversation. Please upload a CSV file to run schema, quality, and data analysis.";
        }

        // Try to get document from database
        Optional<CsvDocument> docOpt = csvStorageService.getByIdForConversation(documentId, conversationId);
        if (docOpt.isEmpty()) {
            return "Document ID " + documentId + " was not found. Please re-upload the CSV file.";
        }

        CsvDocument doc = docOpt.get();

        // 1. ONLY intercept dedicated, explicit file health inspection queries
        if (isExplicitHealthCheckQuery(lower)) {
            return generateHealthResponse(doc);
        }

        // 2. ROUTE ALL ANALYTICAL INQUIRIES (e.g. "top 3", filters, stats, schema) TO SQL AGENT
        if (sqlAgentService != null) {
            try {
                String datasetContext = loadDatasetContext(conversationId, documentId);
                return sqlAgentService.processQuery(prompt, conversationId, documentId, datasetContext);
            } catch (Exception e) {
                return formatAiError(e);
            }
        }

        // 3. OFFLINE FALLBACK ONLY (when no LLM / SQL Agent is active)
        if (lower.contains("schema") || lower.contains("columns") || lower.contains("data type") || lower.contains("structure")) {
            return generateSchemaResponse(doc);
        }
        if (lower.equals("null") || lower.contains("missing values") || lower.contains("null values")) {
            return generateNullsResponse(doc);
        }
        if (lower.contains("sample") || lower.contains("preview") || lower.contains("top") || lower.contains("first")) {
            int limit = extractLimit(lower, 5);
            return generateRowsResponse(doc, limit);
        }
        if (lower.contains("stat") || lower.contains("summary") || lower.contains("describe") || lower.contains("overview")) {
            return generateStatsResponse(doc);
        }

        return "I can help with health checks, schema analysis, null values, and previews. For advanced SQL queries, enable LLM integration.";
    }

    public ChatResponse processStructuredQuery(String prompt, String conversationId, String documentId) {
        if (prompt != null && documentId != null && !documentId.isBlank() && sqlAgentService != null) {
            Optional<CsvDocument> doc = csvStorageService.getByIdForConversation(documentId, conversationId);
            if (doc.isPresent() && !isExplicitHealthCheckQuery(prompt.toLowerCase().trim())) {
                try {
                    return sqlAgentService.processStructuredQuery(prompt, conversationId, documentId,
                            loadDatasetContext(conversationId, documentId));
                } catch (Exception ignored) { }
            }
        }
        return new ChatResponse(processQuery(prompt, conversationId, documentId), null);
    }

    private boolean isExplicitHealthCheckQuery(String lower) {
        return lower.equals("health")
                || lower.equals("show health")
                || lower.contains("health report")
                || lower.contains("auto-heal")
                || lower.contains("auto heal")
                || lower.contains("csv health")
                || lower.contains("healing details")
                || lower.contains("corruptions");
    }

    private int extractLimit(String lower, int defaultLimit) {
        Matcher matcher = Pattern.compile("\\b(?:top|first|limit)\\s+(\\d+)\\b").matcher(lower);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultLimit;
    }

    private String formatAiError(Exception e) {
        String errorType = e.getClass().getSimpleName();
        String message = e.getMessage();

        return "Error executing query: " + errorType + " - " + message;
    }

    private String loadDatasetContext(String conversationId, String documentId) {
        try {
            String metadataJson = jdbcTemplate.queryForObject(
                    "SELECT metadata FROM csv_documents WHERE id = ? AND conversation_id = ?",
                    String.class,
                    UUID.fromString(documentId), UUID.fromString(conversationId)
            );

            if (metadataJson != null && !metadataJson.isBlank()) {
                var metadata = metadataService.metadataFromJson(metadataJson);
                return metadataService.formatMetadataForSystemPrompt(metadata);
            }
        } catch (Exception e) {
            // Metadata might not exist yet, fall back to in-memory data
        }

        CsvDocument doc = csvStorageService.getByIdForConversation(documentId, conversationId).orElse(null);
        if (doc != null) {
            return buildSimpleContext(doc);
        }

        return "";
    }

    private String buildSimpleContext(CsvDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DATASET CONTEXT ===\n");
        sb.append("File: ").append(doc.getFilename()).append("\n");

        if (doc.getHealthReport() != null) {
            sb.append("Rows: ").append(doc.getHealthReport().getHealedRowCount() - 1).append("\n");
            sb.append("Columns: ").append(doc.getHeaders().size()).append("\n\n");
            sb.append("Columns:\n");
            for (ColumnMetadata col : doc.getHealthReport().getColumns()) {
                sb.append("  - ").append(col.getName()).append(" (").append(col.getInferredType()).append(")\n");
            }
        }
        sb.append("=====================\n");
        return sb.toString();
    }

    private String generateHealthResponse(CsvDocument doc) {
        CsvHealthCheckReport r = doc.getHealthReport();
        if (r == null) {
            return "Health report data is currently unavailable for this session.";
        }

        StringBuilder sb = new StringBuilder();

        if (r.getIssues().isEmpty()) {
            sb.append("No structural corruptions were found.\n");
        } else {
            sb.append("Corruptions Identified & Healed:\n\n");
            sb.append("| Index | Issue Type | Location | Description | Healing Action Taken |\n");
            sb.append("|---|---|---|---|---|\n");
            int idx = 1;
            for (CsvIssue issue : r.getIssues()) {
                String row = issue.getRowNumber() != null ? "Row " + issue.getRowNumber() : "Global";
                sb.append("| ").append(idx++).append(" ")
                        .append("| ").append(issue.getIssueType()).append(" ")
                        .append("| ").append(row).append(" ")
                        .append("| ").append(issue.getDescription()).append(" ")
                        .append("| ").append(issue.getActionTaken()).append(" |\n");
            }
        }

        sb.append("CSV Health & Auto-Healing Report: ").append(doc.getFilename()).append("\n\n");
        sb.append("Overall Status: ").append(r.getStatus()).append("\n");
        sb.append("Auto-Healed: ").append(r.isAutoHealed() ? "Yes" : "No corruptions detected").append("\n");
        sb.append("Raw Rows / Healed Rows: ").append(r.getOriginalRowCount()).append(" / ").append(r.getHealedRowCount()).append("\n");
        sb.append("Raw Columns / Healed Columns: ").append(r.getOriginalColumnCount()).append(" / ").append(r.getHealedColumnCount()).append("\n");
        sb.append("Detected Delimiter: ").append(r.getDetectedDelimiter()).append("\n\n");

        return sb.toString();
    }

    private String generateSchemaResponse(CsvDocument doc) {
        if (doc.getHealthReport() == null) return "Schema metadata unavailable.";

        StringBuilder sb = new StringBuilder();
        sb.append("| Column Name | Inferred Type | Null Count | Null % | Sample Values |\n");
        sb.append("|---|---|---|---|---|\n");

        int totalRows = Math.max(1, doc.getHealthReport().getHealedRowCount() - 1);
        for (ColumnMetadata col : doc.getHealthReport().getColumns()) {
            double nullPct = ((double) col.getNullCount() / totalRows) * 100.0;
            String samples = String.join(", ", col.getSampleValues());
            if (samples.length() > 30) samples = samples.substring(0, 27) + "...";
            sb.append("| ").append(col.getName()).append(" ")
                    .append("| ").append(col.getInferredType()).append(" ")
                    .append("| ").append(col.getNullCount()).append(" ")
                    .append("| ").append(String.format("%.1f%%", nullPct)).append(" ")
                    .append("| ").append(samples.isEmpty() ? "—" : samples).append(" |\n");
        }

        return sb.toString();
    }

    private String generateNullsResponse(CsvDocument doc) {
        if (doc.getHealthReport() == null) return "Missing data for null analysis.";

        StringBuilder sb = new StringBuilder();
        sb.append("Null & Missing Value Analysis: ").append(doc.getFilename()).append("\n\n");

        int totalRows = Math.max(1, doc.getHealthReport().getHealedRowCount() - 1);
        List<ColumnMetadata> colsWithNulls = doc.getHealthReport().getColumns().stream()
                .filter(c -> c.getNullCount() > 0)
                .collect(Collectors.toList());

        if (colsWithNulls.isEmpty()) {
            sb.append("No null or missing values detected. Every column is complete across all ").append(totalRows).append(" rows.\n");
        } else {
            sb.append("Missing values detected across ").append(colsWithNulls.size()).append(" column(s):\n\n");
            sb.append("| Column | Type | Missing Values | Completeness |\n");
            sb.append("|---|---|---|---|\n");
            for (ColumnMetadata c : colsWithNulls) {
                double completePct = 100.0 - (((double) c.getNullCount() / totalRows) * 100.0);
                sb.append("| ").append(c.getName()).append(" | ").append(c.getInferredType())
                        .append(" | ").append(c.getNullCount()).append(" / ").append(totalRows)
                        .append(" | ").append(String.format("%.1f%%", completePct)).append(" |\n");
            }
        }
        return sb.toString();
    }

    private String generateRowsResponse(CsvDocument doc, int limit) {
        if (doc.getPreviewRows() == null || doc.getHeaders() == null) return "Preview data is missing.";

        StringBuilder sb = new StringBuilder();
        sb.append("Previewing First ").append(Math.min(limit, doc.getPreviewRows().size()))
                .append(" Rows: ").append(doc.getFilename()).append("\n\n");

        List<String> headers = doc.getHeaders();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|");
        for (int i = 0; i < headers.size(); i++) {
            sb.append("---|");
        }
        sb.append("\n");

        int count = 0;
        for (Map<String, Object> row : doc.getPreviewRows()) {
            if (count >= limit) break;
            sb.append("| ");
            for (String h : headers) {
                Object v = row.getOrDefault(h, "");
                sb.append(v != null ? v.toString() : "").append(" | ");
            }
            sb.append("\n");
            count++;
        }

        return sb.toString();
    }

    private String generateStatsResponse(CsvDocument doc) {
        if (doc.getHealthReport() == null) return "Statistics data is missing.";

        StringBuilder sb = new StringBuilder();
        sb.append("Summary Statistics: ").append(doc.getFilename()).append("\n\n");
        sb.append("Total Rows: ").append(doc.getHealthReport().getHealedRowCount() - 1).append("\n");
        sb.append("Total Columns: ").append(doc.getHeaders().size()).append("\n");
        sb.append("Encoding / Delimiter: UTF-8 / ").append(doc.getHealthReport().getDetectedDelimiter()).append("\n");
        sb.append("Health Status: ").append(doc.getHealthReport().getStatus()).append("\n\n");

        long intCols = doc.getHealthReport().getColumns().stream().filter(c -> "INTEGER".equals(c.getInferredType())).count();
        long decCols = doc.getHealthReport().getColumns().stream().filter(c -> "DECIMAL".equals(c.getInferredType())).count();
        long strCols = doc.getHealthReport().getColumns().stream().filter(c -> "STRING".equals(c.getInferredType())).count();
        long dateCols = doc.getHealthReport().getColumns().stream().filter(c -> "DATE".equals(c.getInferredType())).count();

        sb.append("Column Type Distribution:\n");
        sb.append("Numeric (Integer/Decimal): ").append(intCols + decCols).append("\n");
        sb.append("Text / Categorical: ").append(strCols).append("\n");
        sb.append("Date / Temporal: ").append(dateCols).append("\n\n");

        return sb.toString();
    }
}
