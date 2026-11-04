package com.synapse.backend.services;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.SqlResult;
import com.synapse.backend.tools.SqlQueryTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("legacy")
public class LocalDatasetClient implements DatasetClient {
    private final CsvStorageService storage;
    private final DatasetMetadataService metadata;
    private final SqlQueryTool queries;

    public LocalDatasetClient(CsvStorageService storage, DatasetMetadataService metadata, SqlQueryTool queries) {
        this.storage = storage;
        this.metadata = metadata;
        this.queries = queries;
    }

    @Override
    public Optional<CsvDocument> getDocument(String documentId, String conversationId, RequestIdentity.Identity identity) {
        return storage.getByIdForConversationAndUser(documentId, conversationId, identity.subject());
    }

    @Override
    public String getDatasetContext(String documentId, String conversationId, RequestIdentity.Identity identity) {
        return storage.getMetadataJsonForConversationAndUser(documentId, conversationId, identity.subject())
                .map(metadata::metadataFromJson).map(metadata::formatMetadataForSystemPrompt).orElse("");
    }

    @Override
    public SqlResult queryDataset(String conversationId, String documentId, String query, RequestIdentity.Identity identity) {
        return queries.queryDataset(conversationId, documentId, query);
    }

    @Override
    public String getDatasetSchema(String conversationId, String documentId, RequestIdentity.Identity identity) {
        return queries.getDatasetSchema(conversationId, documentId);
    }

    @Override
    public String getRowCount(String conversationId, String documentId, RequestIdentity.Identity identity) {
        return queries.getRowCount(conversationId, documentId);
    }
}
