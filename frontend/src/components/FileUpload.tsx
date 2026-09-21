import { useState, useRef, type DragEvent, type ChangeEvent } from 'react';
import type { UploadResponse } from '../types';
import { useUpload } from '../hooks/useUpload';

interface Props {
    onUploaded: (doc: UploadResponse) => void;
}

const statusSymbol = {
    pass: '[OK]',
    warn: '[WARN]',
    fail: '[FAIL]',
};

export function FileUpload({ onUploaded }: Props) {
    const { doc, loading, progress, error, upload, clear } = useUpload();
    const [dragging, setDragging] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);

    const handleFile = (file: File) => {
        upload(file).then(() => {
            // parent notification handled via useEffect or next render
        });
    };

    // Notify parent when doc changes
    if (doc) onUploaded(doc);

    const onDrop = (e: DragEvent<HTMLDivElement>) => {
        e.preventDefault();
        setDragging(false);
        const file = e.dataTransfer.files[0];
        if (file) handleFile(file);
    };

    const onChange = (e: ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (file) handleFile(file);
    };

    return (
        <div style={S.wrapper}>
            {!doc ? (
                <div
                    style={{ ...S.dropzone, ...(dragging ? S.dropzoneActive : {}) }}
                    onDragOver={e => { e.preventDefault(); setDragging(true); }}
                    onDragLeave={() => setDragging(false)}
                    onDrop={onDrop}
                    onClick={() => inputRef.current?.click()}
                >
                    <input
                        ref={inputRef}
                        type="file"
                        accept=".parquet,.csv,.pbix,.pbit,.bim,.json"
                        style={{ display: 'none' }}
                        onChange={onChange}
                    />

                    {loading ? (
                        <div style={S.loadingBox}>
                            <div style={S.progressBar}>
                                <div style={{ ...S.progressFill, width: `${progress}%` }} />
                            </div>
                            <span style={S.hint}>Uploading and parsing dataset...</span>
                        </div>
                    ) : (
                        <>
                            <div style={S.dropTitle}>Drop dataset here or browse</div>
                            <div style={S.hint}>
                                Supported formats: .parquet, .csv, .pbix, .pbit, .bim, .json
                            </div>
                        </>
                    )}

                    {error && <div style={S.err}>Error: {error}</div>}
                </div>
            ) : (
                <div style={S.card}>
                    {/* Header */}
                    <div style={S.cardHeader}>
                        <div>
                            <span style={S.filename}>{doc.metadata.filename}</span>
                            <span style={S.meta}>
                                {doc.metadata.type.toUpperCase()} | {doc.metadata.rowCount?.toLocaleString()} rows | {doc.metadata.columnCount} cols
                            </span>
                        </div>
                        <button style={S.clearBtn} onClick={clear} title="Remove file">Remove</button>
                    </div>

                    {/* Health checks */}
                    <div style={S.checks}>
                        {doc.metadata.healthCheck.checks.map((c, i) => (
                            <span key={i} style={{ ...S.checkBadge, ...S[c.status] }}>
                                {statusSymbol[c.status === 'healthy' ? 'pass' : c.status === 'warning' ? 'warn' : 'fail']}{' '}
                                {c.name}: {c.message}
                            </span>
                        ))}
                    </div>

                    {/* Schema summary */}
                    <div style={S.schema}>
                        <span style={S.schemaTitle}>Columns:</span>{' '}
                        {doc.metadata.schema.columns.map((c, i) => (
                            <span key={i} style={S.colTag}>
                                {c.name} <em style={S.colType}>({c.type})</em>
                            </span>
                        ))}
                    </div>

                    {/* Relationships (PBIX / BIM) */}
                    {doc.metadata.schema.relationships?.length > 0 && (
                        <div style={S.relationships}>
                            <span style={S.schemaTitle}>Relationships:</span>
                            {doc.metadata.schema.relationships.map((r, i) => (
                                <span key={i} style={S.relTag}>
                                    {r.fromTable}.{r.fromColumn} to {r.toTable}.{r.toColumn}
                                </span>
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

const S: Record<string, React.CSSProperties> = {
    wrapper:     { width: '100%' },
    dropzone:    {
        border: '1.5px dashed #30363d', borderRadius: 8, padding: '24px 16px',
        textAlign: 'center', cursor: 'pointer', transition: 'border-color 0.15s',
    },
    dropzoneActive: { borderColor: '#58a6ff', background: '#0d1117' },
    dropTitle:   { fontWeight: 600, fontSize: 14, color: '#e6edf3', marginBottom: 4 },
    hint:        { fontSize: 12, color: '#7d8590' },
    loadingBox:  { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 },
    progressBar: { width: 180, height: 4, background: '#21262d', borderRadius: 2, overflow: 'hidden' },
    progressFill:{ height: '100%', background: '#238636', transition: 'width 0.2s' },
    err:         { color: '#f85149', fontSize: 12, marginTop: 8 },

    card:        { background: '#161b22', border: '1px solid #30363d', borderRadius: 8, padding: 12 },
    cardHeader:  { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 },
    filename:    { fontWeight: 600, fontSize: 13, color: '#e6edf3', display: 'block' },
    meta:        { fontSize: 11, color: '#7d8590' },
    clearBtn:    { background: 'none', border: 'none', color: '#7d8590', cursor: 'pointer', fontSize: 12 },

    checks:      { display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 },
    checkBadge:  { fontSize: 11, padding: '2px 8px', borderRadius: 12, fontWeight: 500 },
    healthy:     { background: '#1b3a24', color: '#3fb950' },
    warning:     { background: '#3b2f00', color: '#d29922' },
    error:       { background: '#3d1c1c', color: '#f85149' },

    schema:      { fontSize: 11, color: '#7d8590', display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center' },
    schemaTitle: { fontWeight: 600, color: '#8b949e' },
    colTag:      { background: '#21262d', padding: '1px 6px', borderRadius: 4, color: '#c9d1d9' },
    colType:     { fontStyle: 'normal', color: '#79c0ff' },
    relationships:{ fontSize: 11, color: '#7d8590', marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 4 },
    relTag:      { background: '#21262d', padding: '1px 6px', borderRadius: 4, color: '#d2a8ff' },
};
export default FileUpload;
