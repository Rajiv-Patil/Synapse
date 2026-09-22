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
    const [isSending, setIsSending] = useState(false);
    const [isLoadingConvs, setIsLoadingConvs] = useState(true);

    const messagesEndRef = useRef<HTMLDivElement>(null);

    // Load initial user and conversations
    useEffect(() => {
        const loadInitialData = async () => {
            try {
                const profile = await api.getAuthStatus();
                setUser(profile);

                const convList = await api.getConversations();
                setConversations(convList);

                if (convList.length > 0) {
                    selectConversation(convList[0]);
                } else {
                    startNewConversation();
                }
            } catch (err) {
                console.error('Failed to load initial data:', err);
            } finally {
                setIsLoadingConvs(false);
            }
        };

        loadInitialData();
    }, []);

    // Auto-scroll to bottom on message
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, isSending]);

    const selectConversation = async (conv: Conversation) => {
        setActiveConvId(conv.id);
        setMessages(conv.messages || []);

        if (conv.documentId) {
            try {
                const res = await fetch(`/api/csv/${conv.documentId}`);
                if (res.ok) {
                    const csvDoc: CsvUploadResponse = await res.json();
                    setAttachedCsv(csvDoc);
                }
            } catch {
                // Keep attached CSV as is
            }
        } else {
            setAttachedCsv(null);
        }
    };

    const startNewConversation = async () => {
        try {
            // Assign unique conversation ID
            const uniqueId = crypto.randomUUID();
            const newConv: Conversation = {
                id: uniqueId,
                title: "New Analysis",
                messages: [],
                documentId: null,
                documentName: null,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
            };

            setConversations(prev => [newConv, ...prev]);
            setActiveConvId(uniqueId);
            setMessages([]);
            setAttachedCsv(null);

            // Create on backend
            api.createConversation("New Analysis").catch(() => {});
        } catch (e) {
            console.error('Failed to create conversation', e);
        }
    };

    const handleDeleteConversation = async (e: React.MouseEvent, id: string) => {
        e.stopPropagation();
        await api.deleteConversation(id);
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

    const handleCsvUploaded = (uploaded: CsvUploadResponse) => {
        setAttachedCsv(uploaded);

        // Update active conversation's document info
        if (activeConvId) {
            setConversations(prev => prev.map(c => {
                if (c.id === activeConvId) {
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

        // Add a clean announcement into chat
        const isHealed = uploaded.healthReport.autoHealed;
        const announcement: Message = {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: `${uploaded.filename}\n\n` +
                `Status: ${uploaded.healthReport.status} ` +
                (isHealed ? `Auto-healed: ${uploaded.healthReport.issues.length} repairs applied` : ``),
            timestamp: new Date().toISOString(),
        };

        setMessages(prev => [...prev, announcement]);
    };

    const handleSendMessage = async (textToSend?: string) => {
        const query = (textToSend !== undefined ? textToSend : inputPrompt).trim();
        if (!query || isSending) return;

        const convId = activeConvId || crypto.randomUUID();
        if (!activeConvId) {
            setActiveConvId(convId);
        }

        const userMsg: Message = {
            id: crypto.randomUUID(),
            role: 'user',
            content: query,
            timestamp: new Date().toISOString(),
        };

        setMessages(prev => [...prev, userMsg]);
        setInputPrompt('');
        setIsSending(true);

        try {
            const result = await api.sendMessage(query, convId, attachedCsv?.documentId);
            setMessages(prev => [...prev, result.message]);

            // Update conversation list title on first interaction
            if (messages.length <= 1) {
                const titleSnippet = query.length > 28 ? query.substring(0, 28) + '...' : query;
                setConversations(prev => prev.map(c => c.id === convId ? { ...c, title: titleSnippet } : c));
            }
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

    // Persistent recommended inquiries docked right above text input
    const persistentPrompts = [
        "Describe schema and types",
        "Show CSV health and auto-heal details",
        "Which columns have missing or null values?",
        "Show top 5 sample rows",
        "Give me summary statistics",
    ];

    // Helper to render markdown tables and formatting with Base Web styling
    const renderMessageContent = (content: string) => {
        const lines = content.split('\n');
        const elements: React.ReactNode[] = [];
        let inTable = false;
        let tableHeader: string[] = [];
        let tableRows: string[][] = [];

        const flushTable = (keyPrefix: number) => {
            if (tableHeader.length > 0 || tableRows.length > 0) {
                elements.push(
                    <div
                        key={`tbl-${keyPrefix}`}
                        className={css({
                            overflowX: 'auto',
                            margin: '14px 0',
                            borderRadius: theme.borders.radius300,
                            border: `1px solid ${theme.colors.borderOpaque}`,
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
                                                })}
                                            >
                                                {cell.trim()}
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

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];

            // Table detection
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

            if (line.startsWith('### ')) {
                elements.push(
                    <h3 key={i} className={css({ ...theme.typography.HeadingSmall, margin: '18px 0 8px', color: theme.colors.contentPrimary, fontWeight: 600 })}>
                        {line.replace('### ', '')}
                    </h3>
                );
            } else if (line.startsWith('#### ')) {
                elements.push(
                    <h4 key={i} className={css({ ...theme.typography.LabelMedium, margin: '14px 0 6px', color: theme.colors.contentPrimary, fontWeight: 600 })}>
                        {line.replace('#### ', '')}
                    </h4>
                );
            } else if (line.startsWith('- ')) {
                elements.push(
                    <li key={i} className={css({ margin: '4px 0 4px 20px', color: theme.colors.contentSecondary, fontSize: '14px', lineHeight: 1.6 })}>
                        {line.substring(2)}
                    </li>
                );
            } else if (line.startsWith('> ')) {
                elements.push(
                    <blockquote
                        key={i}
                        className={css({
                            margin: '12px 0',
                            padding: '8px 14px',
                            borderLeft: `2px solid ${theme.colors.contentPrimary}`,
                            backgroundColor: theme.colors.backgroundSecondary,
                            color: theme.colors.contentSecondary,
                            fontSize: '13px',
                            borderRadius: `0 ${theme.borders.radius200} ${theme.borders.radius200} 0`,
                        })}
                    >
                        {line.substring(2)}
                    </blockquote>
                );
            } else if (line.trim() === '') {
                elements.push(<div key={i} style={{ height: '8px' }} />);
            } else {
                elements.push(
                    <p key={i} className={css({ margin: '6px 0', lineHeight: 1.65, fontSize: '14px', color: theme.colors.contentPrimary })}>
                        {line}
                    </p>
                );
            }
        }

        if (inTable) {
            flushTable(lines.length);
        }

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
            })}
        >
            {/* 1. LEFT SIDEBAR (25% Width - Contrasted Palette for Active & Hover Tabs) */}
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
                })}
            >
                {/* Brand & Action Header */}
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

                {/* Conversation History List - Contrasted Palette on Active & Hover States */}
                <div
                    className={css({
                        flex: 1,
                        overflowY: 'auto',
                        padding: `${theme.sizing.scale400} ${theme.sizing.scale400}`,
                    })}
                >
                    <div
                        style={{
                            display: 'flex',
                            alignItems: "center",
                            alignContent: 'center',
                            justifyContent: 'space-evenly',
                            marginBottom: '10px',
                        }}
                    >
                        <ThemeToggle />
                        <Button
                            size={BUTTON_SIZE.compact}
                            kind={BUTTON_KIND.secondary}
                            shape={BUTTON_SHAPE.pill}
                            onClick={startNewConversation}
                            startEnhancer={<Plus size={24} />}
                            overrides={{
                                BaseButton: {
                                    style: {
                                        width: '70%',
                                        //border: `1px solid ${theme.colors.borderOpaque}`,
                                        backgroundColor: theme.colors.backgroundPrimary,
                                        color: theme.colors.contentPrimary,
                                        fontWeight: 200,
                                        //marginBottom: '10px',
                                        transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        ':hover': {
                                            backgroundColor: theme.colors.contentPrimary,
                                            color: theme.colors.backgroundPrimary,
                                            borderColor: theme.colors.contentPrimary,
                                        }
                                    }
                                }
                            }}
                        >
                        </Button>
                    </div>

                    {/*<div className={css({ ...theme.typography.LabelXSmall, color: theme.colors.contentTertiary, textTransform: 'uppercase', letterSpacing: '0.06em', padding: `0 ${theme.sizing.scale300}`, marginBottom: theme.sizing.scale300 })}>
                        Recent History
                    </div>*/}

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
                                        padding: '5px 7px',
                                        borderRadius: theme.borders.radius400,
                                        margin: '6px 6px 6px 6px',

                                        cursor: 'pointer',
                                        // High-contrast inverted palette on active state
                                        backgroundColor: isActive
                                            ? theme.colors.contentPrimary
                                            : 'transparent',
                                        color: isActive
                                            ? theme.colors.backgroundPrimary
                                            : theme.colors.contentPrimary,
                                        boxShadow: isActive ? theme.lighting.shadow400 : 'none',
                                        transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        // High-contrast fill on hover state
                                        ':hover': {
                                            backgroundColor: isActive
                                                ? theme.colors.contentPrimary
                                                : theme.colors.backgroundTertiary,
                                            transform: 'translateX(2px)',
                                        },
                                    })}
                                >
                                    <div className={css({ minWidth: 0, flex: 1, marginRight: '8px' })}>
                                        <div
                                            className={css({
                                                fontSize: '13px',
                                                fontWeight: isActive ? 600 : 500,
                                                color: isActive ? theme.colors.backgroundPrimary : theme.colors.contentPrimary,
                                                whiteSpace: 'nowrap',
                                                overflow: 'hidden',
                                                textOverflow: 'ellipsis',
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
                                            transition: `all ${theme.animation.timing100} ease`,
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

                {/* User Profile Footer */}
                <div
                    className={css({
                        padding: `${theme.sizing.scale400} ${theme.sizing.scale500}`,
                        borderTop: `1px solid ${theme.colors.borderOpaque}`,
                        backgroundColor: theme.colors.backgroundSecondary,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
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
                        <div className={css({ minWidth: 0 })}>
                            <div className={css({ fontSize: '12.5px', fontWeight: 600, color: theme.colors.contentPrimary, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' })}>
                                {user?.name || (user?.authenticated ? 'Authenticated User' : 'Guest Mode')}
                            </div>
                            <div className={css({ fontSize: '11px', color: theme.colors.contentTertiary })}>
                                {user?.roles?.includes('ROLE_ADMIN') ? 'Administrator' : (user?.authenticated ? 'Standard User' : 'Not signed in')}
                            </div>
                        </div>
                    </div>

                    {!user?.authenticated && (
                        <Button
                            size={BUTTON_SIZE.mini}
                            kind={BUTTON_KIND.secondary}
                            shape={BUTTON_SHAPE.pill}
                            onClick={() => { window.location.href = '/oauth2/authorization/cognito'; }}
                            overrides={{
                                BaseButton: {
                                    style: {
                                        fontSize: '11px',
                                        transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        ':hover': {
                                            backgroundColor: theme.colors.contentPrimary,
                                            color: theme.colors.backgroundPrimary,
                                        }
                                    }
                                }
                            }}
                        >
                            Sign In
                        </Button>
                    )}
                </div>
            </aside>

            {/* 2. MAIN CHAT WINDOW (75% Width - Contrasted Tab Bar & Claude Editorial Style) */}
            <main
                className={css({
                    width: '80%',
                    flex: 1,
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    backgroundColor: theme.colors.backgroundPrimary,
                    boxSizing: 'border-box',
                })}
            >
                {/* Top Contrasted Conversation Tab Bar */}
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
                    })}
                >
                    {/* Active Conversation Tab Capsule with Contrasted Palette */}
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
                                Text: {
                                    style: {
                                        color: theme.colors.backgroundPrimary,
                                    }
                                }
                            }}
                        >
                            {conversations.find(c => c.id === activeConvId)?.title || 'Dataset Analysis'}
                        </Tag>

                        {attachedCsv ? (
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
                        ) : null}
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
                                    transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
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

                {/* Claude-Style Editorial Conversation Stream */}
                <div
                    className={css({
                        flex: 1,
                        overflowY: 'auto',
                        padding: `${theme.sizing.scale700} ${theme.sizing.scale700}`,
                        boxSizing: 'border-box',
                    })}
                >
                    {/* Centered Editorial Column */}
                    <div
                        className={css({
                            maxWidth: '780px',
                            margin: '0 auto',
                            width: '100%',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '24px',
                        })}
                    >
                        {/* Display Attached CSV Health Card in Conversation Panel if present */}
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
                                {/*
                                <p
                                    className={css({
                                        ...theme.typography.ParagraphMedium,
                                        margin: 0,
                                        maxWidth: '520px',
                                        color: theme.colors.contentSecondary,
                                        lineHeight: 1.6,
                                    })}
                                >
                                    Attach a CSV dataset directly to inspect structural integrity, heal malformed rows, and interactively query your data points.
                                </p> */}

                                {/* CSV Upload Area in Conversation Panel */}
                                <div className={css({ width: '30%', maxWidth: '540px', marginTop: '12px' })}>
                                    <CsvUploader onUploaded={handleCsvUploaded} />
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
                                            justifyContent: isUser ? 'flex-end' : 'flex-start',
                                            width: '100%',
                                        })}
                                    >
                                        <div
                                            className={css({
                                                maxWidth: isUser ? '75%' : '100%',
                                                padding: isUser ? '12px 18px' : '0',
                                                borderRadius: isUser ? '20px 20px 4px 20px' : '0',
                                                backgroundColor: isUser
                                                    ? theme.colors.contentPrimary
                                                    : 'transparent',
                                                color: isUser
                                                    ? theme.colors.backgroundPrimary
                                                    : theme.colors.contentPrimary,
                                                lineHeight: 1.65,
                                                boxShadow: isUser ? theme.lighting.shadow400 : 'none',
                                                transition: `all ${theme.animation.timing100} ease`,
                                            })}
                                        >
                                            {isUser ? (
                                                <div className={css({ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: '14px' })}>
                                                    {msg.content}
                                                </div>
                                            ) : (
                                                <div className={css({ width: '100%' })}>
                                                    {renderMessageContent(msg.content)}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                );
                            })
                        )}

                        {isSending && (
                            <div className={css({ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 0' })}>
                                <Spinner size={16} />
                                <span className={css({ fontSize: '13px', color: theme.colors.contentSecondary })}>
                                    Analyzing query and calculating dataset responses...
                                </span>
                            </div>
                        )}

                        <div ref={messagesEndRef} />
                    </div>
                </div>

                {/* Persistent Docked Prompts & Claude-Style Floating Input Capsule */}
                <div
                    className={css({
                        padding: `0 ${theme.sizing.scale700} ${theme.sizing.scale600}`,
                        backgroundColor: theme.colors.backgroundPrimary,
                        flexShrink: 0,
                    })}
                >
                    <div
                        className={css({
                            maxWidth: '780px',
                            margin: '0 auto',
                            width: '100%',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '10px',
                        })}
                    >
                        {/* PERSISTENT Recommended Prompts using Base Web Button with contrasted hover palette */}
                        <div
                            className={css({
                                display: 'flex',
                                alignItems: 'center',
                                gap: '8px',
                                overflowX: 'auto',
                                paddingBottom: '4px',
                                scrollbarWidth: 'none',
                                '::-webkit-scrollbar': { display: 'none' },
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
                                                transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
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

                        {/* Claude-Style Rounded Input Capsule with Contrasted Active Focus */}
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
                                transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                ':focus-within': {
                                    borderColor: theme.colors.contentPrimary,
                                    boxShadow: `0 0 0 1px ${theme.colors.contentPrimary}`,
                                },
                            })}
                        >
                            {/* Compact CSV attachment button */}
                            <CsvUploader
                                compact
                                onUploaded={handleCsvUploaded}
                            />

                            <div className={css({ flex: 1, minHeight: '36px', display: 'flex', alignItems: 'center' })}>
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
                                            }
                                        },
                                        Input: {
                                            style: {
                                                backgroundColor: 'transparent',
                                                fontSize: '14px',
                                                resize: 'none',
                                                padding: '4px 6px',
                                                color: theme.colors.contentPrimary,
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
                                            transition: `all ${theme.animation.timing200} ${theme.animation.easeInOutQuinticCurve}`,
                                        }
                                    }
                                }}
                            />
                        </div>

                        <div className={css({ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 8px', fontSize: '11px', color: theme.colors.contentTertiary })}>
                            <span>Press <kbd className={css({ padding: '1px 4px', border: `1px solid ${theme.colors.borderOpaque}`, borderRadius: '3px' })}>Enter</kbd> to send, <kbd className={css({ padding: '1px 4px', border: `1px solid ${theme.colors.borderOpaque}`, borderRadius: '3px' })}>Shift + Enter</kbd> for newline</span>
                            {attachedCsv && (
                                <span>Attached: {attachedCsv.filename}</span>
                            )}
                        </div>
                    </div>
                </div>
            </main>
        </div>
    );
}

export default ChatPage;
