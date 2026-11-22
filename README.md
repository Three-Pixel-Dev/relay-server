# Relay Server

Spring Boot API for **Relay**, a digital product assistant that turns sources into sellable products (build → publish → distribute → promote) and supports creator partnerships.

## Stack

- Java 21
- Spring Boot 4.1
- Spring Web MVC, Data JPA, Data JDBC, Validation, Security, Actuator
- JWT (jjwt), OpenAPI / Swagger
- PostgreSQL (Neon)
- Lombok
- Maven Wrapper (`mvnw`)

## Requirements

- JDK 21+
- A Neon / PostgreSQL database

## Configuration

Copy `.env.example` to `.env` and fill in values (do not commit `.env`):

| Variable | Purpose |
|----------|---------|
| `SERVER_PORT` | HTTP port for server (default: `8080`) |
| `DATABASE_URL` | Neon-style Postgres URL |
| `SPRING_DATASOURCE_URL` | JDBC URL (`jdbc:postgresql://…`) |
| `SPRING_DATASOURCE_USERNAME` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `JWT_SECRET` | HMAC secret for access/refresh tokens |
| `JWT_EXPIRATION_MS` | Access token TTL (default 1 day) |
| `JWT_REFRESH_EXPIRATION_MS` | Refresh token TTL (default 7 days) |
| `GOOGLE_OAUTH_CLIENT_IDS` | Comma-separated Google OAuth client IDs (audiences) |

`application.properties` reads the server port and Spring datasource vars:

```properties
server.port=${SERVER_PORT:8080}
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
```

Export those variables in your shell or IDE run configuration before starting (Spring Boot does not load `.env` automatically).

Schema is managed with `spring.jpa.hibernate.ddl-auto=update`.

## Gmail integration

Multi-account Gmail via Google OAuth 2.0 and the Gmail API. No Google Cloud Pub/Sub or Cloud KMS is used; mailboxes are synced by a Spring `@Scheduled` job using the Gmail History API, and tokens are encrypted in-app with AES-256-GCM.

| Variable | Purpose |
|----------|---------|
| `RELAY_TOKEN_ENCRYPTION_KEY` | Base64 of exactly 32 random bytes (required to connect Gmail) |
| `RELAY_TOKEN_ENCRYPTION_KEY_VERSION` | Version stamped on new ciphertext (default `1`) |
| `RELAY_TOKEN_ENCRYPTION_PREVIOUS_KEY` / `_VERSION` | Old key kept only to decrypt during rotation |
| `GMAIL_OAUTH_CLIENT_ID` / `GMAIL_OAUTH_CLIENT_SECRET` / `GMAIL_OAUTH_REDIRECT_URI` | Google OAuth web client |
| `GMAIL_FRONTEND_REDIRECT_BASE` | Where the OAuth callback redirects the browser |
| `GMAIL_SCHEDULING_ENABLED` | Enable History sync + follow-up schedulers (default `true`) |
| `GMAIL_SYNC_INTERVAL_MS` | History sync interval (default `60000`) |
| `GMAIL_FOLLOWUP_INTERVAL_MS` | Follow-up send poll interval (default `60000`) |
| `GMAIL_SYNC_LEASE_SECONDS` | Per-mailbox DB lease so instances never sync the same mailbox concurrently |

Endpoints: `GET /api/gmail/mailboxes`, `GET /api/gmail/mailboxes/{id}`, `POST /api/gmail/oauth/start`, `GET /api/gmail/oauth/callback`, `POST /api/gmail/oauth/complete`, `DELETE /api/gmail/mailboxes/{id}`.

Details: [docs/token-encryption.md](docs/token-encryption.md) (key generation, backup, rotation) and [docs/gmail-history-sync.md](docs/gmail-history-sync.md).

## Deploy (GCP VM)

See [docs/deploy-gcp.md](docs/deploy-gcp.md) for JAR + GitHub Actions SSH deploy (Tobu-style).  
VM example: `35.185.177.9` — put the SSH **private** key in GitHub secret `GCP_VM_SSH_KEY`, never in chat.

## Run

```bash
./mvnw spring-boot:run
```

Windows (loads `.env` automatically):

```cmd
.\run.bat
```

Or manually:

```powershell
.\mvnw.cmd spring-boot:run
```

Compile only:

```bash
./mvnw -DskipTests compile
```

## Package layout

```
com.expnai.relay
├── RelayApplication
├── common
│   ├── persistence      # MasterEntity, BaseRepository, AuditUserContext
│   └── specification    # MasterEntitySpecifications
└── domain
    ├── entity           # JPA models
    ├── enums            # Domain enums + AttributeConverters
    └── repository       # Empty Spring Data repositories
```

### Persistence bases

Entities extend `MasterEntity` (soft-delete via `is_enable`, audit timestamps / user ids).  
Repositories extend `BaseRepository<T>` (`JpaRepository` + `JpaSpecificationExecutor`).  
`AuditUserContext` supplies optional `created_by` / `updated_by` during persist/update.

### Domain entities

Aligned with the Relay product data model:

| Area | Entities |
|------|----------|
| Account | `RelayUser`, `RelaySubscription`, `RelayServiceConnection` |
| Build | `RelayProduct`, `RelaySource`, `RelayPassage`, `RelaySourceProfile`, `RelayPocket`, `RelaySection`, `RelayCitation`, `RelayBrand`, `RelayProductFile`, `RelayThreadMessage` |
| Publish | `RelayListing`, `RelayWhopProduct`, `RelayOrder` |
| Distribute / promote | `RelayChannel`, `RelayBatch`, `RelayMetric`, `RelayPromoAsset` |
| Partners | `RelayProspect`, `RelayOutreachTemplate`, `RelayOutreachMessage`, `RelayReply`, `RelayPartnership`, `RelaySuppression` |
| Ops | `RelayAuditEvent` |

Each entity has a matching empty `*Repository` interface under `domain.repository`.


## Accounts API

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/signup` | public | Create account + JWT (no trial) |
| POST | `/api/auth/login` | public | Login |
| POST | `/api/auth/google` | public | Google Sign-In (ID token) |
| POST | `/api/auth/refresh-token` | public | Refresh tokens |
| GET | `/api/auth/me` | JWT | Current user + subscription summary |
| PUT | `/api/auth/profile` | JWT | Update name |
| GET/POST/DELETE | `/api/auth/sessions`, `/logout`, `/logout-all` | JWT | Session management |
| GET | `/api/subscription/plans` | public | 6-month and yearly plans |
| GET | `/api/subscription/me` | JWT | Current subscription |
| POST | `/api/subscription/subscribe` | JWT | Start/replace plan |
| POST | `/api/subscription/cancel` | JWT | Cancel at period end |
| POST | `/api/subscription/resume` | JWT | Undo cancel |
| GET | `/api/subscription/history` | JWT | Billing history |
| GET | `/api/subscription/billing-portal` | JWT | Placeholder until payment provider is set |

Swagger UI: `/swagger-ui.html`

## License

Proprietary — Three Pixel Dev / Expnai.
