package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetMetadata {
    private String tableName;
    private String schemaName;
    private long rowCount;
    private int columnCount;
    private List<ColumnDefinition> columns;
    private Map<String, Object> statistics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnDefinition {
        private String name;
        private String dataType;
        private String inferredType;
        private long nullCount;
        private double nullPercentage;
        private List<String> uniqueValues;
        private int uniqueCount;
        private Object minValue;
        private Object maxValue;
        private Double averageValue;
    }
}
