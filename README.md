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

`application.properties` reads the server port and Spring datasource vars:

```properties
server.port=${SERVER_PORT:8080}
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
```

Export those variables in your shell or IDE run configuration before starting (Spring Boot does not load `.env` automatically).

Schema is managed with `spring.jpa.hibernate.ddl-auto=update`.

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
