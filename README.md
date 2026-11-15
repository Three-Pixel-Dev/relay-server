# Relay Server

Spring Boot API for **Relay**, a digital product assistant that turns sources into sellable products (build → publish → distribute → promote) and supports creator partnerships.

## Stack

- Java 21
- Spring Boot 4.1
- Spring Web MVC, Data JPA, Data JDBC, Validation, Actuator
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
| `DATABASE_URL` | Neon-style Postgres URL |
| `SPRING_DATASOURCE_URL` | JDBC URL (`jdbc:postgresql://…`) |
| `SPRING_DATASOURCE_USERNAME` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | DB password |

`application.properties` reads the Spring datasource vars:

```properties
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

Windows:

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

## License

Proprietary — Three Pixel Dev / Expnai.
