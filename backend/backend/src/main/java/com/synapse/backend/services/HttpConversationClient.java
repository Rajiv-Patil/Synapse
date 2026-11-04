package com.synapse.backend.services;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.Conversation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

@Component
@Profile({"dataset", "ai"})
public class HttpConversationClient implements ConversationClient {
    private final RestClient client;

    public HttpConversationClient(@Value("${services.conversation.base-url:http://localhost:8080}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public boolean existsForUser(String conversationId, RequestIdentity.Identity identity) {
        try {
            client.get().uri("/api/internal/conversations/{id}", conversationId)
                    .headers(headers -> identity.applyHeaders(headers))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return false;
            throw exception;
        }
    }

    @Override
    public boolean attachDocument(String conversationId, String documentId, String documentName,
                                  RequestIdentity.Identity identity) {
        try {
            client.post().uri("/api/internal/conversations/{id}/document", conversationId)
                    .headers(headers -> identity.applyHeaders(headers))
                    .body(Map.of("documentId", documentId, "documentName", documentName))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return false;
            throw exception;
        }
    }

    @Override
    public Conversation createConversation(String title, RequestIdentity.Identity identity) {
        return client.post().uri("/api/internal/conversations")
                .headers(headers -> identity.applyHeaders(headers))
                .body(Map.of("title", title == null || title.isBlank() ? "New Analysis" : title))
                .retrieve().body(Conversation.class);
    }

    @Override
    public Optional<Conversation> getConversation(String conversationId, RequestIdentity.Identity identity) {
        try {
            Conversation conversation = client.get().uri("/api/internal/conversations/{id}", conversationId)
                    .headers(headers -> identity.applyHeaders(headers))
                    .retrieve().body(Conversation.class);
            return Optional.ofNullable(conversation);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return Optional.empty();
            throw exception;
        }
    }

    @Override
    public Conversation addMessage(String conversationId, ChatMessage message, RequestIdentity.Identity identity) {
        return client.post().uri("/api/internal/conversations/{id}/messages", conversationId)
                .headers(headers -> identity.applyHeaders(headers))
                .body(message)
                .retrieve().body(Conversation.class);
    }
}
