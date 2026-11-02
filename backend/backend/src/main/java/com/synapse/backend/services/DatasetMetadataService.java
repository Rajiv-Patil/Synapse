package com.synapse.backend.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapse.backend.models.ColumnMetadata;
import com.synapse.backend.models.DatasetMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DatasetMetadataService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DatasetMetadataService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public DatasetMetadata extractMetadata(String conversationId, String documentId, String tableName,
                                           List<ColumnMetadata> columnMetadataList) {
        String schemaName = "conv_" + conversationId.replace("-", "_");
        String fullTableName = "\"" + schemaName + "\".\"" + tableName + "\"";

        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + fullTableName,
                Long.class
        );

        List<Map<String, Object>> columnsInfo = jdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                schemaName, tableName
        );

        List<DatasetMetadata.ColumnDefinition> columnDefinitions = new ArrayList<>();
        Map<String, Object> statistics = new HashMap<>();

        for (Map<String, Object> colInfo : columnsInfo) {
            String colName = (String) colInfo.get("column_name");
            String dataType = (String) colInfo.get("data_type");

            ColumnMetadata csvMeta = columnMetadataList.stream()
                    .filter(cm -> cm.getName().equals(colName))
                    .findFirst()
                    .orElse(null);

            Long nullCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + fullTableName + " WHERE \"" + colName + "\" IS NULL OR \"" + colName + "\"::text = ''",
                    Long.class
            );

            double nullPercentage = rowCount != null && rowCount > 0 ? (nullCount * 100.0 / rowCount) : 0.0;

            List<String> uniqueValues = jdbcTemplate.queryForList(
                    "SELECT DISTINCT \"" + colName + "\" FROM " + fullTableName +
                            " WHERE \"" + colName + "\" IS NOT NULL AND \"" + colName + "\"::text != '' " +
                            " LIMIT 100",
                    String.class
            );

            int uniqueCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT \"" + colName + "\") FROM " + fullTableName +
                            " WHERE \"" + colName + "\" IS NOT NULL AND \"" + colName + "\"::text != ''",
                    Integer.class
            );

            Object minValue = null;
            Object maxValue = null;
            Double averageValue = null;

            if (dataType.contains("int") || dataType.contains("numeric") || dataType.contains("decimal") ||
                    dataType.contains("real") || dataType.contains("double") ||
                    (csvMeta != null && ("INTEGER".equals(csvMeta.getInferredType()) || "DECIMAL".equals(csvMeta.getInferredType())))) {

                try {
                    minValue = jdbcTemplate.queryForObject(
                            "SELECT MIN(\"" + colName + "\"::numeric) FROM " + fullTableName +
                                    " WHERE \"" + colName + "\" IS NOT NULL AND \"" + colName + "\"::text != ''",
                            Object.class
                    );
                    maxValue = jdbcTemplate.queryForObject(
                            "SELECT MAX(\"" + colName + "\"::numeric) FROM " + fullTableName +
                                    " WHERE \"" + colName + "\" IS NOT NULL AND \"" + colName + "\"::text != ''",
                            Object.class
                    );
                    averageValue = jdbcTemplate.queryForObject(
                            "SELECT AVG(\"" + colName + "\"::numeric) FROM " + fullTableName +
                                    " WHERE \"" + colName + "\" IS NOT NULL AND \"" + colName + "\"::text != ''",
                            Double.class
                    );
                } catch (Exception ignored) {
                }
            }

            DatasetMetadata.ColumnDefinition colDef = DatasetMetadata.ColumnDefinition.builder()
                    .name(colName)
                    .dataType(dataType)
                    .inferredType(csvMeta != null ? csvMeta.getInferredType() : "STRING")
                    .nullCount(nullCount)
                    .nullPercentage(nullPercentage)
                    .uniqueValues(uniqueValues.size() > 20 ? uniqueValues.subList(0, 20) : uniqueValues)
                    .uniqueCount(uniqueCount)
                    .minValue(minValue)
                    .maxValue(maxValue)
                    .averageValue(averageValue)
                    .build();

            columnDefinitions.add(colDef);

            Map<String, Object> colStats = new HashMap<>();
            colStats.put("nullCount", nullCount);
            colStats.put("nullPercentage", nullPercentage);
            colStats.put("uniqueCount", uniqueCount);
            if (minValue != null) colStats.put("min", minValue);
            if (maxValue != null) colStats.put("max", maxValue);
            if (averageValue != null) colStats.put("average", averageValue);
            statistics.put(colName, colStats);
        }

        return DatasetMetadata.builder()
                .tableName(tableName)
                .schemaName(schemaName)
                .rowCount(rowCount != null ? rowCount : 0)
                .columnCount(columnDefinitions.size())
                .columns(columnDefinitions)
                .statistics(statistics)
                .build();
    }

    public String formatMetadataForSystemPrompt(DatasetMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DATASET CONTEXT ===\n");
        // Always present the table as 'dataset' to prevent the model from hardcoding dynamic schema names
        sb.append("Table: dataset\n");
        sb.append("Dimensions: ").append(metadata.getRowCount()).append(" rows x ").append(metadata.getColumnCount()).append(" columns\n\n");

        sb.append("Schema Definition:\n");
        for (DatasetMetadata.ColumnDefinition col : metadata.getColumns()) {
            sb.append("  - ").append(col.getName())
                    .append(" (").append(col.getDataType()).append(")");
            if (col.getInferredType() != null) {
                sb.append(" [inferred: ").append(col.getInferredType()).append("]");
            }
            sb.append("\n");
        }

        sb.append("\nColumn Statistics:\n");
        for (DatasetMetadata.ColumnDefinition col : metadata.getColumns()) {
            sb.append("  ").append(col.getName()).append(":\n");
            sb.append("    - Null values: ").append(col.getNullCount())
                    .append(" (").append(String.format("%.1f%%", col.getNullPercentage())).append(")\n");
            sb.append("    - Unique values: ").append(col.getUniqueCount()).append("\n");
            if (col.getMinValue() != null) {
                sb.append("    - Min: ").append(col.getMinValue()).append("\n");
            }
            if (col.getMaxValue() != null) {
                sb.append("    - Max: ").append(col.getMaxValue()).append("\n");
            }
            if (col.getAverageValue() != null) {
                sb.append("    - Average: ").append(String.format("%.2f", col.getAverageValue())).append("\n");
            }
            if (!col.getUniqueValues().isEmpty()) {
                sb.append("    - Sample values: ").append(String.join(", ", col.getUniqueValues())).append("\n");
            }
        }

        sb.append("=====================\n");
        return sb.toString();
    }

    public String metadataToJson(DatasetMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }

    public DatasetMetadata metadataFromJson(String json) {
        try {
            return objectMapper.readValue(json, DatasetMetadata.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize metadata", e);
        }
    }
}