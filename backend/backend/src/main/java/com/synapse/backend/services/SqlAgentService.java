// FILE: main/java/com/synapse/backend/services/SqlAgentService.java
package com.synapse.backend.services;

import com.synapse.backend.tools.SqlQueryTool;
import com.synapse.backend.models.ChatResponse;
import com.synapse.backend.models.SqlResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@ConditionalOnBean(ChatLanguageModel.class)
public class SqlAgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final SqlQueryTool sqlQueryTool;
    private final DatasetMetadataService metadataService;

    public SqlAgentService(ChatLanguageModel chatLanguageModel, SqlQueryTool sqlQueryTool,
                           DatasetMetadataService metadataService) {
        this.chatLanguageModel = chatLanguageModel;
        this.sqlQueryTool = sqlQueryTool;
        this.metadataService = metadataService;
    }

    public String processQuery(String userQuery, String conversationId, String documentId, String datasetContext) {
        return processStructuredQuery(userQuery, conversationId, documentId, datasetContext).getContent();
    }

    public ChatResponse processStructuredQuery(String userQuery, String conversationId, String documentId, String datasetContext) {
        String lowerQuery = userQuery.toLowerCase().trim();

        // 1. Direct Schema metadata queries
        if (lowerQuery.equals("schema") || lowerQuery.equals("show schema") || lowerQuery.equals("describe schema")) {
            return new ChatResponse(sqlQueryTool.getDatasetSchema(conversationId, documentId), null);
        }

        // 2. Direct Row count queries
        if (lowerQuery.equals("count") || lowerQuery.equals("row count") || lowerQuery.equals("how many rows")) {
            return new ChatResponse(sqlQueryTool.getRowCount(conversationId, documentId), null);
        }

        // 3. Generate SQL query for all NLP inquiries
        String generatedSql = generateSqlQuery(userQuery, datasetContext, null, null);

        if (generatedSql != null && !generatedSql.isBlank()) {
            SqlResult result = sqlQueryTool.queryDataset(conversationId, documentId, generatedSql);

            // Self-healing reflection loop if PostgreSQL throws an error
            if (result == null) {
                String correctedSql = generateSqlQuery(userQuery, datasetContext, generatedSql, "Query execution failed");
                if (correctedSql != null && !correctedSql.isBlank()) {
                    result = sqlQueryTool.queryDataset(conversationId, documentId, correctedSql);
                }
            }
            if (result == null) return new ChatResponse("I couldn't execute that query. Please try rephrasing it.", null);
            return new ChatResponse(interpret(userQuery, result), result);
        }

        // 4. Fallback to general conversational response
        String fallbackPrompt = "You are a helpful data analysis assistant.\n\n"
                + (datasetContext != null && !datasetContext.isBlank() ? datasetContext + "\n\n" : "")
                + "Answer the following question clearly in plain natural language.\n"
                + "CRITICAL: Do NOT invoke tools. Do NOT return JSON.\n\n"
                + "User: " + userQuery;

        return new ChatResponse(chatLanguageModel.generate(fallbackPrompt), null);
    }

    private String interpret(String question, SqlResult result) {
        List<Map<String, Object>> safeRows = result.getRows().stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>();
            row.forEach((key, value) -> { if (!key.toLowerCase().matches(".*(id|uuid|token|key)$")) safe.put(key, value); });
            return safe;
        }).toList();
        String fallback = result.getCount() == 0 ? "The query returned no matching rows." : "The query returned " + result.getCount() + " row(s).";
        try {
            String prompt = "Answer the user's question concisely using only the SQL result below. Do not make unsupported claims.\nQuestion: " + question
                    + "\nReturned count: " + result.getCount() + "\nColumns and sample rows: " + safeRows;
            String answer = chatLanguageModel.generate(prompt);
            return answer == null || answer.isBlank() ? fallback : answer.trim();
        } catch (Exception ignored) { return fallback; }
    }

    private String generateSqlQuery(String userQuery, String datasetContext, String previousFailedSql, String errorMessage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a PostgreSQL expert. Convert the user's natural language question into a PostgreSQL SELECT query.\n\n");

        if (datasetContext != null && !datasetContext.isBlank()) {
            prompt.append(datasetContext).append("\n\n");
        }

        if (previousFailedSql != null && errorMessage != null) {
            prompt.append("PREVIOUS QUERY FAILED WITH ERROR:\n");
            prompt.append("Failed SQL: ").append(previousFailedSql).append("\n");
            prompt.append("Database Error: ").append(errorMessage).append("\n");
            prompt.append("Fix the error and generate a corrected query.\n\n");
        }

        prompt.append("CRITICAL INSTRUCTIONS:\n");
        prompt.append("1. Return ONLY ONE single SELECT statement. NEVER output multiple separate queries.\n");
        prompt.append("2. Return plain text SQL only. No explanations, no markdown (no ```sql), no JSON, and no tool calls.\n");
        prompt.append("3. Do NOT end the query with a semicolon (;).\n");
        prompt.append("4. In the FROM clause, ALWAYS use the table name 'dataset' (e.g., SELECT * FROM dataset WHERE ...).\n");
        prompt.append("5. Dataset columns are stored as TEXT. For numeric comparisons, sorting, or math/aggregates, cast the column (e.g., CAST(salary AS NUMERIC) > 10, or ORDER BY CAST(salary AS NUMERIC) DESC LIMIT 3).\n");
        prompt.append("6. For queries asking for 'top N' or 'bottom N', use ORDER BY with CAST to NUMERIC and apply LIMIT N.\n");
        prompt.append("7. Generate ONLY SELECT or WITH queries. Never generate INSERT, UPDATE, DELETE, or DROP.\n\n");
        prompt.append("User question: ").append(userQuery).append("\n");
        prompt.append("SQL query:");

        try {
            String response = chatLanguageModel.generate(prompt.toString());
            if (response == null) return null;

            return sanitizeAndExtractSingleQuery(response.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeAndExtractSingleQuery(String sql) {
        // Strip markdown code fences
        if (sql.startsWith("```sql")) {
            sql = sql.substring(6);
        }
        if (sql.startsWith("```")) {
            sql = sql.substring(3);
        }
        if (sql.endsWith("```")) {
            sql = sql.substring(0, sql.length() - 3);
        }
        sql = sql.trim();

        // Extract if wrapped in JSON tool calls
        if (sql.startsWith("{") && sql.contains("\"query\"")) {
            int start = sql.indexOf("\"query\"");
            int colon = sql.indexOf(":", start);
            int quoteStart = sql.indexOf("\"", colon);
            int quoteEnd = sql.lastIndexOf("\"");
            if (quoteStart != -1 && quoteEnd > quoteStart) {
                sql = sql.substring(quoteStart + 1, quoteEnd);
            }
        }

        // Parenthesis-aware extraction to avoid cutting subqueries like "FROM (SELECT ...)"
        sql = extractFirstCompleteStatement(sql);

        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }

        String upper = sql.toUpperCase();
        return (upper.startsWith("SELECT") || upper.startsWith("WITH")) ? sql : null;
    }

    /**
     * Safely isolates the first complete SQL statement without breaking valid subqueries.
     */
    private String extractFirstCompleteStatement(String sql) {
        // If separated by a semicolon, take the first non-empty block
        if (sql.contains(";")) {
            String[] parts = sql.split(";");
            for (String part : parts) {
                if (!part.trim().isBlank()) {
                    sql = part.trim();
                    break;
                }
            }
        }

        // Line-by-line parenthesis tracking to distinguish subqueries from second independent queries
        String[] lines = sql.split("\\r?\\n");
        StringBuilder firstQuery = new StringBuilder();
        int parenDepth = 0;
        boolean foundFirstQuery = false;

        for (String line : lines) {
            String trimmedLine = line.trim();
            String upperLine = trimmedLine.toUpperCase();

            // If we are at top-level (parenDepth == 0) and encounter an independent second query
            if (foundFirstQuery && parenDepth == 0 && (upperLine.startsWith("SELECT ") || upperLine.startsWith("WITH "))) {
                break;
            }

            // Track parentheses on this line
            for (char c : trimmedLine.toCharArray()) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth = Math.max(0, parenDepth - 1);
            }

            if (upperLine.startsWith("SELECT ") || upperLine.startsWith("WITH ")) {
                foundFirstQuery = true;
            }

            firstQuery.append(line).append("\n");
        }

        return firstQuery.toString().trim();
    }
}
