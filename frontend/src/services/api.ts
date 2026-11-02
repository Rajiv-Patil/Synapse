import type {
    UserProfile,
    CsvUploadResponse,
    Conversation,
    Message,
    UploadResponse
} from '../types';

export const api = {
    async getAuthStatus(): Promise<UserProfile> {
        try {
            const res = await fetch('/api/auth/me', { credentials: 'include' });
            if (!res.ok) return { authenticated: false };
            return await res.json();
        } catch {
            return { authenticated: false };
        }
    },

    async uploadCsv(file: File, conversationId?: string): Promise<CsvUploadResponse> {
        const formData = new FormData();
        formData.append('file', file);
        if (conversationId) {
            formData.append('conversationId', conversationId);
        }

        const res = await fetch('/api/csv/upload', {
            method: 'POST',
            body: formData,
            credentials: 'include',
        });

        if (!res.ok) {
            let errorMsg = 'Failed to upload CSV';
            try {
                const errJson = await res.json();
                if (errJson.error) errorMsg = errJson.error;
            } catch {
                // fallback
            }
            throw new Error(errorMsg);
        }

        return await res.json();
    },

    async upload(file: File): Promise<UploadResponse> {
        const csvRes = await this.uploadCsv(file);
        return {
            documentId: csvRes.documentId,
            metadata: {
                filename: csvRes.filename,
                type: 'csv',
                rowCount: csvRes.healthReport.healedRowCount - 1,
                columnCount: csvRes.healthReport.healedColumnCount,
                healthCheck: {
                    status: csvRes.healthReport.status === 'HEALTHY' ? 'healthy' : 'warning',
                    checks: csvRes.healthReport.issues.map(i => ({
                        name: i.issueType,
                        status: 'warning',
                        message: i.description
                    }))
                },
                schema: {
                    columns: csvRes.healthReport.columns.map(c => ({
                        name: c.name,
                        type: c.inferredType,
                        nullable: c.nullCount > 0
                    })),
                    relationships: []
                }
            }
        };
    },

    streamChat(
        params: { prompt: string; history?: Message[]; documentId?: string; sessionId?: string; conversationId?: string },
        onToken: (token: string) => void,
        onDone: (conversationId: string, message: Message) => void,
        onError: (err: Error) => void
    ): () => void {
        let isCancelled = false;
        (async () => {
            try {
                const resp = await this.sendMessage(params.prompt, params.conversationId, params.documentId);
                if (!isCancelled) {
                    onToken(resp.message.content);
                    onDone(resp.conversationId, resp.message);
                }
            } catch (e) {
                if (!isCancelled) {
                    onError(e instanceof Error ? e : new Error(String(e)));
                }
            }
        })();

        return () => {
            isCancelled = true;
        };
    },

    async getConversations(): Promise<Conversation[]> {
        const res = await fetch('/api/conversations', { credentials: 'include' });
        if (!res.ok) throw new Error(`Failed to load conversations (${res.status})`);
        return await res.json();
    },

    async createConversation(title?: string, documentId?: string, documentName?: string): Promise<Conversation> {
        const res = await fetch('/api/conversations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, documentId, documentName }),
            credentials: 'include',
        });

        if (!res.ok) throw new Error('Failed to create conversation');
        return await res.json();
    },

    async getConversation(id: string): Promise<Conversation> {
        const res = await fetch(`/api/conversations/${id}`, { credentials: 'include' });
        if (!res.ok) throw new Error('Failed to load conversation');
        return await res.json();
    },

    async getCsvDocument(id: string): Promise<CsvUploadResponse> {
        const res = await fetch(`/api/csv/${id}`, { credentials: 'include' });
        if (!res.ok) throw new Error(`Failed to load dataset (${res.status})`);
        return await res.json();
    },

    async deleteConversation(id: string): Promise<boolean> {
        const res = await fetch(`/api/conversations/${id}`, {
            method: 'DELETE',
            credentials: 'include',
        });
        if (!res.ok) throw new Error(`Failed to delete conversation (${res.status})`);
        const result = await res.json();
        return result.deleted === true;
    },

    async addConversationMessage(id: string, content: string): Promise<Message> {
        const res = await fetch(`/api/conversations/${id}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content }),
            credentials: 'include',
        });
        if (!res.ok) throw new Error(`Failed to save conversation message (${res.status})`);
        const result = await res.json();
        return result.message;
    },

    async sendMessage(
        prompt: string,
        conversationId?: string,
        documentId?: string
    ): Promise<{ conversationId: string; message: Message; conversation: Conversation }> {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt, conversationId, documentId }),
            credentials: 'include',
        });

        if (!res.ok) throw new Error(`Failed to send message (${res.status})`);
        return await res.json();
    },

    downloadHealedCsvUrl(documentId: string): string {
        return `/api/csv/${documentId}/download`;
    }
};
