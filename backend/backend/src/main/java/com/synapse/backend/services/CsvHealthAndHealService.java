package com.synapse.backend.services;

import com.synapse.backend.models.ColumnMetadata;
import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.CsvHealthCheckReport;
import com.synapse.backend.models.CsvIssue;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CsvHealthAndHealService {

    private static final Pattern INT_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d*\\.\\d+$");
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("^(true|false|yes|no|1|0)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})(.*)$");

    public CsvDocument inspectAndHeal(String filename, byte[] rawBytes) {
        List<CsvIssue> issues = new ArrayList<>();
        boolean autoHealed = false;

        // 1. BOM Check
        byte[] cleanedBytes = rawBytes;
        if (rawBytes.length >= 3 &&
                (rawBytes[0] & 0xFF) == 0xEF &&
                (rawBytes[1] & 0xFF) == 0xBB &&
                (rawBytes[2] & 0xFF) == 0xBF) {
            cleanedBytes = Arrays.copyOfRange(rawBytes, 3, rawBytes.length);
            issues.add(CsvIssue.builder()
                    .issueType("BOM_DETECTED")
                    .description("File contains UTF-8 Byte Order Mark (BOM).")
                    .rowNumber(1)
                    .actionTaken("Stripped UTF-8 BOM sequence.")
                    .build());
            autoHealed = true;
        }

        String rawContent = new String(cleanedBytes, StandardCharsets.UTF_8);

        char delimiter = detectDelimiter(rawContent);
        if (delimiter != ',') {
            issues.add(CsvIssue.builder()
                    .issueType("NON_STANDARD_DELIMITER")
                    .description("Detected non-standard delimiter: '" + delimiter + "'.")
                    .rowNumber(null)
                    .actionTaken("Normalized delimiter to standard comma (',').")
                    .build());
            autoHealed = true;
        }

        String[] rawLines = rawContent.split("\\r?\\n");
        int originalRowCount = rawLines.length;
        List<String> cleanLines = new ArrayList<>();
        int emptyLinesRemoved = 0;

        for (String line : rawLines) {
            if (line.trim().isEmpty()) {
                emptyLinesRemoved++;
            } else {
                cleanLines.add(line);
            }
        }

        if (emptyLinesRemoved > 0) {
            issues.add(CsvIssue.builder()
                    .issueType("EMPTY_LINES")
                    .description("Encountered " + emptyLinesRemoved + " blank line(s).")
                    .rowNumber(null)
                    .actionTaken("Removed empty/blank line(s).")
                    .build());
            autoHealed = true;
        }

        if (cleanLines.isEmpty()) {
            throw new IllegalArgumentException("The uploaded CSV file is empty.");
        }

        // 4. Header Inspection and Healing
        List<String> rawHeaders = parseRowFlexible(cleanLines.getFirst(), delimiter);
        List<String> healedHeaders = new ArrayList<>();
        Set<String> seenHeaders = new HashSet<>();
        int colIndex = 1;

        for (String col : rawHeaders) {
            String trimmed = col.trim().replaceAll("[^\\w\\s\\-_.]", "");
            if (trimmed.isEmpty()) {
                String generatedName = "column_" + colIndex;
                healedHeaders.add(generatedName);
                issues.add(CsvIssue.builder()
                        .issueType("BLANK_HEADER")
                        .description("Header column " + colIndex + " was blank.")
                        .rowNumber(1)
                        .actionTaken("Assigned default name '" + generatedName + "'.")
                        .build());
                autoHealed = true;
            } else if (seenHeaders.contains(trimmed.toLowerCase())) {
                String deduplicated = trimmed + "_" + colIndex;
                healedHeaders.add(deduplicated);
                issues.add(CsvIssue.builder()
                        .issueType("DUPLICATE_HEADER")
                        .description("Header column '" + trimmed + "' appeared multiple times.")
                        .rowNumber(1)
                        .actionTaken("Renamed duplicate to '" + deduplicated + "'.")
                        .build());
                autoHealed = true;
            } else {
                healedHeaders.add(trimmed);
                seenHeaders.add(trimmed.toLowerCase());
            }
            colIndex++;
        }

        int targetColumnCount = healedHeaders.size();

        // 5. Data Rows Parsing & Ragged Row Healing
        List<List<String>> healedDataRows = new ArrayList<>();
        int raggedUnderflowCount = 0;
        int raggedOverflowCount = 0;

        for (int i = 1; i < cleanLines.size(); i++) {
            int lineNum = i + 1;
            List<String> cells = parseRowFlexible(cleanLines.get(i), delimiter);

            // Underflow (too few columns)
            if (cells.size() < targetColumnCount) {
                int missing = targetColumnCount - cells.size();
                List<String> padded = new ArrayList<>(cells);
                for (int m = 0; m < missing; m++) {
                    padded.add("");
                }
                healedDataRows.add(padded);
                raggedUnderflowCount++;
                if (raggedUnderflowCount <= 5) {
                    issues.add(CsvIssue.builder()
                            .issueType("RAGGED_ROW_UNDERFLOW")
                            .description("Row " + lineNum + " had " + cells.size() + " columns (expected " + targetColumnCount + ").")
                            .rowNumber(lineNum)
                            .actionTaken("Padded " + missing + " missing column(s) with empty values.")
                            .build());
                }
                autoHealed = true;
            }
            // Overflow (too many columns)
            else if (cells.size() > targetColumnCount) {
                List<String> truncated = new ArrayList<>(cells.subList(0, targetColumnCount));
                // consolidate excess into last column
                String combinedLast = cells.get(targetColumnCount - 1) + " " + String.join(" ", cells.subList(targetColumnCount, cells.size()));
                truncated.set(targetColumnCount - 1, combinedLast.trim());
                healedDataRows.add(truncated);
                raggedOverflowCount++;
                if (raggedOverflowCount <= 5) {
                    issues.add(CsvIssue.builder()
                            .issueType("RAGGED_ROW_OVERFLOW")
                            .description("Row " + lineNum + " had " + cells.size() + " columns (expected " + targetColumnCount + ").")
                            .rowNumber(lineNum)
                            .actionTaken("Merged excess columns into the last column to preserve rectangular structure.")
                            .build());
                }
                autoHealed = true;
            }
            else {
                healedDataRows.add(cells);
            }
        }

        if (raggedUnderflowCount > 5) {
            issues.add(CsvIssue.builder()
                    .issueType("RAGGED_ROW_UNDERFLOW")
                    .description("Additional " + (raggedUnderflowCount - 5) + " underflow rows were automatically padded.")
                    .rowNumber(null)
                    .actionTaken("Batch padded remaining ragged underflow rows.")
                    .build());
        }
        if (raggedOverflowCount > 5) {
            issues.add(CsvIssue.builder()
                    .issueType("RAGGED_ROW_OVERFLOW")
                    .description("Additional " + (raggedOverflowCount - 5) + " overflow rows were aligned.")
                    .rowNumber(null)
                    .actionTaken("Batch aligned remaining ragged overflow rows.")
                    .build());
        }

        // 6. Generate Clean RFC-4180 Healed CSV String
        StringWriter stringWriter = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(stringWriter, CSVFormat.DEFAULT.builder().setHeader(healedHeaders.toArray(new String[0])).build())) {
            for (List<String> row : healedDataRows) {
                printer.printRecord(row);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to format healed CSV: " + e.getMessage(), e);
        }

        String healedCsvText = stringWriter.toString();
        byte[] healedBytes = healedCsvText.getBytes(StandardCharsets.UTF_8);

        // 7. Column Analysis & Preview Generation
        List<ColumnMetadata> columnMetadataList = analyzeColumns(healedHeaders, healedDataRows);
        List<Map<String, Object>> previewRows = buildPreviewRows(healedHeaders, healedDataRows, 10);

        // 8. Determine Overall Status
        String status = "HEALTHY";
        if (autoHealed) {
            status = "CORRUPTED_HEALED";
        }

        String summary = buildSummary(issues, autoHealed, healedDataRows.size(), healedHeaders.size());

        CsvHealthCheckReport report = CsvHealthCheckReport.builder()
                .status(status)
                .originalRowCount(originalRowCount)
                .healedRowCount(healedDataRows.size() + 1) // +1 for header
                .originalColumnCount(rawHeaders.size())
                .healedColumnCount(healedHeaders.size())
                .detectedDelimiter(String.valueOf(delimiter))
                .autoHealed(autoHealed)
                .summary(summary)
                .issues(issues)
                .columns(columnMetadataList)
                .build();

        return CsvDocument.builder()
                .id(UUID.randomUUID().toString())
                .filename(filename)
                .originalSizeBytes(rawBytes.length)
                .healedSizeBytes(healedBytes.length)
                .originalContent(rawBytes)
                .healedContent(healedBytes)
                .healedCsvText(healedCsvText)
                .headers(healedHeaders)
                .previewRows(previewRows)
                .healthReport(report)
                .uploadedAt(Instant.now())
                .build();
    }

    private char detectDelimiter(String content) {
        char[] candidates = new char[]{',', ';', '\t', '|'};
        String[] sampleLines = content.split("\\r?\\n", 6);
        if (sampleLines.length == 0) return ',';

        char bestDelim = ',';
        int maxConsistency = -1;

        for (char candidate : candidates) {
            int firstCount = countOccurrences(sampleLines[0], candidate);
            if (firstCount == 0) continue;

            boolean consistent = true;
            for (int i = 1; i < Math.min(sampleLines.length, 5); i++) {
                if (sampleLines[i].trim().isEmpty()) continue;
                if (countOccurrences(sampleLines[i], candidate) != firstCount) {
                    consistent = false;
                    break;
                }
            }

            if (consistent && firstCount > maxConsistency) {
                maxConsistency = firstCount;
                bestDelim = candidate;
            }
        }

        return bestDelim;
    }

    private int countOccurrences(String line, char c) {
        int count = 0;
        for (char ch : line.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    /**
     * Resilient row parser that handles quotes, escaped quotes, and commas gracefully.
     */
    private List<String> parseRowFlexible(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }

    private List<ColumnMetadata> analyzeColumns(List<String> headers, List<List<String>> rows) {
        List<ColumnMetadata> list = new ArrayList<>();

        for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
            String colName = headers.get(colIdx);
            int nullCount = 0;
            int intCount = 0;
            int decCount = 0;
            int boolCount = 0;
            int dateCount = 0;
            int total = rows.size();
            List<String> samples = new ArrayList<>();

            for (List<String> row : rows) {
                if (colIdx >= row.size()) {
                    nullCount++;
                    continue;
                }
                String val = row.get(colIdx);
                if (val == null || val.trim().isEmpty() || "null".equalsIgnoreCase(val) || "na".equalsIgnoreCase(val)) {
                    nullCount++;
                    continue;
                }

                if (samples.size() < 3 && !samples.contains(val)) {
                    samples.add(val);
                }

                if (INT_PATTERN.matcher(val).matches()) {
                    intCount++;
                } else if (DECIMAL_PATTERN.matcher(val).matches()) {
                    decCount++;
                } else if (BOOLEAN_PATTERN.matcher(val).matches()) {
                    boolCount++;
                } else if (DATE_PATTERN.matcher(val).matches()) {
                    dateCount++;
                }
            }

            int populated = total - nullCount;
            String inferredType = "STRING";
            if (populated > 0) {
                if (intCount >= populated * 0.8) inferredType = "INTEGER";
                else if ((intCount + decCount) >= populated * 0.8) inferredType = "DECIMAL";
                else if (boolCount >= populated * 0.8) inferredType = "BOOLEAN";
                else if (dateCount >= populated * 0.8) inferredType = "DATE";
            }

            list.add(ColumnMetadata.builder()
                    .name(colName)
                    .originalName(colName)
                    .inferredType(inferredType)
                    .nullCount(nullCount)
                    .totalCount(total)
                    .sampleValues(samples)
                    .build());
        }

        return list;
    }

    private List<Map<String, Object>> buildPreviewRows(List<String> headers, List<List<String>> rows, int limit) {
        List<Map<String, Object>> previews = new ArrayList<>();
        int count = Math.min(rows.size(), limit);

        for (int i = 0; i < count; i++) {
            List<String> row = rows.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            for (int col = 0; col < headers.size(); col++) {
                String val = col < row.size() ? row.get(col) : "";
                map.put(headers.get(col), val);
            }
            previews.add(map);
        }
        return previews;
    }

    private String buildSummary(List<CsvIssue> issues, boolean autoHealed, int rowCount, int colCount) {
        if (!autoHealed || issues.isEmpty()) {
            return "CSV is structurally healthy with " + rowCount + " rows and " + colCount + " columns. No corruptions found.";
        }
        return "Auto-healed " + issues.size() + " issue(s). Resulting dataset is normalized with " + rowCount + " rows and " + colCount + " columns.";
    }
}
