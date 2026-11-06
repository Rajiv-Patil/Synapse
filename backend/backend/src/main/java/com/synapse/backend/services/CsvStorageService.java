package com.synapse.backend.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapse.backend.models.ColumnMetadata;
import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.CsvHealthCheckReport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.*;

@Service
@org.springframework.context.annotation.Profile({"dataset", "legacy"})
public class CsvStorageService {

    private static final Duration DATASET_CACHE_TTL = Duration.ofMinutes(60);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCacheSupport cache;

    public CsvStorageService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RedisCacheSupport cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    @Transactional
    public CsvDocument save(CsvDocument document, String conversationId, String ownerSub, String metadataJson) {
        String enrichedMetadata = metadataJson;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = objectMapper.readValue(metadataJson, Map.class);
            if (document.getHealedContent() != null) {
                metaMap.put("healedCsvText", new String(document.getHealedContent(), StandardCharsets.UTF_8));
            }
            if (document.getHealthReport() != null) {
                metaMap.put("healthReport", document.getHealthReport());
            }
            if (document.getHeaders() != null) {
                metaMap.put("headers", document.getHeaders());
            }
            if (document.getPreviewRows() != null) {
                metaMap.put("previewRows", document.getPreviewRows());
            }
            enrichedMetadata = objectMapper.writeValueAsString(metaMap);
        } catch (Exception e) {
            // Ignore enrichment failures
        }

        UUID docId = UUID.fromString(document.getId());
        UUID convId = UUID.fromString(conversationId);

        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        try {
            jsonObject.setValue(enrichedMetadata);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to construct JSONB object for database", e);
        }

        String tableName = "dataset_" + document.getId().replace("-", "_");

        jdbcTemplate.update(
                "INSERT INTO csv_documents (id, filename, conversation_id, owner_sub, table_name, original_size_bytes, " +
                        "healed_size_bytes, metadata, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (id) DO UPDATE SET " +
                        "filename = EXCLUDED.filename, " +
                        "conversation_id = EXCLUDED.conversation_id, " +
                        "owner_sub = EXCLUDED.owner_sub, " +
                        "table_name = EXCLUDED.table_name, " +
                        "original_size_bytes = EXCLUDED.original_size_bytes, " +
                        "healed_size_bytes = EXCLUDED.healed_size_bytes, " +
                        "metadata = EXCLUDED.metadata",
                docId,
                document.getFilename(),
                convId,
                ownerSub,
                tableName,
                document.getOriginalSizeBytes(),
                document.getHealedSizeBytes(),
                jsonObject
        );

        return document;
    }

    /**
     * Self-healing recovery: Automatically recreates and repopulates the dataset table
     * in the active schema if an old schema was dropped or desynced.
     */
    @Transactional
    public void restoreDatasetTableIfMissing(String conversationId, String documentId, String tableName) {
        String schemaName = "conv_" + conversationId.replace("-", "_");

        // Check if table is already in this schema
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                schemaName, tableName
        );
        if (count != null && count > 0) {
            return;
        }

        Optional<CsvDocument> docOpt = getByIdForConversation(documentId, conversationId);
        if (docOpt.isEmpty()) {
            return;
        }

        CsvDocument doc = docOpt.get();
        if (doc.getHealedContent() == null || doc.getHealthReport() == null) {
            return;
        }

        // Recreate schema and table
        createDatasetTable(conversationId, documentId, tableName, doc.getHealthReport().getColumns());

        // Parse and repopulate rows
        try {
            List<List<String>> rows = new ArrayList<>();
            try (CSVParser parser = CSVFormat.DEFAULT.parse(
                    new InputStreamReader(new ByteArrayInputStream(doc.getHealedContent()), StandardCharsets.UTF_8))) {
                parser.forEach(record -> {
                    List<String> row = new ArrayList<>();
                    for (String val : record) {
                        row.add(val);
                    }
                    rows.add(row);
                });
            }
            List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : new ArrayList<>();
            insertData(conversationId, tableName, dataRows, doc.getHeaders());
        } catch (Exception e) {
            System.err.println("Failed to repopulate restored dataset table: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<CsvDocument> getByIdForUser(String id, String ownerSub) {
        try {
            UUID documentId = UUID.fromString(id);
            CsvDocument doc = jdbcTemplate.queryForObject(
                    "SELECT * FROM csv_documents WHERE id = ? AND owner_sub = ?",
                    new CsvDocumentRowMapper(objectMapper), documentId, ownerSub);
            return Optional.ofNullable(doc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<CsvDocument> getByIdForConversation(String id, String conversationId) {
        try {
            CsvDocument doc = jdbcTemplate.queryForObject(
                    "SELECT * FROM csv_documents WHERE id = ? AND conversation_id = ?",
                    new CsvDocumentRowMapper(objectMapper), UUID.fromString(id), UUID.fromString(conversationId));
            return Optional.ofNullable(doc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<CsvDocument> getByIdForConversationAndUser(String id, String conversationId, String ownerSub) {
        String cacheKey = "dataset:doc:" + ownerSub + ":" + id;
        Optional<CsvDocument> cached = cache.get(cacheKey, CsvDocument.class);
        if (cached.isPresent()) {
            return cached;
        }

        try {
            CsvDocument doc = jdbcTemplate.queryForObject(
                    "SELECT * FROM csv_documents WHERE id = ? AND conversation_id = ? AND owner_sub = ?",
                    new CsvDocumentRowMapper(objectMapper), UUID.fromString(id), UUID.fromString(conversationId), ownerSub);
            if (doc != null) {
                cache.put(cacheKey, doc, DATASET_CACHE_TTL);
            }
            return Optional.ofNullable(doc);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> getMetadataJsonForConversationAndUser(String id, String conversationId, String ownerSub) {
        String cacheKey = "dataset:ctx:" + ownerSub + ":" + id;
        Optional<String> cached = cache.get(cacheKey, String.class);
        if (cached.isPresent()) {
            return cached;
        }

        try {
            String metadata = jdbcTemplate.queryForObject(
                    "SELECT metadata::text FROM csv_documents WHERE id = ? AND conversation_id = ? AND owner_sub = ?",
                    String.class, UUID.fromString(id), UUID.fromString(conversationId), ownerSub);
            if (metadata != null) {
                cache.put(cacheKey, metadata, DATASET_CACHE_TTL);
            }
            return Optional.ofNullable(metadata);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public boolean belongsToConversation(String documentId, String conversationId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM csv_documents WHERE id = ? AND conversation_id = ?",
                    Integer.class, UUID.fromString(documentId), UUID.fromString(conversationId));
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public boolean belongsToConversationForUser(String documentId, String conversationId, String ownerSub) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM csv_documents WHERE id = ? AND conversation_id = ? AND owner_sub = ?",
                    Integer.class, UUID.fromString(documentId), UUID.fromString(conversationId), ownerSub);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public boolean delete(String id) {
        UUID documentId = UUID.fromString(id);
        int rows = jdbcTemplate.update("DELETE FROM csv_documents WHERE id = ?", documentId);
        return rows > 0;
    }

    @Transactional
    public void createDatasetTable(String conversationId, String documentId, String tableName,
                                   List<ColumnMetadata> columns) {
        String schemaName = "conv_" + conversationId.replace("-", "_");
        String fullTableName = "\"" + schemaName + "\".\"" + tableName + "\"";

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS " + fullTableName + " (");
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            String pgType = mapInferredTypeToPostgresType(col.getInferredType());
            String colName = col.getName().replace("\"", "\"\"");

            sql.append("\"").append(colName).append("\" ").append(pgType);
            if (i < columns.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");

        jdbcTemplate.execute(sql.toString());
    }

    @Transactional
    public void insertData(String conversationId, String tableName, List<List<String>> data, List<String> headers) {
        String schemaName = "conv_" + conversationId.replace("-", "_");
        String fullTableName = "\"" + schemaName + "\".\"" + tableName + "\"";

        StringBuilder placeholders = new StringBuilder();
        List<String> quotedHeaders = new ArrayList<>();

        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
            quotedHeaders.add("\"" + headers.get(i).replace("\"", "\"\"") + "\"");
        }

        String insertSql = "INSERT INTO " + fullTableName + " (" +
                String.join(", ", quotedHeaders) + ") VALUES (" + placeholders + ")";

        jdbcTemplate.batchUpdate(insertSql, data, data.size(), (ps, row) -> {
            for (int i = 0; i < headers.size(); i++) {
                String value = (i < row.size()) ? row.get(i) : null;
                if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value)) {
                    ps.setNull(i + 1, Types.NULL);
                } else {
                    ps.setObject(i + 1, value, Types.OTHER);
                }
            }
        });
    }

    private String mapInferredTypeToPostgresType(String inferredType) {
        return "TEXT";
    }

    private static class CsvDocumentRowMapper implements RowMapper<CsvDocument> {
        private final ObjectMapper mapper;

        public CsvDocumentRowMapper(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public CsvDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
            byte[] healedContent = null;
            CsvHealthCheckReport healthReport = null;
            List<String> headers = new ArrayList<>();
            List<Map<String, Object>> previewRows = new ArrayList<>();

            try {
                String metadataStr = rs.getString("metadata");
                if (metadataStr != null) {
                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(metadataStr);
                    if (node.has("healedCsvText")) {
                        healedContent = node.get("healedCsvText").asText().getBytes(StandardCharsets.UTF_8);
                    }
                    if (node.has("healthReport")) {
                        healthReport = mapper.treeToValue(node.get("healthReport"), CsvHealthCheckReport.class);
                    }
                    if (node.has("headers")) {
                        headers = mapper.convertValue(node.get("headers"), new TypeReference<List<String>>() {});
                    }
                    if (node.has("previewRows")) {
                        previewRows = mapper.convertValue(node.get("previewRows"), new TypeReference<List<Map<String, Object>>>() {});
                    }
                }
            } catch (Exception e) {
                // Ignore parse failures
            }

            return CsvDocument.builder()
                    .id(rs.getObject("id", UUID.class).toString())
                    .filename(rs.getString("filename"))
                    .originalSizeBytes(rs.getLong("original_size_bytes"))
                    .healedSizeBytes(rs.getLong("healed_size_bytes"))
                    .healedContent(healedContent)
                    .healthReport(healthReport)
                    .headers(headers)
                    .previewRows(previewRows)
                    .uploadedAt(rs.getTimestamp("uploaded_at").toInstant())
                    .build();
        }
    }
}
