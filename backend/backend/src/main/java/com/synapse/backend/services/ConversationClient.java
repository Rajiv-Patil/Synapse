package com.synapse.backend.services;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.Conversation;

import java.util.Optional;

public interface ConversationClient {
    boolean existsForUser(String conversationId, RequestIdentity.Identity identity);
    boolean attachDocument(String conversationId, String documentId, String documentName,
                           RequestIdentity.Identity identity);
    Conversation createConversation(String title, RequestIdentity.Identity identity);
    Optional<Conversation> getConversation(String conversationId, RequestIdentity.Identity identity);
    Conversation addMessage(String conversationId, ChatMessage message, RequestIdentity.Identity identity);
}
