package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvHealthCheckReport {
    private String status;
    private int originalRowCount;
    private int healedRowCount;
    private int originalColumnCount;
    private int healedColumnCount;
    private String detectedDelimiter;
    private boolean autoHealed;
    private String summary;
    @Builder.Default
    private List<CsvIssue> issues = new ArrayList<>();
    @Builder.Default
    private List<ColumnMetadata> columns = new ArrayList<>();
}
