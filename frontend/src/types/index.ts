export interface Message {
    id: string;
    role: 'user' | 'assistant' | 'system';
    content: string;
    timestamp: string;
}

export interface CsvIssue {
    issueType: string;
    description: string;
    rowNumber?: number | null;
    actionTaken: string;
}

export interface ColumnMetadata {
    name: string;
    originalName: string;
    inferredType: string;
    nullCount: number;
    totalCount: number;
    sampleValues: string[];
}

export interface CsvHealthCheckReport {
    status: 'HEALTHY' | 'WARNING' | 'CORRUPTED_HEALED' | 'ERROR';
    originalRowCount: number;
    healedRowCount: number;
    originalColumnCount: number;
    healedColumnCount: number;
    detectedDelimiter: string;
    autoHealed: boolean;
    summary: string;
    issues: CsvIssue[];
    columns: ColumnMetadata[];
}

export interface CsvUploadResponse {
    documentId: string;
    filename: string;
    originalSizeBytes: number;
    healedSizeBytes: number;
    headers: string[];
    previewRows: Record<string, unknown>[];
    healthReport: CsvHealthCheckReport;
}

export interface Conversation {
    id: string;
    title: string;
    userId?: string;
    documentId?: string | null;
    documentName?: string | null;
    messages: Message[];
    createdAt: string;
    updatedAt: string;
}

export interface UserProfile {
    authenticated: boolean;
    name?: string;
    email?: string;
    roles?: string[];
}

// Compatibility types for previous interfaces
export interface ColumnSchema {
    name: string;
    type: string;
    nullable: boolean;
}

export interface Relationship {
    fromTable: string;
    fromColumn: string;
    toTable: string;
    toColumn: string;
}

export interface HealthCheckItem {
    name: string;
    status: 'healthy' | 'warning' | 'error';
    message: string;
}

export interface HealthCheck {
    status: 'healthy' | 'warning' | 'error';
    checks: HealthCheckItem[];
}

export interface DocumentMetadata {
    filename: string;
    type: string;
    rowCount?: number;
    columnCount: number;
    healthCheck: HealthCheck;
    schema: {
        columns: ColumnSchema[];
        relationships: Relationship[];
    };
}

export interface UploadResponse {
    documentId: string;
    metadata: DocumentMetadata;
}
