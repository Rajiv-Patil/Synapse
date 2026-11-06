SELECT 'CREATE DATABASE synapse_identity' WHERE NOT EXISTS
    (SELECT FROM pg_database WHERE datname = 'synapse_identity') \gexec
SELECT 'CREATE DATABASE synapse_conversations' WHERE NOT EXISTS
    (SELECT FROM pg_database WHERE datname = 'synapse_conversations') \gexec
SELECT 'CREATE DATABASE synapse_datasets' WHERE NOT EXISTS
    (SELECT FROM pg_database WHERE datname = 'synapse_datasets') \gexec
SELECT 'CREATE DATABASE synapse_ai' WHERE NOT EXISTS
    (SELECT FROM pg_database WHERE datname = 'synapse_ai') \gexec

\connect synapse_identity
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sub VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255),
    name VARCHAR(255),
    provider VARCHAR(50) DEFAULT 'cognito',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_sub ON users(sub);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE TABLE IF NOT EXISTS service_migrations (
    version VARCHAR(100) PRIMARY KEY,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

\connect synapse_conversations
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL,
    document_id UUID,
    document_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_id ON conversations(user_id);

\connect synapse_datasets
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE TABLE IF NOT EXISTS csv_documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename VARCHAR(255) NOT NULL,
    conversation_id UUID NOT NULL,
    owner_sub VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    original_size_bytes BIGINT,
    healed_size_bytes BIGINT,
    metadata JSONB,
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_csv_documents_conversation_id ON csv_documents(conversation_id);
CREATE INDEX IF NOT EXISTS idx_csv_documents_owner_sub ON csv_documents(owner_sub);

\connect synapse_ai
SELECT 1;
