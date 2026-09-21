import { useState } from 'react';
import { useStyletron } from 'baseui';
import { Tag, KIND as TAG_KIND } from 'baseui/tag';
import { Button, KIND as BUTTON_KIND, SIZE as BUTTON_SIZE } from 'baseui/button';
import { ArrowDown } from 'baseui/icon';
import type { CsvUploadResponse, CsvIssue, ColumnMetadata } from '../types';

interface Props {
    csvData: CsvUploadResponse;
    onClear?: () => void;
}

export function CsvHealthCard({ csvData, onClear }: Props) {
    const [css, theme] = useStyletron();
    const [showIssues, setShowIssues] = useState(false);
    const [showSchema, setShowSchema] = useState(false);

    const report = csvData.healthReport;
    const isHealed = report.autoHealed || report.status === 'CORRUPTED_HEALED';

    const handleDownload = () => {
        window.open(`/api/csv/${csvData.documentId}/download`, '_blank');
    };

    return (
        <div
            className={css({
                backgroundColor: theme.colors.backgroundSecondary,
                border: `1px solid ${theme.colors.borderOpaque}`,
                borderRadius: theme.borders.radius400,
                padding: theme.sizing.scale500,
                marginBottom: theme.sizing.scale500,
                transition: `border-color ${theme.animation.timing200} ${theme.animation.easeOutCurve}`,
            })}
        >
            {/* Header info */}
            <div className={css({ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '12px' })}>
                <div className={css({ minWidth: 0, flex: 1 })}>
                    <div
                        className={css({
                            ...theme.typography.LabelMedium,
                            color: theme.colors.contentPrimary,
                            fontWeight: 600,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                        })}
                        title={csvData.filename}
                    >
                        {csvData.filename}
                    </div>
                    <div className={css({ ...theme.typography.ParagraphSmall, color: theme.colors.contentSecondary, marginTop: '2px' })}>
                        {report.healedRowCount - 1} rows · {report.healedColumnCount} columns · Delimiter: {report.detectedDelimiter}
                    </div>
                </div>

                <div className={css({ display: 'flex', alignItems: 'center', gap: '8px' })}>
                    <Button
                        size={BUTTON_SIZE.mini}
                        kind={BUTTON_KIND.secondary}
                        onClick={handleDownload}
                        startEnhancer={<ArrowDown size={14} />}
                        overrides={{
                            BaseButton: {
                                style: {
                                    borderRadius: theme.borders.radius300,
                                    fontSize: '12px',
                                }
                            }
                        }}
                    >
                        Download Healed
                    </Button>

                    {onClear && (
                        <button
                            onClick={onClear}
                            aria-label="Remove attached dataset"
                            className={css({
                                background: 'transparent',
                                border: `1px solid ${theme.colors.borderOpaque}`,
                                borderRadius: theme.borders.radius300,
                                cursor: 'pointer',
                                color: theme.colors.contentTertiary,
                                fontSize: '11px',
                                padding: '4px 8px',
                                transition: `all ${theme.animation.timing100} ease`,
                                ':hover': {
                                    color: theme.colors.contentPrimary,
                                    borderColor: theme.colors.borderSelected,
                                },
                            })}
                        >
                            Remove
                        </button>
                    )}
                </div>
            </div>

            {/* Status Tag - Monochromatic Base Web */}
            <div className={css({ display: 'flex', alignItems: 'center', gap: '8px', marginTop: theme.sizing.scale300, flexWrap: 'wrap' })}>
                {isHealed ? (
                    <Tag
                        closeable={false}
                        kind={TAG_KIND.neutral}
                        overrides={{
                            Root: {
                                style: {
                                    backgroundColor: theme.colors.backgroundTertiary,
                                    color: theme.colors.contentPrimary,
                                    border: `1px solid ${theme.colors.borderOpaque}`,
                                    borderRadius: theme.borders.radius200,
                                    fontSize: '11px',
                                    fontWeight: 500,
                                    margin: 0,
                                }
                            }
                        }}
                    >
                        Auto-Healed ({report.issues.length} repairs applied)
                    </Tag>
                ) : (
                    <Tag
                        closeable={false}
                        kind={TAG_KIND.neutral}
                        overrides={{
                            Root: {
                                style: {
                                    backgroundColor: theme.colors.backgroundTertiary,
                                    color: theme.colors.contentPrimary,
                                    border: `1px solid ${theme.colors.borderOpaque}`,
                                    borderRadius: theme.borders.radius200,
                                    fontSize: '11px',
                                    fontWeight: 500,
                                    margin: 0,
                                }
                            }
                        }}
                    >
                        Healthy Dataset
                    </Tag>
                )}

                <button
                    onClick={() => setShowIssues(!showIssues)}
                    className={css({
                        background: 'none',
                        border: 'none',
                        color: theme.colors.contentSecondary,
                        fontSize: '12px',
                        cursor: 'pointer',
                        padding: 0,
                        textDecoration: 'underline',
                        ':hover': { color: theme.colors.contentPrimary },
                    })}
                >
                    {showIssues ? 'Hide repairs' : `View repairs (${report.issues.length})`}
                </button>

                <button
                    onClick={() => setShowSchema(!showSchema)}
                    className={css({
                        background: 'none',
                        border: 'none',
                        color: theme.colors.contentSecondary,
                        fontSize: '12px',
                        cursor: 'pointer',
                        padding: 0,
                        textDecoration: 'underline',
                        ':hover': { color: theme.colors.contentPrimary },
                    })}
                >
                    {showSchema ? 'Hide schema' : `Inspect schema (${report.columns.length})`}
                </button>
            </div>

            {/* Toggle Issue Details if auto-healed */}
            {showIssues && report.issues.length > 0 && (
                <div
                    className={css({
                        marginTop: theme.sizing.scale400,
                        maxHeight: '180px',
                        overflowY: 'auto',
                        backgroundColor: theme.colors.backgroundTertiary,
                        borderRadius: theme.borders.radius300,
                        padding: '10px 12px',
                        fontSize: '12px',
                        border: `1px solid ${theme.colors.borderOpaque}`,
                    })}
                >
                    {report.issues.map((issue: CsvIssue, idx: number) => (
                        <div
                            key={idx}
                            className={css({
                                padding: '6px 0',
                                borderBottom: idx < report.issues.length - 1 ? `1px solid ${theme.colors.borderOpaque}` : 'none',
                            })}
                        >
                            <div className={css({ color: theme.colors.contentPrimary, fontWeight: 600 })}>
                                [{issue.issueType}] {issue.rowNumber ? `Row ${issue.rowNumber}` : 'Global'}
                            </div>
                            <div className={css({ color: theme.colors.contentSecondary, fontSize: '11px', marginTop: '2px' })}>
                                {issue.description}
                            </div>
                            <div className={css({ color: theme.colors.contentTertiary, fontSize: '11px', marginTop: '1px' })}>
                                Action: {issue.actionTaken}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {/* Toggle Schema Columns preview */}
            {showSchema && (
                <div
                    className={css({
                        marginTop: theme.sizing.scale400,
                        maxHeight: '180px',
                        overflowY: 'auto',
                        backgroundColor: theme.colors.backgroundTertiary,
                        borderRadius: theme.borders.radius300,
                        padding: '10px 12px',
                        fontSize: '12px',
                        border: `1px solid ${theme.colors.borderOpaque}`,
                    })}
                >
                    {report.columns.map((col: ColumnMetadata, idx: number) => (
                        <div
                            key={idx}
                            className={css({
                                display: 'flex',
                                justifyContent: 'space-between',
                                padding: '5px 0',
                                borderBottom: idx < report.columns.length - 1 ? `1px solid ${theme.colors.borderOpaque}` : 'none',
                            })}
                        >
                            <span className={css({ color: theme.colors.contentPrimary, fontWeight: 500 })}>{col.name}</span>
                            <span className={css({ color: theme.colors.contentSecondary, fontSize: '11px' })}>
                                {col.inferredType} {col.nullCount > 0 ? `(${col.nullCount} nulls)` : ''}
                            </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
