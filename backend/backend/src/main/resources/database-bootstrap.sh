#!/bin/sh
set -eu
set -o pipefail

export PGHOST=postgres
export PGUSER="${POSTGRES_USER:-synapse_app}"
export PGPASSWORD="${PGPASSWORD:-${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD or PGPASSWORD}}"
legacy_db="${POSTGRES_DB:-synapse}"

until pg_isready -h "$PGHOST" -U "$PGUSER" -d "$legacy_db"; do
  sleep 2
done

psql -v ON_ERROR_STOP=1 -d "$legacy_db" -f /bootstrap/service-databases.sql

migrated="$(psql -d synapse_identity -Atc "SELECT count(*) FROM service_migrations WHERE version = 'legacy-v1'")"
legacy_tables="$(psql -d "$legacy_db" -Atc "SELECT to_regclass('public.users') IS NOT NULL AND to_regclass('public.conversations') IS NOT NULL AND to_regclass('public.csv_documents') IS NOT NULL")"

if [ "$migrated" = "0" ] && [ "$legacy_tables" = "t" ]; then
  psql -d synapse_identity -c "TRUNCATE TABLE users"
  psql -d synapse_conversations -c "TRUNCATE TABLE conversations"
  psql -d synapse_conversations -c "DO \$\$ DECLARE s RECORD; BEGIN FOR s IN SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'conv!_%' ESCAPE '!' LOOP EXECUTE format('DROP SCHEMA %I CASCADE', s.schema_name); END LOOP; END \$\$"
  psql -d synapse_datasets -c "TRUNCATE TABLE csv_documents"
  psql -d synapse_datasets -c "DO \$\$ DECLARE s RECORD; BEGIN FOR s IN SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'conv!_%' ESCAPE '!' LOOP EXECUTE format('DROP SCHEMA %I CASCADE', s.schema_name); END LOOP; END \$\$"

  psql -d "$legacy_db" -c "COPY (SELECT id, sub, email, name, provider, created_at, last_login_at FROM users) TO STDOUT WITH CSV HEADER" |
    psql -d synapse_identity -c "COPY users (id, sub, email, name, provider, created_at, last_login_at) FROM STDIN WITH CSV HEADER"

  psql -d "$legacy_db" -c "COPY (SELECT id, title, user_id, document_id, document_name, created_at, updated_at FROM conversations) TO STDOUT WITH CSV HEADER" |
    psql -d synapse_conversations -c "COPY conversations (id, title, user_id, document_id, document_name, created_at, updated_at) FROM STDIN WITH CSV HEADER"

  for conversation_id in $(psql -d "$legacy_db" -Atc "SELECT id FROM conversations"); do
    schema_name="conv_$(printf '%s' "$conversation_id" | tr '-' '_')"
    case "$conversation_id" in
      *[!0-9a-fA-F-]*) echo "Invalid conversation identifier in legacy data" >&2; exit 1 ;;
    esac
    psql -v ON_ERROR_STOP=1 -d synapse_conversations -c "CREATE SCHEMA IF NOT EXISTS \"$schema_name\"; CREATE TABLE IF NOT EXISTS \"$schema_name\".chat_history (id UUID PRIMARY KEY, role VARCHAR(20) NOT NULL, content TEXT NOT NULL, timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, sql_result TEXT); CREATE TABLE IF NOT EXISTS \"$schema_name\".interactions (id UUID PRIMARY KEY, query_type VARCHAR(50), query_text TEXT, result TEXT, timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
    if [ "$(psql -d "$legacy_db" -Atc "SELECT to_regclass('$schema_name.chat_history') IS NOT NULL")" = "t" ]; then
      psql -d "$legacy_db" -c "COPY (SELECT id, role, content, timestamp, sql_result FROM \"$schema_name\".chat_history) TO STDOUT WITH CSV HEADER" |
        psql -d synapse_conversations -c "COPY \"$schema_name\".chat_history (id, role, content, timestamp, sql_result) FROM STDIN WITH CSV HEADER"
      if [ "$(psql -d "$legacy_db" -Atc "SELECT to_regclass('$schema_name.interactions') IS NOT NULL")" = "t" ]; then
        psql -d "$legacy_db" -c "COPY (SELECT id, query_type, query_text, result, timestamp FROM \"$schema_name\".interactions) TO STDOUT WITH CSV HEADER" |
          psql -d synapse_conversations -c "COPY \"$schema_name\".interactions (id, query_type, query_text, result, timestamp) FROM STDIN WITH CSV HEADER"
      fi
    fi
  done

  for document in $(psql -d "$legacy_db" -Atc "SELECT id || '|' || conversation_id || '|' || table_name FROM csv_documents"); do
    document_id="${document%%|*}"
    remainder="${document#*|}"
    conversation_id="${remainder%%|*}"
    table_name="${remainder#*|}"
    schema_name="conv_$(printf '%s' "$conversation_id" | tr '-' '_')"
    case "$document_id$conversation_id$table_name" in
      *[!0-9a-fA-F_-]*) echo "Invalid dataset identifier in legacy data" >&2; exit 1 ;;
    esac
    if [ "$(psql -d "$legacy_db" -Atc "SELECT to_regclass('$schema_name.$table_name') IS NOT NULL")" = "t" ]; then
      pg_dump -d "$legacy_db" --no-owner --no-privileges --table="$schema_name.$table_name" |
        psql -v ON_ERROR_STOP=1 -d synapse_datasets
    fi
  done

  psql -d "$legacy_db" -c "COPY (SELECT d.id, d.filename, d.conversation_id, u.sub, d.table_name, d.original_size_bytes, d.healed_size_bytes, d.metadata, d.uploaded_at FROM csv_documents d JOIN conversations c ON c.id = d.conversation_id JOIN users u ON u.id = c.user_id) TO STDOUT WITH CSV HEADER" |
    psql -d synapse_datasets -c "COPY csv_documents (id, filename, conversation_id, owner_sub, table_name, original_size_bytes, healed_size_bytes, metadata, uploaded_at) FROM STDIN WITH CSV HEADER"

  psql -d synapse_identity -c "INSERT INTO service_migrations (version) VALUES ('legacy-v1') ON CONFLICT (version) DO NOTHING"
fi
