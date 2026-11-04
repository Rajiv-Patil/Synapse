package com.synapse.backend.services;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.SqlResult;

import java.util.Optional;

public interface DatasetClient {
    Optional<CsvDocument> getDocument(String documentId, String conversationId, RequestIdentity.Identity identity);
    String getDatasetContext(String documentId, String conversationId, RequestIdentity.Identity identity);
    SqlResult queryDataset(String conversationId, String documentId, String query, RequestIdentity.Identity identity);
    String getDatasetSchema(String conversationId, String documentId, RequestIdentity.Identity identity);
    String getRowCount(String conversationId, String documentId, RequestIdentity.Identity identity);
}
