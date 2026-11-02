import { useState, useCallback, useRef } from 'react';
import type { Message } from '../types';
import { api } from '../services/api';

export function useChat(documentId?: string) {
    const [messages, setMessages] = useState<Message[]>([]);
    const [isLoading, setLoading] = useState(false);
    const [error,     setError]   = useState<string | null>(null);

    // Stable session ID for the lifetime of this hook instance
    const sessionId   = useRef(crypto.randomUUID()).current;
    const conversationIdRef = useRef<string | undefined>(undefined);
    // Ref to the current abort function (to cancel streaming on unmount)
    const cancelRef   = useRef<(() => void) | null>(null);

    const sendMessage = useCallback(async (prompt: string) => {
        if (!prompt.trim() || isLoading) return;

        const userMsg: Message = {
            id: crypto.randomUUID(), role: 'user',
            content: prompt, timestamp: new Date().toISOString(),
        };
        const assistantMsg: Message = {
            id: crypto.randomUUID(), role: 'assistant',
            content: '', timestamp: new Date().toISOString(),
        };

        setMessages(prev => [...prev, userMsg, assistantMsg]);
        setLoading(true);
        setError(null);

        cancelRef.current = api.streamChat(
            { prompt, history: messages, documentId, sessionId, conversationId: conversationIdRef.current },
            // onToken — append each streamed token to the assistant message
            (token: string) => {
                setMessages(prev => prev.map(m =>
                    m.id === assistantMsg.id ? { ...m, content: m.content + token } : m
                ));
            },
            // onDone
            (conversationId: string, completedMessage: Message) => {
                conversationIdRef.current = conversationId;
                setMessages(prev => prev.map(m => m.id === assistantMsg.id
                    ? { ...m, sqlResult: completedMessage.sqlResult ?? null }
                    : m));
                setLoading(false);
            },
            // onError
            (err: Error) => {
                setError(err.message);
                setMessages(prev => prev.filter(m => m.id !== assistantMsg.id));
                setLoading(false);
            },
        );
    }, [messages, documentId, sessionId, isLoading]);

    const clear = useCallback(() => {
        cancelRef.current?.();
        conversationIdRef.current = undefined;
        setMessages([]);
        setError(null);
        setLoading(false);
    }, []);

    return { messages, isLoading, error, sendMessage, clear, sessionId };
}
