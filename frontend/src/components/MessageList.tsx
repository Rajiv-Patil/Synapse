import { useEffect, useRef } from 'react';
import type { Message } from '../types';

interface Props {
    messages: Message[];
    isLoading: boolean;
}

export function MessageList({ messages, isLoading }: Props) {
    const bottomRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, isLoading]);

    if (messages.length === 0) {
        return (
            <div style={S.empty}>
                <div style={S.emptyTitle}>Synapse Assistant</div>
                <div style={S.emptySub}>
                    Inquire: "Describe the schema" or "Which columns have nulls?"
                </div>
            </div>
        );
    }

    return (
        <div style={S.list}>
            {messages.map(msg => (
                <div
                    key={msg.id}
                    style={{
                        ...S.bubble,
                        ...(msg.role === 'user' ? S.userBubble : S.assistantBubble),
                    }}
                >
                    <span style={S.role}>
                        {msg.role === 'user' ? 'You' : 'Assistant'}
                    </span>
                    <div style={S.content}>
                        {msg.content || <span style={{ color: '#7d8590', fontStyle: 'italic' }}>Generating response...</span>}
                    </div>
                </div>
            ))}

            {isLoading && (
                <div style={{ ...S.bubble, ...S.assistantBubble }}>
                    <span style={S.role}>Assistant</span>
                    <div style={{ color: '#7d8590', fontSize: 13 }}>Processing...</div>
                </div>
            )}

            <div ref={bottomRef} />
        </div>
    );
}

const S: Record<string, React.CSSProperties> = {
    list: {
        flex: 1, overflowY: 'auto', padding: '16px 20px',
        display: 'flex', flexDirection: 'column', gap: 12,
    },
    empty: {
        flex: 1, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center',
        color: '#7d8590', gap: 8, padding: 32, textAlign: 'center',
    },
    emptyTitle: { fontWeight: 600, fontSize: 16, color: '#c9d1d9' },
    emptySub:   { fontSize: 13, maxWidth: 360, lineHeight: 1.5 },
    bubble: {
        maxWidth: '80%', padding: '10px 14px', borderRadius: 8,
        fontSize: 14, lineHeight: 1.5, wordBreak: 'break-word',
    },
    userBubble: {
        alignSelf: 'flex-end', background: '#1f6feb', color: '#ffffff',
    },
    assistantBubble: {
        alignSelf: 'flex-start', background: '#161b22',
        border: '1px solid #30363d', color: '#e6edf3',
    },
    role: {
        display: 'block', fontSize: 10, fontWeight: 700,
        textTransform: 'uppercase', letterSpacing: '0.05em',
        opacity: 0.6, marginBottom: 4,
    },
    content: { whiteSpace: 'pre-wrap' },
};
export default MessageList;
