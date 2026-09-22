import { useState } from 'react';
import { useChat } from '../hooks/useChat';
import { MessageList } from './MessageList';

interface Props {
    documentId?: string;
}

export function ChatInterface({ documentId }: Props) {
    const { messages, isLoading, error, sendMessage, clear } = useChat(documentId);
    const [input, setInput] = useState('');

    const handleSend = () => {
        if (!input.trim() || isLoading) return;
        sendMessage(input);
        setInput('');
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    return (
        <div style={S.container}>
            {/* Header */}
            <div style={S.header}>
                <span style={S.headerTitle}>Synapse Assistant</span>
                <div style={S.headerRight}>
                    <span style={S.docBadge}>
                        {documentId ? `doc: ${documentId.slice(0, 8)}` : 'no document'}
                    </span>
                    {messages.length > 0 && (
                        <button style={S.clearBtn} onClick={clear} title="Clear conversation">
                            Clear
                        </button>
                    )}
                </div>
            </div>

            {/* Error banner */}
            {error && <div style={S.err}>Error: {error}</div>}

            {/* Messages */}
            <MessageList messages={messages} isLoading={isLoading} />

            {/* Input area */}
            <div style={S.inputArea}>
                <textarea
                    style={S.textarea}
                    rows={2}
                    placeholder={documentId ? 'Ask about your data...' : 'Upload a file first...'}
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={handleKeyDown}
                    disabled={isLoading}
                />
                <button
                    style={{
                        ...S.sendBtn,
                        ...(input.trim() && !isLoading ? S.sendBtnActive : {}),
                    }}
                    onClick={handleSend}
                    disabled={!input.trim() || isLoading}
                >
                    Send
                </button>
            </div>
        </div>
    );
}

const S: Record<string, React.CSSProperties> = {
    container: {
        display: 'flex', flexDirection: 'column',
        height: '100%', background: '#0d1117',
        border: '1px solid #30363d', borderRadius: 8, overflow: 'hidden',
    },
    header: {
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '12px 16px', borderBottom: '1px solid #21262d', background: '#161b22',
    },
    headerTitle: { fontWeight: 600, fontSize: 13, color: '#e6edf3' },
    headerRight: { display: 'flex', alignItems: 'center', gap: 8 },
    docBadge:    { fontSize: 11, color: '#7d8590', background: '#21262d', padding: '2px 8px', borderRadius: 10 },
    clearBtn:    { background: 'none', border: 'none', color: '#7d8590', cursor: 'pointer', fontSize: 12 },
    err:         { background: '#3d1c1c', color: '#f85149', padding: '8px 16px', fontSize: 12, borderBottom: '1px solid #f8514933' },
    inputArea:   {
        display: 'flex', gap: 8, padding: '12px 16px',
        borderTop: '1px solid #21262d', background: '#161b22', alignItems: 'flex-end',
    },
    textarea: {
        flex: 1, resize: 'none', background: '#0d1117',
        border: '1px solid #30363d', borderRadius: 6,
        padding: '8px 10px', color: '#e6edf3', fontSize: 13,
        lineHeight: 1.4, outline: 'none',
    },
    sendBtn: {
        padding: '8px 14px', borderRadius: 6,
        background: '#21262d', border: '1px solid #30363d',
        color: '#7d8590', cursor: 'not-allowed', fontSize: 13,
        transition: 'all 0.15s',
    },
    sendBtnActive: {
        background: '#238636', borderColor: '#2ea043',
        color: '#ffffff', cursor: 'pointer',
    },
};

export default ChatInterface;
