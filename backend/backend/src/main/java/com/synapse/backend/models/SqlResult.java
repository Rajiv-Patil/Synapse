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
public class SqlResult {
    private String query;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private int count;
}
