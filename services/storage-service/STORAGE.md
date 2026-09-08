# Storage / Archive Service

## Overview

The Archive service stores ingested messages and their attachments.

## Components

- PostgreSQL for message and attachment metadata
- Kafka for message ingestion and archive events
- MinIO/S3 infrastructure for object storage
- Flyway for database migrations
- Retention and disposition processing
- Legal hold handling

## Main Packages

- `api` — REST controllers
- `config` — application configuration
- `domain` — database entities and mapping
- `ingest` — message ingestion and archive processing
- `messaging` — Kafka publishing and event handling
- `repository` — PostgreSQL repositories
- `retention` — retention and disposition logic

## Database Migrations

- `V1__baseline.sql`
- `V2__messages.sql`
- `V3__disposition.sql`

## Tests

The Archive service contains tests for:

- Message mapping
- Archive service processing
- Retention/disposition processing