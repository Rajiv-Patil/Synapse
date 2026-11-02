import React, { useState, useEffect, useRef } from 'react';
import { useStyletron } from 'baseui';
import { Button, KIND as BUTTON_KIND, SIZE as BUTTON_SIZE, SHAPE as BUTTON_SHAPE } from 'baseui/button';
import { Textarea } from 'baseui/textarea';
import { Tag, KIND as TAG_KIND, VARIANT as TAG_VARIANT } from 'baseui/tag';
import { Spinner } from 'baseui/spinner';
import { Plus, Delete, ArrowRight } from 'baseui/icon';
import { api } from '../services/api';
import { CsvUploader } from './CsvUploader';
import { CsvHealthCard } from './CsvHealthCard';
import ThemeToggle from './ThemeToggle';
import type { Conversation, CsvUploadResponse, Message, UserProfile } from '../types';

interface Props {
    onNavigateHome: () => void;
}

export function ChatPage({ onNavigateHome }: Props) {
    const [css, theme] = useStyletron();

    const [user, setUser] = useState<UserProfile | null>(null);
    const [conversations, setConversations] = useState<Conversation[]>([]);
    const [activeConvId, setActiveConvId] = useState<string | null>(null);
    const [messages, setMessages] = useState<Message[]>([]);
    const [attachedCsv, setAttachedCsv] = useState<CsvUploadResponse | null>(null);

    const [inputPrompt, setInputPrompt] = useState('');
    const [expandedSqlResults, setExpandedSqlResults] = useState<Set<string>>(() => new Set());
    const [expandedSqlQueries, setExpandedSqlQueries] = useState<Set<string>>(() => new Set());
    const [isSending, setIsSending] = useState(false);
    const [isLoadingConvs, setIsLoadingConvs] = useState(true);

    const messagesEndRef = useRef<HTMLDivElement>(null);
    const activeConvIdRef = useRef<string | null>(null);
    const initialLoadStartedRef = useRef(false);
    const creatingConversationRef = useRef(false);
    const selectionRequestRef = useRef(0);

    const selectActiveConversationId = (id: string | null) => {
        activeConvIdRef.current = id;
        setActiveConvId(id);
    };

    useEffect(() => {
        if (initialLoadStartedRef.current) return;
        initialLoadStartedRef.current = true;

        const loadInitialData = async () => {
            try {
                const profile = await api.getAuthStatus();
                setUser(profile);
                if (!profile.authenticated) {
                    window.location.assign('/oauth2/authorization/cognito');
                    return;
                }

                const convList = await api.getConversations();
                setConversations(convList);

                if (convList.length > 0) {
                    selectConversation(convList[0]);
                } else {
                    await startNewConversation();
                }
            } catch (err) {
                console.error('Failed to load initial data:', err);
            } finally {
                setIsLoadingConvs(false);
            }
        };

        loadInitialData();
    }, []);

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, isSending]);

    const selectConversation = async (conv: Conversation) => {
        const requestId = ++selectionRequestRef.current;
        selectActiveConversationId(conv.id);
        setAttachedCsv(null);

        // Fetch full conversation with messages from backend
        let finalConv = conv;
        try {
            const fullConv = await api.getConversation(conv.id);
            if (requestId !== selectionRequestRef.current) return;
            setMessages(fullConv.messages || []);
            // Update local state with full conversation data
            setConversations(prev => prev.map(c =>
                c.id === conv.id ? fullConv : c
            ));
            finalConv = fullConv;
        } catch (err) {
            if (requestId !== selectionRequestRef.current) return;
            console.error('Failed to load conversation:', err);
            setMessages([]);
        }

        if (finalConv.documentId) {
            try {
                const csvDoc = await api.getCsvDocument(finalConv.documentId);
                if (requestId === selectionRequestRef.current) {
                    setAttachedCsv(csvDoc);
                }
            } catch {
                // Ignore
            }
        } else {
            setAttachedCsv(null);
        }
    };

    const startNewConversation = async () => {
        if (creatingConversationRef.current) return;
        creatingConversationRef.current = true;
        try {
            const newConv = await api.createConversation("New Analysis");
            selectionRequestRef.current++;
            selectActiveConversationId(newConv.id);
            setMessages(newConv.messages || []);
            setAttachedCsv(null);
            setConversations(prev => [newConv, ...prev]);
        } catch (e) {
            console.error('Failed to create conversation:', e);
            // Don't create fake local conversations - they cause confusion
            // Show error to user instead
            alert('Failed to create conversation. Please try again.');
        } finally {
            creatingConversationRef.current = false;
        }
    };

    const handleDeleteConversation = async (e: React.MouseEvent, id: string) => {
        e.stopPropagation();
        try {
            const deleted = await api.deleteConversation(id);
            if (!deleted) throw new Error('Conversation was not deleted');
        } catch (err) {
            console.error('Failed to delete conversation:', err);
            alert('Failed to delete conversation. Please try again.');
            return;
        }
        setConversations(prev => prev.filter(c => c.id !== id));
        if (activeConvId === id) {
            const remaining = conversations.filter(c => c.id !== id);
            if (remaining.length > 0) {
                selectConversation(remaining[0]);
            } else {
                startNewConversation();
            }
        }
    };

    const handleCsvUploaded = async (uploaded: CsvUploadResponse, uploadConversationId?: string) => {
        const conversationId = uploadConversationId ?? activeConvIdRef.current;
        if (!conversationId) return;
        const isStillActive = () => activeConvIdRef.current === conversationId;
        if (isStillActive()) setAttachedCsv(uploaded);

        const isHealed = uploaded.healthReport.autoHealed;
        const announcement: Message = {
            id: crypto.randomUUID(),
            role: 'assistant',
            content : `**Dataset Attached:** \`${uploaded.filename}\`\n\n` +
                `- **Status:** ${uploaded.healthReport.status}\n` +
                `- **Dimensions:** ${uploaded.healthReport.healedRowCount - 1} rows, ${uploaded.healthReport.healedColumnCount} columns\n` +
                (isHealed
                    ? `- **Auto-Healing:** ${uploaded.healthReport.issues.length} structural repairs applied`
                    : `- **Integrity:** Clean dataset structure detected.`),
            timestamp: new Date().toISOString(),
        };

        if (conversationId) {
            try {
                const savedAnnouncement = await api.addConversationMessage(conversationId, announcement.content);
                if (isStillActive()) setMessages(prev => [...prev, savedAnnouncement]);
            } catch (err) {
                console.error('Failed to save dataset attachment notice:', err);
                if (isStillActive()) setMessages(prev => [...prev, announcement]);
            }
        }

        // Update the active conversation's document info.
        if (conversationId) {
            setConversations(prev => prev.map(c => {
                if (c.id === conversationId) {
                    return {
                        ...c,
                        documentId: uploaded.documentId,
                        documentName: uploaded.filename,
                        title: uploaded.filename,
                    };
                }
                return c;
            }));
        }
    };

    const handleSendMessage = async (textToSend?: string) => {
        const query = (textToSend !== undefined ? textToSend : inputPrompt).trim();
        if (!query || isSending || isLoadingConvs) return;

        setIsSending(true);

        try {
            let convId = activeConvId;
            if (!convId) {
                const created = await api.createConversation('New Analysis');
                convId = created.id;
                selectionRequestRef.current++;
                selectActiveConversationId(convId);
                setAttachedCsv(null);
                setConversations(prev => [created, ...prev]);
            }
            const sendSelectionId = selectionRequestRef.current;

            const userMsg: Message = {
                id: crypto.randomUUID(),
                role: 'user',
                content: query,
                timestamp: new Date().toISOString(),
            };
            setMessages(prev => [...prev, userMsg]);
            setInputPrompt('');

            const result = await api.sendMessage(query, convId, attachedCsv?.documentId);
            if (sendSelectionId === selectionRequestRef.current) {
                setMessages(prev => [...prev, result.message]);
            }

            // Use the authoritative conversation data from the backend
            // instead of manually appending to stale local state
            setConversations(prev => prev.map(c => {
                if (c.id === result.conversationId) {
                    return {
                        ...c,
                        title: result.conversation.title,
                        messages: result.conversation.messages || [],
                        updatedAt: result.conversation.updatedAt
                    };
                }
                return c;
            }));
        } catch (err) {
            const errorMsg: Message = {
                id: crypto.randomUUID(),
                role: 'assistant',
                content: `Unable to process query: ${err instanceof Error ? err.message : 'Unknown error'}. Please verify connection and retry.`,
                timestamp: new Date().toISOString(),
            };
            setMessages(prev => [...prev, errorMsg]);
        } finally {
            setIsSending(false);
        }
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendMessage();
        }
    };

    const persistentPrompts = [
        "Describe schema and types",
        "Show CSV health and auto-heal details",
        "Which columns have missing or null values?",
        "Show top 5 sample rows",
        "Give me summary statistics",
        "Count total rows",
        "Find records where value > 100",
        "Show me the average of [column]",
    ];

    // Helper for inline bold, code formatting
    const formatInline = (text: string) => {
        const parts = text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
        return parts.map((part, idx) => {
            if (part.startsWith('`') && part.endsWith('`')) {
                return (
                    <code
                        key={idx}
                        className={css({
                            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                            fontSize: '12.5px',
                            backgroundColor: theme.colors.backgroundTertiary,
                            padding: '2px 5px',
                            borderRadius: '4px',
                            border: `1px solid ${theme.colors.borderOpaque}`,
                            color: theme.colors.contentPrimary,
                        })}
                    >
                        {part.slice(1, -1)}
                    </code>
                );
            }
            if (part.startsWith('**') && part.endsWith('**')) {
                return <strong key={idx} style={{ fontWeight: 600 }}>{part.slice(2, -2)}</strong>;
            }
            return part;
        });
    };

    // Rich Markdown & Left-Aligned Layout Renderer
    const renderMessageContent = (content: string) => {
        const lines = content.split('\n');
        const elements: React.ReactNode[] = [];

        let inTable = false;
        let tableHeader: string[] = [];
        let tableRows: string[][] = [];

        let inCode = false;
        let codeLang = '';
        let codeLines: string[] = [];

        const flushTable = (keyPrefix: number) => {
            if (tableHeader.length > 0 || tableRows.length > 0) {
                elements.push(
                    <div
                        key={`tbl-${keyPrefix}`}
                        className={css({
                            overflowX: 'auto',
                            margin: '12px 0',
                            borderRadius: theme.borders.radius300,
                            border: `1px solid ${theme.colors.borderOpaque}`,
                            textAlign: 'left',
                        })}
                    >
                        <table
                            className={css({
                                width: '100%',
                                borderCollapse: 'collapse',
                                fontSize: '13px',
                                textAlign: 'left',
                            })}
                        >
                            {tableHeader.length > 0 && (
                                <thead>
                                <tr className={css({ backgroundColor: theme.colors.backgroundTertiary })}>
                                    {tableHeader.map((h, hi) => (
                                        <th
                                            key={hi}
                                            className={css({
                                                padding: '10px 14px',
                                                borderBottom: `1px solid ${theme.colors.borderOpaque}`,
                                                fontWeight: 600,
                                                color: theme.colors.contentPrimary,
                                                textAlign: 'left',
                                                whiteSpace: 'nowrap',
                                            })}
                                        >
                                            {h.trim()}
                                        </th>
                                    ))}
                                </tr>
                                </thead>
                            )}
                            <tbody>
                            {tableRows.map((row, ri) => (
                                <tr
                                    key={ri}
                                    className={css({
                                        borderBottom: ri < tableRows.length - 1 ? `1px solid ${theme.colors.borderOpaque}` : 'none',
                                        backgroundColor: ri % 2 === 0 ? 'transparent' : theme.colors.backgroundSecondary,
                                    })}
                                >
                                    {row.map((cell, ci) => (
                                        <td
                                            key={ci}
                                            className={css({
                                                padding: '8px 14px',
                                                color: theme.colors.contentSecondary,
                                                textAlign: 'left',
                                            })}
                                        >
                                            {formatInline(cell.trim())}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                );
                tableHeader = [];
                tableRows = [];
            }
        };

        const flushCode = (keyPrefix: number) => {
            if (codeLines.length > 0) {
                elements.push(
                    <div
                        key={`code-${keyPrefix}`}
                        className={css({
                            margin: '12px 0',
                            borderRadius: theme.borders.radius300,
                            overflow: 'hidden',
                            border: `1px solid ${theme.colors.borderOpaque}`,
                            backgroundColor: theme.colors.backgroundTertiary,
                            textAlign: 'left',
                        })}
                    >
                        {codeLang && (
                            <div
                                className={css({
                                    padding: '6px 12px',
                                    fontSize: '11px',
                                    fontFamily: 'ui-monospace, monospace',
                                    fontWeight: 600,
                                    textTransform: 'uppercase',
                                    letterSpacing: '0.06em',
                                    color: theme.colors.contentTertiary,
                                    borderBottom: `1px solid ${theme.colors.borderOpaque}`,
                                    textAlign: 'left',
                                })}
                            >
                                {codeLang}
                            </div>
                        )}
                        <pre
                            className={css({
                                margin: 0,
                                padding: '12px 14px',
                                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                                fontSize: '13px',
                                lineHeight: 1.5,
                                overflowX: 'auto',
                                color: theme.colors.contentPrimary,
                                textAlign: 'left',
                            })}
                        >
                            {codeLines.join('\n')}
                        </pre>
                    </div>
                );
                codeLines = [];
                codeLang = '';
            }
        };

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];

            // Code Fence
            if (line.trim().startsWith('```')) {
                if (inCode) {
                    inCode = false;
                    flushCode(i);
                } else {
                    inCode = true;
                    codeLang = line.trim().replace('```', '');
                }
                continue;
            }

            if (inCode) {
                codeLines.push(line);
                continue;
            }

            // Markdown Table
            if (line.trim().startsWith('|') && line.trim().endsWith('|')) {
                const cells = line.trim().slice(1, -1).split('|');
                if (cells.some(c => c.trim().match(/^-+$/))) {
                    continue;
                }
                if (!inTable) {
                    inTable = true;
                    tableHeader = cells;
                } else {
                    tableRows.push(cells);
                }
                continue;
            } else if (inTable) {
                inTable = false;
                flushTable(i);
            }

            // Headings
            if (line.startsWith('### ')) {
                elements.push(
                    <h3
                        key={i}
                        className={css({
                            ...theme.typography.HeadingSmall,
                            margin: '16px 0 6px',
                            color: theme.colors.contentPrimary,
                            fontWeight: 600,
                            textAlign: 'left',
                        })}
                    >
                        {line.replace('### ', '')}
                    </h3>
                );
            } else if (line.startsWith('#### ')) {
                elements.push(
                    <h4
                        key={i}
                        className={css({
                            ...theme.typography.LabelMedium,
                            margin: '12px 0 4px',
                            color: theme.colors.contentPrimary,
                            fontWeight: 600,
                            textAlign: 'left',
                        })}
                    >
                        {line.replace('#### ', '')}
                    </h4>
                );
            } else if (line.startsWith('- ') || line.startsWith('* ')) {
                elements.push(
                    <li
                        key={i}
                        className={css({
                            margin: '4px 0 4px 20px',
                            color: theme.colors.contentSecondary,
                            fontSize: '14px',
                            lineHeight: 1.6,
                            textAlign: 'left',
                        })}
                    >
                        {formatInline(line.substring(2))}
                    </li>
                );
            } else if (line.startsWith('> ')) {
                elements.push(
                    <blockquote
                        key={i}
                        className={css({
                            margin: '10px 0',
                            padding: '8px 14px',
                            borderLeft: `3px solid ${theme.colors.contentPrimary}`,
                            backgroundColor: theme.colors.backgroundSecondary,
                            color: theme.colors.contentSecondary,
                            fontSize: '13px',
                            borderRadius: `0 ${theme.borders.radius200} ${theme.borders.radius200} 0`,
                            textAlign: 'left',
                        })}
                    >
                        {formatInline(line.substring(2))}
                    </blockquote>
                );
            } else if (line.trim() === '') {
                elements.push(<div key={i} style={{ height: '6px' }} />);
            } else {
                elements.push(
                    <p
                        key={i}
                        className={css({
                            margin: '4px 0',
                            lineHeight: 1.65,
                            fontSize: '14px',
                            color: theme.colors.contentPrimary,
                            textAlign: 'left',
                        })}
                    >
                        {formatInline(line)}
                    </p>
                );
            }
        }

        if (inTable) flushTable(lines.length);
        if (inCode) flushCode(lines.length);

        return elements;
    };

    return (
        <div
            className={css({
                display: 'flex',
                height: '100vh',
                width: '100vw',
                overflow: 'hidden',
                backgroundColor: theme.colors.backgroundPrimary,
                fontFamily: theme.typography.font100.fontFamily,
                textAlign: 'left', // Overrides #root text-align: center
            })}
        >
            {/* 1. LEFT SIDEBAR */}
            <aside
                className={css({
                    width: '20%',
                    minWidth: '280px',
                    maxWidth: '360px',
                    height: '100%',
                    backgroundColor: theme.colors.backgroundSecondary,
                    borderRight: `1px solid ${theme.colors.borderOpaque}`,
                    display: 'flex',
                    flexDirection: 'column',
                    boxSizing: 'border-box',
                    flexShrink: 0,
                    textAlign: 'left',
                })}
            >
                <div
                    className={css({
                        padding: `${theme.sizing.scale600} ${theme.sizing.scale600} ${theme.sizing.scale400}`,
                        borderBottom: `1px solid ${theme.colors.borderOpaque}`,
                    })}
                >
                    <div className={css({ display: 'flex', justifyContent: 'left', alignItems: 'center' })}>
                        <div
                            onClick={onNavigateHome}
                            className={css({
                                ...theme.typography.HeadingMedium,
                                fontFamily: 'BrandFont, sans-serif',
                                color: theme.colors.contentPrimary,
                                cursor: 'pointer',
                                fontWeight: 700,
                                letterSpacing: '-0.5px',
                            })}
                        >
                            Synapse
                        </div>
                    </div>
                </div>

                {/* Conversation Sessions List */}
                <div
                    className={css({
                        flex: 1,
                        overflowY: 'auto',
                        padding: `${theme.sizing.scale400} ${theme.sizing.scale400}`,
                        textAlign: 'left',
                    })}
                >
                    <div
                        style={{
                            display: 'flex',
                            alignItems: "center",
                            justifyContent: 'space-between',
                            marginBottom: '12px',
                            padding: '0 4px',
                        }}
                    >
                        <ThemeToggle />
                        <Button
                            size={BUTTON_SIZE.compact}
                            kind={BUTTON_KIND.secondary}
                            shape={BUTTON_SHAPE.pill}
                            onClick={startNewConversation}
                            startEnhancer={<Plus size={18} />}
                            overrides={{
                                BaseButton: {
                                    style: {
                                        width: '75%',
                                        backgroundColor: theme.colors.backgroundPrimary,
                                        color: theme.colors.contentPrimary,
                                        fontWeight: 500,
                                        fontSize: '12.5px',
                                        transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        ':hover': {
                                            backgroundColor: theme.colors.contentPrimary,
                                            color: theme.colors.backgroundPrimary,
                                        }
                                    }
                                }
                            }}
                        >
                            New Chat
                        </Button>
                    </div>

                    {isLoadingConvs ? (
                        <div className={css({ display: 'flex', justifyContent: 'center', padding: theme.sizing.scale600 })}>
                            <Spinner size={24} />
                        </div>
                    ) : conversations.length === 0 ? (
                        <div className={css({ padding: `${theme.sizing.scale400} ${theme.sizing.scale300}`, color: theme.colors.contentTertiary, fontSize: '13px' })}>
                            No prior conversations
                        </div>
                    ) : (
                        conversations.map(conv => {
                            const isActive = conv.id === activeConvId;
                            return (
                                <div
                                    key={conv.id}
                                    onClick={() => selectConversation(conv)}
                                    className={css({
                                        display: 'flex',
                                        flexDirection: 'row',
                                        alignItems: 'center',
                                        justifyContent: 'space-between',
                                        padding: '8px 10px',
                                        borderRadius: theme.borders.radius400,
                                        margin: '4px 0',
                                        cursor: 'pointer',
                                        backgroundColor: isActive
                                            ? theme.colors.contentPrimary
                                            : 'transparent',
                                        color: isActive
                                            ? theme.colors.backgroundPrimary
                                            : theme.colors.contentPrimary,
                                        boxShadow: isActive ? theme.lighting.shadow400 : 'none',
                                        transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        textAlign: 'left',
                                        ':hover': {
                                            backgroundColor: isActive
                                                ? theme.colors.contentPrimary
                                                : theme.colors.backgroundTertiary,
                                        },
                                    })}
                                >
                                    <div className={css({ minWidth: 0, flex: 1, marginRight: '8px', textAlign: 'left' })}>
                                        <div
                                            className={css({
                                                fontSize: '13px',
                                                fontWeight: isActive ? 600 : 500,
                                                color: isActive ? theme.colors.backgroundPrimary : theme.colors.contentPrimary,
                                                whiteSpace: 'nowrap',
                                                overflow: 'hidden',
                                                textOverflow: 'ellipsis',
                                                textAlign: 'left',
                                            })}
                                        >
                                            {conv.title || 'Untitled Session'}
                                        </div>
                                        {conv.documentName && (
                                            <div
                                                className={css({
                                                    fontSize: '11px',
                                                    color: isActive ? theme.colors.backgroundTertiary : theme.colors.contentTertiary,
                                                    marginTop: '2px',
                                                    whiteSpace: 'nowrap',
                                                    overflow: 'hidden',
                                                    textOverflow: 'ellipsis',
                                                    textAlign: 'left',
                                                })}
                                            >
                                                {conv.documentName}
                                            </div>
                                        )}
                                    </div>

                                    <button
                                        onClick={(e) => handleDeleteConversation(e, conv.id)}
                                        aria-label="Delete session"
                                        className={css({
                                            background: 'transparent',
                                            border: 'none',
                                            color: isActive ? theme.colors.backgroundPrimary : theme.colors.contentTertiary,
                                            cursor: 'pointer',
                                            padding: '4px',
                                            borderRadius: theme.borders.radius200,
                                            display: 'flex',
                                            alignItems: 'center',
                                            opacity: isActive ? 0.85 : 0.5,
                                            ':hover': {
                                                opacity: 1,
                                                backgroundColor: isActive ? 'rgba(255, 255, 255, 0.2)' : theme.colors.backgroundSecondary,
                                            },
                                        })}
                                        title="Delete Session"
                                    >
                                        <Delete size={14} />
                                    </button>
                                </div>
                            );
                        })
                    )}
                </div>

                {/* User Profile */}
                <div
                    className={css({
                        padding: `${theme.sizing.scale400} ${theme.sizing.scale500}`,
                        borderTop: `1px solid ${theme.colors.borderOpaque}`,
                        backgroundColor: theme.colors.backgroundSecondary,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        textAlign: 'left',
                    })}
                >
                    <div className={css({ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 })}>
                        <div
                            className={css({
                                width: '30px',
                                height: '30px',
                                borderRadius: '50%',
                                backgroundColor: theme.colors.contentPrimary,
                                color: theme.colors.backgroundPrimary,
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                fontWeight: 700,
                                fontSize: '12px',
                                flexShrink: 0,
                            })}
                        >
                            {user?.name ? user.name.charAt(0).toUpperCase() : 'U'}
                        </div>
                        <div className={css({ minWidth: 0, textAlign: 'left' })}>
                            <div className={css({ fontSize: '12.5px', fontWeight: 600, color: theme.colors.contentPrimary, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', textAlign: 'left' })}>
                                {user?.name || (user?.authenticated ? 'Authenticated User' : 'Guest Mode')}
                            </div>
                            <div className={css({ fontSize: '11px', color: theme.colors.contentTertiary, textAlign: 'left' })}>
                                {user?.roles?.includes('ROLE_ADMIN') ? 'Administrator' : (user?.authenticated ? 'Standard User' : 'Not signed in')}
                            </div>
                        </div>
                    </div>
                </div>
            </aside>

            {/* 2. MAIN CHAT AREA */}
            <main
                className={css({
                    width: '80%',
                    flex: 1,
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    backgroundColor: theme.colors.backgroundPrimary,
                    boxSizing: 'border-box',
                    textAlign: 'left',
                })}
            >
                {/* Header */}
                <header
                    className={css({
                        height: '60px',
                        borderBottom: `1px solid ${theme.colors.borderOpaque}`,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: `0 ${theme.sizing.scale700}`,
                        backgroundColor: theme.colors.backgroundPrimary,
                        flexShrink: 0,
                        textAlign: 'left',
                    })}
                >
                    <div className={css({ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 })}>
                        <Tag
                            closeable={false}
                            variant={TAG_VARIANT.solid}
                            kind={TAG_KIND.neutral}
                            overrides={{
                                Root: {
                                    style: {
                                        backgroundColor: theme.colors.contentPrimary,
                                        color: theme.colors.backgroundPrimary,
                                        fontWeight: 600,
                                        fontSize: '12px',
                                        borderRadius: theme.borders.radius400,
                                        padding: '4px 10px',
                                        margin: 0,
                                    }
                                },
                                Text: { style: { color: theme.colors.backgroundPrimary } }
                            }}
                        >
                            {conversations.find(c => c.id === activeConvId)?.title || 'Dataset Analysis'}
                        </Tag>

                        {attachedCsv && (
                            <Tag
                                closeable={false}
                                variant={TAG_VARIANT.outlined}
                                kind={TAG_KIND.neutral}
                                overrides={{
                                    Root: {
                                        style: {
                                            fontSize: '11px',
                                            margin: 0,
                                            backgroundColor: theme.colors.backgroundSecondary,
                                            color: theme.colors.contentSecondary,
                                            border: `1px solid ${theme.colors.borderOpaque}`,
                                            borderRadius: theme.borders.radius300,
                                        }
                                    }
                                }}
                            >
                                {attachedCsv.filename} ({attachedCsv.healthReport.healedRowCount - 1} rows)
                            </Tag>
                        )}
                    </div>

                    <Button
                        size={BUTTON_SIZE.compact}
                        kind={BUTTON_KIND.tertiary}
                        shape={BUTTON_SHAPE.pill}
                        onClick={onNavigateHome}
                        overrides={{
                            BaseButton: {
                                style: {
                                    fontSize: '12.5px',
                                    color: theme.colors.contentSecondary,
                                    ':hover': {
                                        color: theme.colors.contentPrimary,
                                        backgroundColor: theme.colors.backgroundTertiary,
                                    },
                                }
                            }
                        }}
                    >
                        Back to Home
                    </Button>
                </header>

                {/* Conversation Stream */}
                <div
                    className={css({
                        flex: 1,
                        overflowY: 'auto',
                        padding: `${theme.sizing.scale700} ${theme.sizing.scale700}`,
                        boxSizing: 'border-box',
                        textAlign: 'left',
                    })}
                >
                    <div
                        className={css({
                            maxWidth: '840px',
                            margin: '0 auto',
                            width: '100%',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '20px',
                            textAlign: 'left',
                        })}
                    >
                        {attachedCsv && (
                            <CsvHealthCard
                                csvData={attachedCsv}
                                onClear={() => setAttachedCsv(null)}
                            />
                        )}

                        {messages.length === 0 ? (
                            <div
                                className={css({
                                    display: 'flex',
                                    flexDirection: 'column',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    textAlign: 'center',
                                    padding: '48px 0 24px',
                                    gap: '16px',
                                })}
                            >
                                <h1
                                    className={css({
                                        ...theme.typography.HeadingLarge,
                                        margin: 0,
                                        color: theme.colors.contentPrimary,
                                        fontWeight: 700,
                                        letterSpacing: '-0.8px',
                                    })}
                                >
                                    Synapse Studio
                                </h1>
                                <div className={css({ width: '30%', maxWidth: '540px', marginTop: '12px' })}>
                                    <CsvUploader onUploaded={handleCsvUploaded} conversationId={activeConvId || undefined} />
                                </div>
                            </div>
                        ) : (
                            messages.map(msg => {
                                const isUser = msg.role === 'user';
                                return (
                                    <div
                                        key={msg.id}
                                        className={css({
                                            display: 'flex',
                                            flexDirection: isUser ? 'row-reverse' : 'row',
                                            alignItems: 'flex-start',
                                            gap: '12px',
                                            width: '100%',
                                            textAlign: 'left',
                                        })}
                                    >
                                        {/* Assistant Avatar Badge */}
                                        {!isUser && (
                                            <div
                                                className={css({
                                                    width: '28px',
                                                    height: '28px',
                                                    borderRadius: '8px',
                                                    backgroundColor: theme.colors.backgroundTertiary,
                                                    border: `1px solid ${theme.colors.borderOpaque}`,
                                                    display: 'flex',
                                                    alignItems: 'center',
                                                    justifyContent: 'center',
                                                    fontSize: '12px',
                                                    fontWeight: 700,
                                                    color: theme.colors.contentPrimary,
                                                    flexShrink: 0,
                                                    marginTop: '4px',
                                                })}
                                                title="Synapse Assistant"
                                            >
                                                ✦
                                            </div>
                                        )}

                                        {/* Message Bubble / Card */}
                                        <div
                                            className={css({
                                                maxWidth: isUser ? '75%' : '92%',
                                                padding: isUser ? '10px 16px' : '16px 20px',
                                                borderRadius: isUser ? '18px 18px 4px 18px' : theme.borders.radius400,
                                                backgroundColor: isUser
                                                    ? theme.colors.contentPrimary
                                                    : theme.colors.backgroundSecondary,
                                                border: isUser
                                                    ? 'none'
                                                    : `1px solid ${theme.colors.borderOpaque}`,
                                                color: isUser
                                                    ? theme.colors.backgroundPrimary
                                                    : theme.colors.contentPrimary,
                                                lineHeight: 1.6,
                                                boxShadow: isUser ? theme.lighting.shadow400 : 'none',
                                                textAlign: 'left',
                                            })}
                                        >
                                            {isUser ? (
                                                <div className={css({ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: '14px', textAlign: 'left' })}>
                                                    {msg.content}
                                                </div>
                                            ) : (
                                                <div className={css({ width: '100%', textAlign: 'left' })}>
                                                    {renderMessageContent(msg.content)}
                                                    {msg.sqlResult && (() => {
                                                        const resultOpen = expandedSqlResults.has(msg.id);
                                                        const queryOpen = expandedSqlQueries.has(msg.id);
                                                        const toggleSet = (setter: React.Dispatch<React.SetStateAction<Set<string>>>) => setter(previous => {
                                                            const next = new Set(previous);
                                                            if (next.has(msg.id)) next.delete(msg.id);
                                                            else next.add(msg.id);
                                                            return next;
                                                        });
                                                        return <div className={css({ marginTop: '14px', borderTop: `1px solid ${theme.colors.borderOpaque}`, paddingTop: '10px' })}>
                                                            <button type="button" aria-expanded={resultOpen} onClick={() => toggleSet(setExpandedSqlResults)}
                                                                className={css({ display: 'flex', alignItems: 'center', gap: '8px', border: 0, padding: '4px 0', background: 'transparent', color: theme.colors.contentPrimary, font: 'inherit', fontWeight: 600, cursor: 'pointer' })}>
                                                                <span aria-hidden="true">{resultOpen ? '▾' : '▸'}</span>Results ({msg.sqlResult.count} rows)
                                                            </button>
                                                            {resultOpen && <div>
                                                                <div className={css({ overflowX: 'auto', marginTop: '8px', borderRadius: theme.borders.radius300, border: `1px solid ${theme.colors.borderOpaque}` })}>
                                                                    {msg.sqlResult.columns.length === 0 ? <div className={css({ padding: '12px', color: theme.colors.contentSecondary })}>No results found.</div> : <table className={css({ width: '100%', borderCollapse: 'collapse', fontSize: '13px', textAlign: 'left' })}>
                                                                        <thead><tr className={css({ backgroundColor: theme.colors.backgroundTertiary })}>{msg.sqlResult.columns.map(column => <th key={column} className={css({ padding: '10px 14px', borderBottom: `1px solid ${theme.colors.borderOpaque}`, whiteSpace: 'nowrap' })}>{column}</th>)}</tr></thead>
                                                                        <tbody>{msg.sqlResult.rows.map((row, rowIndex) => <tr key={rowIndex} className={css({ borderBottom: `1px solid ${theme.colors.borderOpaque}` })}>{msg.sqlResult!.columns.map(column => <td key={column} className={css({ padding: '8px 14px', color: theme.colors.contentSecondary, whiteSpace: 'nowrap' })}>{row[column] == null ? 'null' : String(row[column])}</td>)}</tr>)}</tbody>
                                                                    </table>}
                                                                </div>
                                                                <button type="button" aria-expanded={queryOpen} onClick={() => toggleSet(setExpandedSqlQueries)}
                                                                    className={css({ display: 'flex', alignItems: 'center', gap: '8px', border: 0, padding: '10px 0 4px', background: 'transparent', color: theme.colors.contentSecondary, font: 'inherit', cursor: 'pointer' })}>
                                                                    <span aria-hidden="true">{queryOpen ? '▾' : '▸'}</span>Executed query
                                                                </button>
                                                                {queryOpen && <pre className={css({ whiteSpace: 'pre-wrap', overflowX: 'auto', padding: '10px', borderRadius: theme.borders.radius200, backgroundColor: theme.colors.backgroundTertiary, fontSize: '12px' })}>{msg.sqlResult.query}</pre>}
                                                            </div>}
                                                        </div>;
                                                    })()}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                );
                            })
                        )}

                        {isSending && (
                            <div className={css({ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 0', textAlign: 'left' })}>
                                <Spinner size={16} />
                                <span className={css({ fontSize: '13px', color: theme.colors.contentSecondary, textAlign: 'left' })}>
                                    Generating query and computing results...
                                </span>
                            </div>
                        )}

                        <div ref={messagesEndRef} />
                    </div>
                </div>

                {/* Persistent Inquiries & Input */}
                <div
                    className={css({
                        padding: `0 ${theme.sizing.scale700} ${theme.sizing.scale600}`,
                        backgroundColor: theme.colors.backgroundPrimary,
                        flexShrink: 0,
                        textAlign: 'left',
                    })}
                >
                    <div
                        className={css({
                            maxWidth: '840px',
                            margin: '0 auto',
                            width: '100%',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '10px',
                            textAlign: 'left',
                        })}
                    >
                        {/* Suggested Prompts */}
                        <div
                            className={css({
                                display: 'flex',
                                alignItems: 'center',
                                gap: '8px',
                                overflowX: 'auto',
                                paddingBottom: '4px',
                                scrollbarWidth: 'none',
                                '::-webkit-scrollbar': { display: 'none' },
                                textAlign: 'left',
                            })}
                        >
                            {persistentPrompts.map((promptText, idx) => (
                                <Button
                                    key={idx}
                                    size={BUTTON_SIZE.mini}
                                    kind={BUTTON_KIND.secondary}
                                    shape={BUTTON_SHAPE.pill}
                                    onClick={() => handleSendMessage(promptText)}
                                    overrides={{
                                        BaseButton: {
                                            style: {
                                                whiteSpace: 'nowrap',
                                                flexShrink: 0,
                                                fontSize: '12px',
                                                paddingTop: '6px',
                                                paddingBottom: '6px',
                                                paddingLeft: '12px',
                                                paddingRight: '12px',
                                                border: `1px solid ${theme.colors.borderOpaque}`,
                                                backgroundColor: theme.colors.backgroundSecondary,
                                                color: theme.colors.contentSecondary,
                                                ':hover': {
                                                    backgroundColor: theme.colors.contentPrimary,
                                                    color: theme.colors.backgroundPrimary,
                                                    borderColor: theme.colors.contentPrimary,
                                                    transform: 'translateY(-1px)',
                                                }
                                            }
                                        }
                                    }}
                                >
                                    {promptText}
                                </Button>
                            ))}
                        </div>

                        {/* Input Capsule */}
                        <div
                            className={css({
                                display: 'flex',
                                alignItems: 'flex-end',
                                gap: '8px',
                                backgroundColor: theme.colors.backgroundSecondary,
                                border: `1px solid ${theme.colors.borderOpaque}`,
                                borderRadius: '24px',
                                padding: '8px 12px',
                                boxShadow: theme.lighting.shadow400,
                                textAlign: 'left',
                                ':focus-within': {
                                    borderColor: theme.colors.contentPrimary,
                                    boxShadow: `0 0 0 1px ${theme.colors.contentPrimary}`,
                                },
                            })}
                        >
                            <CsvUploader
                                compact
                                onUploaded={handleCsvUploaded}
                                conversationId={activeConvId || undefined}
                            />

                            <div className={css({ flex: 1, minHeight: '36px', display: 'flex', alignItems: 'center', textAlign: 'left' })}>
                                <Textarea
                                    value={inputPrompt}
                                    onChange={e => setInputPrompt((e.target as HTMLTextAreaElement).value)}
                                    onKeyDown={handleKeyDown}
                                    placeholder={attachedCsv ? "Inquire about schema, data types, nulls, or sample rows..." : "Attach a CSV or type an instruction..."}
                                    rows={1}
                                    overrides={{
                                        InputContainer: {
                                            style: {
                                                backgroundColor: 'transparent',
                                                borderWidth: 0,
                                                textAlign: 'left',
                                            }
                                        },
                                        Input: {
                                            style: {
                                                backgroundColor: 'transparent',
                                                fontSize: '14px',
                                                resize: 'none',
                                                padding: '4px 6px',
                                                color: theme.colors.contentPrimary,
                                                textAlign: 'left',
                                            }
                                        }
                                    }}
                                />
                            </div>

                            <Button
                                size={BUTTON_SIZE.compact}
                                kind={BUTTON_KIND.primary}
                                shape={BUTTON_SHAPE.circle}
                                disabled={!inputPrompt.trim() || isSending}
                                onClick={() => handleSendMessage()}
                                startEnhancer={<ArrowRight size={16} />}
                                overrides={{
                                    BaseButton: {
                                        style: {
                                            width: '34px',
                                            height: '34px',
                                            padding: 0,
                                            display: 'flex',
                                            alignItems: 'center',
                                            justifyContent: 'center',
                                        }
                                    }
                                }}
                            />
                        </div>

                        <div className={css({ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 8px', fontSize: '11px', color: theme.colors.contentTertiary, textAlign: 'left' })}>
                            <span>Press <kbd className={css({ padding: '1px 4px', border: `1px solid ${theme.colors.borderOpaque}`, borderRadius: '3px' })}>Enter</kbd> to send, <kbd className={css({ padding: '1px 4px', border: `1px solid ${theme.colors.borderOpaque}`, borderRadius: '3px' })}>Shift + Enter</kbd> for newline</span>
                            {attachedCsv && <span>Attached: {attachedCsv.filename}</span>}
                        </div>
                    </div>
                </div>
            </main>
        </div>
    );
}

export default ChatPage;
