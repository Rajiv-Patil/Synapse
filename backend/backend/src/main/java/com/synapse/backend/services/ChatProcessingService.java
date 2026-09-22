package com.synapse.backend.services;

import com.synapse.backend.models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatProcessingService {

    private final CsvStorageService csvStorageService;

    public ChatProcessingService(CsvStorageService csvStorageService) {
        this.csvStorageService = csvStorageService;
    }

    public String processQuery(String prompt, String documentId) {
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

        Optional<CsvDocument> docOpt = csvStorageService.getById(documentId);
        if (docOpt.isEmpty()) {
            return "Document ID " + documentId + " was not found in the active session. Please re-upload the CSV file.";
        }

        CsvDocument doc = docOpt.get();

        // 1. Health check / Auto-heal report query
        if (lower.contains("health") || lower.contains("heal") || lower.contains("corrupt") || lower.contains("issue") || lower.contains("quality")) {
            return generateHealthResponse(doc);
        }

        // 2. Schema / Columns query
        if (lower.contains("schema") || lower.contains("column") || lower.contains("data type") || lower.contains("structure")) {
            return generateSchemaResponse(doc);
        }

        // 3. Null / Missing values query
        if (lower.contains("null") || lower.contains("missing") || lower.contains("empty") || lower.contains("blank")) {
            return generateNullsResponse(doc);
        }

        // 4. Sample / preview rows query
        if (lower.contains("top") || lower.contains("sample") || lower.contains("preview") || lower.contains("row") || lower.contains("first") || lower.contains("show")) {
            return generateRowsResponse(doc, 5);
        }

        // 5. Statistics / Summary query
        if (lower.contains("stat") || lower.contains("summary") || lower.contains("describe") || lower.contains("overview")) {
            return generateStatsResponse(doc);
        }

        // 6. Generic response based on data
        return generateGeneralAnalysis(doc, prompt);
    }

    private String generateHealthResponse(CsvDocument doc) {
        CsvHealthCheckReport r = doc.getHealthReport();
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
        StringBuilder sb = new StringBuilder();
        sb.append("Null & Missing Value Analysis: ").append(doc.getFilename()).append("\n\n");

        int totalRows = Math.max(1, doc.getHealthReport().getHealedRowCount() - 1);
        List<ColumnMetadata> colsWithNulls = doc.getHealthReport().getColumns().stream()
                .filter(c -> c.getNullCount() > 0)
                .collect(Collectors.toList());

        if (colsWithNulls.isEmpty()) {
            sb.append("No null or missing values detected. Every column is complete across all ").append(totalRows).append(" rows.\n");
        } else {
            sb.append("Missing values detected across ").append(colsWithNulls.size()).append("column(s):\n\n");
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
        StringBuilder sb = new StringBuilder();
        sb.append("Previewing First ").append(Math.min(limit, doc.getPreviewRows().size()))
                .append(" Rows: ").append(doc.getFilename()).append("\n\n");

        List<String> headers = doc.getHeaders();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append("\n");

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

        sb.append("Suggested inquiries: \"Show schema\" to view column types, or \"Show health\" to view auto-healed corruptions.");
        return sb.toString();
    }

    private String generateGeneralAnalysis(CsvDocument doc, String prompt) {
        return "Analysis for " + doc.getFilename() + "(" +
                (doc.getHealthReport().getHealedRowCount() - 1) + " rows, " +
                doc.getHeaders().size() + " columns).\n\n" +
                "Dataset Status: " + doc.getHealthReport().getStatus() + " (" +
                (doc.getHealthReport().isAutoHealed() ? "Auto-healed" : "Clean") + ")\n" +
                "Available Columns: " + String.join(", ", doc.getHeaders()) + "\n\n" +
                "Available inquiries:\n" +
                "1. \"Describe the schema\"\n" +
                "2. \"Show CSV health report\"\n" +
                "3. \"Which columns have null values?\"\n" +
                "4. \"Show top 5 rows\"";
    }
}
