-- Database Initialization Script for Synapse
-- Simplified version - run as synapse_app user

-- Enable UUID extension (if not already enabled)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sub VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255),
    name VARCHAR(255),
    provider VARCHAR(50) DEFAULT 'cognito',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

-- Create conversations table
CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID,
    document_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create csv_documents table
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

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_conversations_user_id ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_csv_documents_conversation_id ON csv_documents(conversation_id);
CREATE INDEX IF NOT EXISTS idx_users_sub ON users(sub);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Create function to get current user ID from session
CREATE OR REPLACE FUNCTION current_user_id()
RETURNS UUID AS $$
BEGIN
    -- This will be set by the application using SET LOCAL
    RETURN NULLIF(current_setting('app.current_user_id', true), '')::UUID;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to create conversation schema dynamically
CREATE OR REPLACE FUNCTION create_conversation_schema(p_conversation_id UUID, p_user_id UUID)
RETURNS VOID AS $$
DECLARE
    schema_name TEXT;
    reader_role TEXT;
    writer_role TEXT;
    user_role TEXT;
BEGIN
    -- Sanitize and create schema name
    schema_name := 'conv_' || replace(p_conversation_id::TEXT, '-', '_');
    user_role := 'user_' || replace(p_user_id::TEXT, '-', '_');
    reader_role := schema_name || '_reader';
    writer_role := schema_name || '_writer';

    -- Create schema
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);

    -- Create chat_history table
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.chat_history (
            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
            role VARCHAR(20) NOT NULL,
            content TEXT NOT NULL,
            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )', schema_name);

    -- Create interactions table
    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.interactions (
            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
            query_type VARCHAR(50),
            query_text TEXT,
            result TEXT,
            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )', schema_name);

    -- Create roles if they don't exist
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = reader_role) THEN
        EXECUTE format('CREATE ROLE %I', reader_role);
    END IF;

    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = writer_role) THEN
        EXECUTE format('CREATE ROLE %I', writer_role);
    END IF;

    -- Grant permissions on schema
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', schema_name, reader_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', schema_name, writer_role);

    -- Grant permissions on tables
    EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I', schema_name, reader_role);
    EXECUTE format('GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA %I TO %I', schema_name, writer_role);

    -- Grant roles to user role
    EXECUTE format('GRANT %I TO %I', reader_role, user_role);
    EXECUTE format('GRANT %I TO %I', writer_role, user_role);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to create dataset table in conversation schema
CREATE OR REPLACE FUNCTION create_dataset_table(
    p_conversation_id UUID,
    p_document_id UUID,
    p_table_name TEXT,
    p_columns JSONB
) RETURNS VOID AS $$
DECLARE
    schema_name TEXT;
    full_table_name TEXT;
    column_def TEXT;
    column_record RECORD;
BEGIN
    schema_name := 'conv_' || replace(p_conversation_id::TEXT, '-', '_');
    full_table_name := schema_name || '.' || p_table_name;

    -- Build column definitions
    column_def := '';
    FOR column_record IN SELECT * FROM jsonb_array_elements(p_columns)
    LOOP
        IF column_def != '' THEN
            column_def := column_def || ', ';
        END IF;
        column_def := column_def ||
            quote_ident(column_record.value->>'name') || ' ' ||
            (column_record.value->>'pg_type');
    END LOOP;

    -- Create table
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I.%I (%s)', schema_name, p_table_name, column_def);

    -- Grant permissions
    EXECUTE format('GRANT SELECT ON %I.%I TO %I_reader', schema_name, p_table_name, schema_name);
    EXECUTE format('GRANT SELECT, INSERT ON %I.%I TO %I_writer', schema_name, p_table_name, schema_name);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to drop conversation schema
CREATE OR REPLACE FUNCTION drop_conversation_schema(p_conversation_id UUID)
RETURNS VOID AS $$
DECLARE
    schema_name TEXT;
    reader_role TEXT;
    writer_role TEXT;
BEGIN
    schema_name := 'conv_' || replace(p_conversation_id::TEXT, '-', '_');
    reader_role := schema_name || '_reader';
    writer_role := schema_name || '_writer';

    -- Drop schema cascade
    EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', schema_name);

    -- Drop roles
    EXECUTE format('DROP ROLE IF EXISTS %I', reader_role);
    EXECUTE format('DROP ROLE IF EXISTS %I', writer_role);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to create user-specific database role
CREATE OR REPLACE FUNCTION create_user_role(p_user_id UUID)
RETURNS VOID AS $$
DECLARE
    user_role TEXT;
BEGIN
    user_role := 'user_' || replace(p_user_id::TEXT, '-', '_');

    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = user_role) THEN
        EXECUTE format('CREATE ROLE %I WITH LOGIN', user_role);
        EXECUTE format('GRANT CONNECT ON DATABASE synapse TO %I', user_role);
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', user_role);
        EXECUTE format('GRANT SELECT, INSERT ON users, conversations, csv_documents TO %I', user_role);
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
