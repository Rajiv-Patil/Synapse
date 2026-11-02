import { useEffect, useRef, useState } from 'react';
import type { Message } from '../types';

interface Props {
    messages: Message[];
    isLoading: boolean;
}

export function MessageList({ messages, isLoading }: Props) {
    const bottomRef = useRef<HTMLDivElement>(null);
    const [expandedResults, setExpandedResults] = useState<Set<string>>(() => new Set());
    const [expandedQueries, setExpandedQueries] = useState<Set<string>>(() => new Set());

    const toggle = (setValue: React.Dispatch<React.SetStateAction<Set<string>>>, id: string) => {
        setValue(previous => {
            const next = new Set(previous);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    };

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
                    {msg.sqlResult && <section style={S.result}>
                        <button type="button" style={S.disclosure} aria-expanded={expandedResults.has(msg.id)}
                            onClick={() => toggle(setExpandedResults, msg.id)}>
                            <span style={S.chevron}>{expandedResults.has(msg.id) ? '▾' : '▸'}</span>
                            Results ({msg.sqlResult.count} rows)
                        </button>
                        {expandedResults.has(msg.id) && <div style={{ overflowX: 'auto', marginTop: 8 }}>
                            {msg.sqlResult.columns.length === 0 ? <div>No results found.</div> : <table style={S.table}>
                                <thead><tr>{msg.sqlResult.columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
                                <tbody>{msg.sqlResult.rows.map((row, i) => <tr key={i}>{msg.sqlResult!.columns.map(c => <td key={c}>{row[c] == null ? 'NULL' : String(row[c])}</td>)}</tr>)}</tbody>
                            </table>}
                            <button type="button" style={S.queryDisclosure} aria-expanded={expandedQueries.has(msg.id)}
                                onClick={() => toggle(setExpandedQueries, msg.id)}>
                                <span style={S.chevron}>{expandedQueries.has(msg.id) ? '▾' : '▸'}</span>Executed query
                            </button>
                            {expandedQueries.has(msg.id) && <pre style={S.query}>{msg.sqlResult.query}</pre>}
                        </div>}
                    </section>}
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
    result: { marginTop: 12, borderTop: '1px solid #30363d', paddingTop: 8 },
    disclosure: { border: 0, background: 'transparent', color: 'inherit', cursor: 'pointer', font: 'inherit', fontWeight: 600, padding: 0, display: 'flex', alignItems: 'center', gap: 6 },
    queryDisclosure: { border: 0, background: 'transparent', color: 'inherit', cursor: 'pointer', font: 'inherit', padding: '10px 0 0', display: 'flex', alignItems: 'center', gap: 6 },
    chevron: { width: 12, color: '#8b949e' },
    query: { whiteSpace: 'pre-wrap', background: '#0d1117', padding: 8, borderRadius: 4, fontSize: 12 },
    table: { borderCollapse: 'collapse', fontSize: 12, width: '100%' },
};
export default MessageList;
