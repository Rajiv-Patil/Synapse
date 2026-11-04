package com.synapse.backend.services;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("legacy")
public class LocalConversationClient implements ConversationClient {
    private final ConversationService conversations;

    public LocalConversationClient(ConversationService conversations) {
        this.conversations = conversations;
    }

    @Override
    public boolean existsForUser(String conversationId, RequestIdentity.Identity identity) {
        return conversations.getByIdAndUser(conversationId, identity.userId()).isPresent();
    }

    @Override
    public boolean attachDocument(String conversationId, String documentId, String documentName,
                                  RequestIdentity.Identity identity) {
        return conversations.attachDocument(conversationId, identity.userId(), documentId, documentName);
    }

    @Override
    public com.synapse.backend.models.Conversation createConversation(String title, RequestIdentity.Identity identity) {
        return conversations.createConversation(title, identity.userId(), identity.subject(),
                identity.email(), identity.name(), null, null);
    }

    @Override
    public Optional<com.synapse.backend.models.Conversation> getConversation(String conversationId,
                                                                              RequestIdentity.Identity identity) {
        return conversations.getByIdAndUser(conversationId, identity.userId());
    }

    @Override
    public com.synapse.backend.models.Conversation addMessage(String conversationId,
            com.synapse.backend.models.ChatMessage message, RequestIdentity.Identity identity) {
        return conversations.addMessage(conversationId, identity.userId(), message);
    }
}
