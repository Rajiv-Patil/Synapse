package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadata {
    private String name;
    private String originalName;
    private String inferredType;
    private int nullCount;
    private int totalCount;
    private List<String> sampleValues;
}
