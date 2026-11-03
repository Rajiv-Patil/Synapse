package com.synapse.backend.tools;

import com.synapse.backend.services.CsvStorageService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.synapse.backend.models.SqlResult;

@Component
@org.springframework.context.annotation.Profile({"dataset", "legacy"})
public class SqlQueryTool {

    private final JdbcTemplate jdbcTemplate;
    private final CsvStorageService csvStorageService;

    private static final Pattern DML_PATTERN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Matches any reference to dataset or raw schema-qualified tables in one pass
    private static final Pattern TABLE_REFERENCE_PATTERN = Pattern.compile(
            "(?i)(\"conv_[a-f0-9_]+\"\\.)?\"?dataset(?:_[a-f0-9_]+)?\"?"
    );
    private static final Pattern TABLE_SOURCE_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+((?:\"?[a-z_][a-z0-9_]*\"?\\.)?\"?[a-z_][a-z0-9_]*\"?)"
    );
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile(
            "(?i)(?:\\bWITH\\s+|,\\s*)(?:RECURSIVE\\s+)?\"?([a-z_][a-z0-9_]*)\"?\\s+AS\\s*\\("
    );
    private static final Pattern COMMA_SOURCE_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:(?:AS\\s+)?[a-z_][a-z0-9_]*\\s*)?,\\s*((?:\"?[a-z_][a-z0-9_]*\"?\\.)?\"?[a-z_][a-z0-9_]*\"?)"
    );
    private static final Pattern DERIVED_FROM_PATTERN = Pattern.compile("(?i)\\bFROM\\s*\\(");
    private static final Pattern UNSAFE_FUNCTION_PATTERN = Pattern.compile(
            "(?i)\\b(dblink|query_to_xml|query_to_xml_and_xmlschema|table_to_xml|pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|nextval|setval|set_config|pg_advisory_lock)\\s*\\("
    );

    public SqlQueryTool(JdbcTemplate jdbcTemplate, CsvStorageService csvStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.csvStorageService = csvStorageService;
    }

    private static class TableTarget {
        final String schemaName;
        final String tableName;
        final String fullTableName;

        TableTarget(String schemaName, String tableName, String fullTableName) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.fullTableName = fullTableName;
        }
    }

    private TableTarget resolveTableTarget(String conversationId, String documentId) {
        String tableName = "dataset_" + (documentId != null ? documentId.replace("-", "_") : "default");

        // Locate the table only inside the active conversation's schema.
        try {
            if (conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException("Conversation ID is required");
            }
            String conversationSchema = "conv_" + conversationId.replace("-", "_");
            List<String> schemas = jdbcTemplate.queryForList(
                    "SELECT table_schema FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    String.class,
                    conversationSchema, tableName
            );

            if (!schemas.isEmpty()) {
                String physicalSchema = schemas.get(0);
                return new TableTarget(physicalSchema, tableName, "\"" + physicalSchema + "\".\"" + tableName + "\"");
            }
        } catch (Exception ignored) {
        }

        // Auto-restore if table was dropped
        if (documentId != null && !documentId.isBlank() && conversationId != null && !conversationId.isBlank()) {
            try {
                csvStorageService.restoreDatasetTableIfMissing(conversationId, documentId, tableName);
                String schemaName = "conv_" + conversationId.replace("-", "_");
                return new TableTarget(schemaName, tableName, "\"" + schemaName + "\".\"" + tableName + "\"");
            } catch (Exception e) {
                System.err.println("Could not auto-restore table: " + e.getMessage());
            }
        }

        // Fallback
        String schemaName = "conv_" + (conversationId != null ? conversationId.replace("-", "_") : "default");
        String fullTableName = "\"" + schemaName + "\".\"" + tableName + "\"";
        return new TableTarget(schemaName, tableName, fullTableName);
    }

    @Tool("Execute a read-only SQL SELECT query on the dataset table. Returns the query executed, results, and interpretation.")
    public SqlResult queryDataset(String conversationId, String documentId, String query) {
        if (!isSelectQuery(query)) {
            return null;
        }

        try {
            TableTarget target = resolveTableTarget(conversationId, documentId);
            if (!referencesOnlyDataset(query, target, conversationId)) {
                return null;
            }

            // Strip trailing semicolons, whitespace, and dangling UNION clauses
            String cleanedQuery = query.trim();
            while (cleanedQuery.endsWith(";")) {
                cleanedQuery = cleanedQuery.substring(0, cleanedQuery.length() - 1).trim();
            }
            cleanedQuery = cleanedQuery.replaceAll("(?i)\\s+UNION(\\s+ALL)?\\s*$", "").trim();

            // Single-pass table replacement to prevent double-quote corruption ("" identifiers)
            String sanitizedQuery = TABLE_REFERENCE_PATTERN.matcher(cleanedQuery)
                    .replaceAll(Matcher.quoteReplacement(target.fullTableName));

            // Resolve columns that need quoting without breaking string literals ('...')
            sanitizedQuery = resolveColumnNames(target.schemaName, target.tableName, sanitizedQuery);

            String limitedQuery = "SELECT * FROM (" + sanitizedQuery + ") AS limited_query LIMIT 1000";

            List<Map<String, Object>> results = jdbcTemplate.queryForList(limitedQuery);

            List<String> columns = results.isEmpty() ? new ArrayList<>() : new ArrayList<>(results.get(0).keySet());
            String displayQuery = sanitizedQuery
                    .replaceAll("(?i)\\\"?conv_[a-f0-9_]+\\\"?\\s*\\.\\s*", "")
                    .replaceAll("(?i)\\\"?dataset_[a-f0-9_]+\\\"?", "dataset")
                    .replaceAll("(?i)(?<![a-f0-9])[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![a-f0-9])", "[redacted]");
            return SqlResult.builder().query(displayQuery).columns(columns)
                    .rows(new ArrayList<>(results.subList(0, Math.min(results.size(), 20))))
                    .count(results.size()).build();

        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return null;
        }
    }

    private String resolveColumnNames(String schemaName, String tableName, String query) {
        try {
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_schema = ? AND table_name = ?",
                    String.class,
                    schemaName, tableName
            );

            String result = query;
            for (String col : columns) {
                if (col == null || col.trim().isEmpty()) continue;

                // PostgreSQL natively resolves lowercase alphanumeric snake_case identifiers; only quote special columns
                boolean needsQuotes = !col.matches("^[a-z0-9_]+$");
                if (!needsQuotes && !col.contains(" ")) {
                    continue;
                }

                String quotedCol = "\"" + col.replace("\"", "\"\"") + "\"";

                // Replace unquoted column without touching single-quoted values ('...') or already-quoted columns ("...")
                String regex = "(?i)(?<!['\"])(\\b" + Pattern.quote(col) + "\\b)(?!['\"])";
                result = result.replaceAll(regex, Matcher.quoteReplacement(quotedCol));

                if (col.contains(" ")) {
                    String snake = col.replace(" ", "_");
                    String snakeRegex = "(?i)(?<!['\"])(\\b" + Pattern.quote(snake) + "\\b)(?!['\"])";
                    result = result.replaceAll(snakeRegex, Matcher.quoteReplacement(quotedCol));
                }
            }
            return result;
        } catch (Exception ignored) {
            return query;
        }
    }

    @Tool("Get the schema information for a dataset table including column names and data types")
    public String getDatasetSchema(String conversationId, String documentId) {
        try {
            TableTarget target = resolveTableTarget(conversationId, documentId);

            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type, is_nullable " +
                            "FROM information_schema.columns " +
                            "WHERE table_schema = ? AND table_name = ? " +
                            "ORDER BY ordinal_position",
                    target.schemaName, target.tableName
            );

            StringBuilder response = new StringBuilder();
            response.append("Schema for \"").append(target.schemaName).append("\".\"").append(target.tableName).append("\":\n\n");
            response.append("| Column Name | Data Type | Nullable cd../|\n");
            response.append("|-------------|-----------|----------|\n");

            for (Map<String, Object> col : columns) {
                response.append("| ")
                        .append(col.get("column_name")).append(" | ")
                        .append(col.get("data_type")).append(" | ")
                        .append(col.get("is_nullable")).append(" |\n");
            }

            return response.toString();

        } catch (Exception e) {
            return "Error getting schema: " + e.getMessage();
        }
    }

    @Tool("Get row count for the dataset table")
    public String getRowCount(String conversationId, String documentId) {
        try {
            TableTarget target = resolveTableTarget(conversationId, documentId);

            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + target.fullTableName,
                    Long.class
            );

            return "The dataset contains " + count + " rows.";

        } catch (Exception e) {
            return "Error getting row count: " + e.getMessage();
        }
    }

    private boolean isSelectQuery(String query) {
        String trimmed = query.trim().toUpperCase();
        return (trimmed.startsWith("SELECT") || trimmed.startsWith("WITH"))
                && !DML_PATTERN.matcher(query).find()
                && !UNSAFE_FUNCTION_PATTERN.matcher(query).find();
    }

    private boolean referencesOnlyDataset(String query, TableTarget target, String conversationId) {
        String withoutComments = query.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\r\\n]*", " ");
        String sql = withoutComments.replaceAll("'(?:''|[^'])*'", "''");
        if (DERIVED_FROM_PATTERN.matcher(sql).find()) return false;
        String activeSchema = "conv_" + conversationId.replace("-", "_");

        Set<String> cteNames = new HashSet<>();
        Matcher ctes = CTE_NAME_PATTERN.matcher(sql);
        while (ctes.find()) {
            cteNames.add(ctes.group(1).toLowerCase(Locale.ROOT));
        }

        Matcher sources = TABLE_SOURCE_PATTERN.matcher(sql);
        boolean foundSource = false;
        while (sources.find()) {
            foundSource = true;
            String[] parts = sources.group(1).replace("\"", "").split("\\.");
            String table = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            if (!isAllowedSource(parts, table, cteNames, target, activeSchema)) return false;

            String tail = sql.substring(sources.end());
            Matcher commaSource = COMMA_SOURCE_PATTERN.matcher(tail);
            while (commaSource.find()) {
                String[] commaParts = commaSource.group(1).replace("\"", "").split("\\.");
                String commaTable = commaParts[commaParts.length - 1].toLowerCase(Locale.ROOT);
                if (!isAllowedSource(commaParts, commaTable, cteNames, target, activeSchema)) return false;
                tail = tail.substring(commaSource.end());
                commaSource = COMMA_SOURCE_PATTERN.matcher(tail);
            }
        }
        return foundSource;
    }

    private boolean isAllowedSource(String[] parts, String table, Set<String> cteNames,
                                    TableTarget target, String activeSchema) {
        if (cteNames.contains(table)) return parts.length == 1;
        if (!(table.equals("dataset") || table.equals(target.tableName.toLowerCase(Locale.ROOT)))) return false;
        return parts.length != 2 || parts[0].equalsIgnoreCase(activeSchema);
    }

    private String generateInterpretation(List<Map<String, Object>> results, String query) {
        if (results.isEmpty()) {
            return "The query returned no results. This could mean no matching data exists in the dataset.";
        }

        StringBuilder interpretation = new StringBuilder();
        interpretation.append("The query successfully returned ").append(results.size()).append(" row(s). ");

        String upperQuery = query.toUpperCase();
        if (upperQuery.contains("COUNT(")) {
            interpretation.append("This appears to be a count query showing the number of records matching your criteria.");
        } else if (upperQuery.contains("GROUP BY")) {
            interpretation.append("This is an aggregated query showing grouped data.");
        } else if (upperQuery.contains("WHERE")) {
            interpretation.append("The results are filtered based on the WHERE clause conditions.");
        } else {
            interpretation.append("This shows raw data from the dataset.");
        }

        return interpretation.toString();
    }
}
