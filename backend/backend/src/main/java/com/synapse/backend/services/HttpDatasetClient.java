package com.synapse.backend.services;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.SqlResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

@Component
@Profile("ai")
public class HttpDatasetClient implements DatasetClient {
    private final RestClient client;

    public HttpDatasetClient(@Value("${services.dataset.base-url:http://localhost:8080}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public Optional<CsvDocument> getDocument(String documentId, String conversationId, RequestIdentity.Identity identity) {
        try {
            CsvDocument document = client.get()
                    .uri(uri -> uri.path("/api/csv/internal/csv/{id}").queryParam("conversationId", conversationId).build(documentId))
                    .headers(identity::applyHeaders).retrieve().body(CsvDocument.class);
            return Optional.ofNullable(document);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return Optional.empty();
            throw exception;
        }
    }

    @Override
    public String getDatasetContext(String documentId, String conversationId, RequestIdentity.Identity identity) {
        return client.get()
                .uri(uri -> uri.path("/api/csv/internal/csv/{id}/context").queryParam("conversationId", conversationId).build(documentId))
                .headers(identity::applyHeaders).retrieve().body(String.class);
    }

    @Override
    public SqlResult queryDataset(String conversationId, String documentId, String query, RequestIdentity.Identity identity) {
        return client.post().uri("/api/csv/internal/dataset/query")
                .headers(identity::applyHeaders)
                .body(Map.of("conversationId", conversationId, "documentId", documentId, "query", query))
                .retrieve().body(SqlResult.class);
    }

    @Override
    public String getDatasetSchema(String conversationId, String documentId, RequestIdentity.Identity identity) {
        return client.get()
                .uri(uri -> uri.path("/api/csv/internal/dataset/schema").queryParam("conversationId", conversationId)
                        .queryParam("documentId", documentId).build())
                .headers(identity::applyHeaders).retrieve().body(String.class);
    }

    @Override
    public String getRowCount(String conversationId, String documentId, RequestIdentity.Identity identity) {
        return client.get()
                .uri(uri -> uri.path("/api/csv/internal/dataset/row-count").queryParam("conversationId", conversationId)
                        .queryParam("documentId", documentId).build())
                .headers(identity::applyHeaders).retrieve().body(String.class);
    }
}
