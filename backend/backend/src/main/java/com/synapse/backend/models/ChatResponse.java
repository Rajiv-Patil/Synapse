package com.synapse.backend.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatResponse {
    private String content;
    private SqlResult sqlResult;
}
