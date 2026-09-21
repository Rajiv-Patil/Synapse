import { useState, useCallback } from 'react';
import type { UploadResponse } from '../types';
import { api } from '../services/api';

const ALLOWED = ['.parquet', '.csv', '.pbix', '.pbit', '.bim', '.json'];
const MAX_BYTES = 500 * 1024 * 1024; // 500 MB

function validate(file: File): string | null {
    const ext = '.' + (file.name.split('.').pop() ?? '').toLowerCase();
    if (!ALLOWED.includes(ext))
        return `Unsupported type "${ext}". Allowed: ${ALLOWED.join(', ')}`;
    if (file.size > MAX_BYTES)
        return `File too large (${(file.size / 1024 / 1024).toFixed(0)} MB). Limit: 500 MB`;
    return null;
}

export function useUpload() {
    const [doc,      setDoc]      = useState<UploadResponse | null>(null);
    const [loading,  setLoading]  = useState(false);
    const [progress, setProgress] = useState(0);
    const [error,    setError]    = useState<string | null>(null);

    const upload = useCallback(async (file: File) => {
        const err = validate(file);
        if (err) { setError(err); return; }

        setLoading(true);
        setError(null);
        setProgress(0);

        // Simulate progress while waiting for the server to parse the file
        const ticker = setInterval(
            () => setProgress(p => Math.min(p + 8, 88)),
            300
        );

        try {
            const result = await api.upload(file);
            clearInterval(ticker);
            setProgress(100);
            setDoc(result);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Upload failed');
        } finally {
            clearInterval(ticker);
            setLoading(false);
        }
    }, []);

    const clear = useCallback(() => {
        setDoc(null);
        setProgress(0);
        setError(null);
    }, []);

    return { doc, loading, progress, error, upload, clear };
}
