package com.synapse.backend.services;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.Conversation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public Conversation createConversation(String title, String userId, String documentId, String documentName) {
        String id = UUID.randomUUID().toString();
        Conversation conv = Conversation.builder()
                .id(id)
                .title(title != null && !title.isBlank() ? title : "New Analysis")
                .userId(userId != null ? userId : "guest")
                .documentId(documentId)
                .documentName(documentName)
                .messages(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        conversations.put(id, conv);
        return conv;
    }

    public List<Conversation> getConversationsByUser(String userId) {
        String safeUser = userId != null ? userId : "guest";
        List<Conversation> list = new ArrayList<>();
        for (Conversation c : conversations.values()) {
            if (safeUser.equals(c.getUserId()) || "guest".equals(c.getUserId())) {
                list.add(c);
            }
        }
        list.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
        return list;
    }

    public Optional<Conversation> getById(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    public Conversation addMessage(String conversationId, ChatMessage message) {
        Conversation conv = conversations.computeIfAbsent(conversationId, id ->
                Conversation.builder()
                        .id(id)
                        .title("Chat " + id.substring(0, 6))
                        .userId("guest")
                        .messages(new ArrayList<>())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build()
        );

        conv.getMessages().add(message);
        conv.setUpdatedAt(Instant.now());
        return conv;
    }

    public void attachDocument(String conversationId, String documentId, String documentName) {
        Conversation conv = conversations.get(conversationId);
        if (conv != null) {
            conv.setDocumentId(documentId);
            conv.setDocumentName(documentName);
            conv.setUpdatedAt(Instant.now());
        }
    }

    public boolean delete(String id) {
        return conversations.remove(id) != null;
    }
}
