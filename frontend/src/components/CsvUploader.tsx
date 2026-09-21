import { useState, useRef, type DragEvent, type ChangeEvent } from 'react';
import { useStyletron } from 'baseui';
import { Spinner } from 'baseui/spinner';
import { Upload } from 'baseui/icon';
import { api } from '../services/api';
import type { CsvUploadResponse } from '../types';

interface Props {
    onUploaded: (data: CsvUploadResponse) => void;
    compact?: boolean;
}

export function CsvUploader({ onUploaded, compact = false }: Props) {
    const [css, theme] = useStyletron();
    const [isDragging, setIsDragging] = useState(false);
    const [isUploading, setIsUploading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const inputRef = useRef<HTMLInputElement>(null);

    const validateAndUpload = async (file: File) => {
        setError(null);
        const name = file.name.toLowerCase();
        if (!name.endsWith('.csv')) {
            setError('Invalid file format. Only CSV files (.csv) are accepted.');
            return;
        }

        setIsUploading(true);
        try {
            const data = await api.uploadCsv(file);
            onUploaded(data);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Upload failed');
        } finally {
            setIsUploading(false);
        }
    };

    const handleDrop = (e: DragEvent<HTMLDivElement>) => {
        e.preventDefault();
        setIsDragging(false);
        if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
            validateAndUpload(e.dataTransfer.files[0]);
        }
    };

    const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
        if (e.target.files && e.target.files.length > 0) {
            validateAndUpload(e.target.files[0]);
        }
    };

    if (compact) {
        return (
            <div>
                <input
                    ref={inputRef}
                    type="file"
                    accept=".csv,text/csv,application/vnd.ms-excel"
                    onChange={handleFileChange}
                    style={{ display: 'none' }}
                />
                <button
                    type="button"
                    onClick={() => !isUploading && inputRef.current?.click()}
                    aria-label="Upload CSV dataset"
                    className={css({
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        width: '36px',
                        height: '36px',
                        borderRadius: theme.borders.radius400,
                        backgroundColor: theme.colors.backgroundTertiary,
                        border: `1px solid ${theme.colors.borderOpaque}`,
                        color: theme.colors.contentPrimary,
                        cursor: isUploading ? 'not-allowed' : 'pointer',
                        transition: `all ${theme.animation.timing100} ${theme.animation.easeOutCurve}`,
                        ':hover': {
                            backgroundColor: theme.colors.backgroundSecondary,
                            borderColor: theme.colors.borderSelected,
                        },
                    })}
                    title="Upload CSV"
                >
                    {isUploading ? <Spinner size={16} /> : <Upload size={16} />}
                </button>
            </div>
        );
    }

    return (
        <div className={css({ width: '100%', boxSizing: 'border-box' })}>
            <div
                onClick={() => !isUploading && inputRef.current?.click()}
                onDragOver={(e) => { e.preventDefault(); setIsDragging(true); }}
                onDragLeave={() => setIsDragging(false)}
                onDrop={handleDrop}
                className={css({
                    border: `1px dashed ${isDragging ? theme.colors.contentPrimary : theme.colors.borderOpaque}`,
                    borderRadius: theme.borders.radius400,
                    padding: `${theme.sizing.scale600} ${theme.sizing.scale500}`,
                    textAlign: 'center',
                    cursor: isUploading ? 'not-allowed' : 'pointer',
                    backgroundColor: isDragging
                        ? theme.colors.backgroundTertiary
                        : theme.colors.backgroundSecondary,
                    transition: `all ${theme.animation.timing200} ${theme.animation.easeOutCurve}`,
                    ':hover': {
                        borderColor: theme.colors.contentPrimary,
                        backgroundColor: theme.colors.backgroundTertiary,
                    },
                })}
            >
                <input
                    ref={inputRef}
                    type="file"
                    accept=".csv,text/csv,application/vnd.ms-excel"
                    onChange={handleFileChange}
                    style={{ display: 'none' }}
                />

                {isUploading ? (
                    <div className={css({ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px' })}>
                        <Spinner size={24} />
                        <span className={css({ ...theme.typography.ParagraphSmall, color: theme.colors.contentSecondary })}>
                            Analyzing structure and auto-healing corruptions...
                        </span>
                    </div>
                ) : (
                    <div className={css({ display: 'flex', flexDirection: 'row', alignItems: 'center', gap: '8px' })}>
                        <div
                            className={css({
                                width: '36px',
                                height: '36px',
                                borderRadius: theme.borders.radius400,
                                backgroundColor: theme.colors.backgroundTertiary,
                                border: `1px solid ${theme.colors.borderOpaque}`,
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                color: theme.colors.contentPrimary,
                            })}
                        >
                            <Upload size={18} />
                        </div>
                        <div>
                            <div className={css({ ...theme.typography.LabelMedium, color: theme.colors.contentPrimary, fontWeight: 600 })}>
                                Select or Drop CSV
                            </div>

                        </div>
                    </div>
                )}
            </div>

            {error && (
                <div
                    className={css({
                        marginTop: theme.sizing.scale300,
                        padding: '8px 12px',
                        borderRadius: theme.borders.radius300,
                        backgroundColor: theme.colors.backgroundTertiary,
                        border: `1px solid ${theme.colors.borderOpaque}`,
                        color: theme.colors.contentPrimary,
                        fontSize: '12px',
                    })}
                >
                    {error}
                </div>
            )}
        </div>
    );
}
