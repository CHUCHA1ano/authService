# authService

Authentication and authorization service for jACApp, implemented with **Keycloak 26.8.1**.

The service owns credentials (login/password), roles and JWT issuing. User profiles, cards and
other business data stay in the corresponding business services (e.g. `userService`).

## What is inside

```
authService/
├── Dockerfile                  # custom Keycloak image with the SPI plugins
├── docker-compose.yaml         # Keycloak + its own PostgreSQL database (internal network only)
├── pom.xml                     # Maven aggregator (opens the project in an IDE)
├── .env.example                # template of the git-ignored `.env` with all secrets
├── realm/
│   └── jacapp-realm.json       # realm, roles, clients, claims, token lifespan, password policy
└── providers/
    ├── bcrypt-provider/        # Keycloak PasswordHashProvider -> BCrypt
    └── claims-mapper/          # protocol mapper: user_id + single role claim
```

## Requirements covered

| Requirement | Implementation |
|---|---|
| Separate auth DB | PostgreSQL container `auth-service-postgres`, not exposed to the host |
| Hashed passwords, unique salt per password | Custom `bcrypt` `PasswordHashProvider`; BCrypt embeds a random salt in every hash |
| Login/password authentication | Keycloak OIDC token endpoint (password grant for the public frontend client) |
| JWT access + refresh | Keycloak realm `jacapp`, clients `jacapp-frontend` (public) and `jacapp-backend` (service account) |
| Token lifespans | Access token 15 minutes; the user stays logged in for 12h via the refresh token (SSO session 43200 s) |
| JWT contains user id and role | Default client scope `jacapp-claims`: `user_id` and single `role` (`ADMIN` / `USER`) claims |
| Roles admin/user | Realm roles `ADMIN` and `USER` |
| Admin API access | Service account of `jacapp-backend` with `manage-users`, `view-users`, `query-users`, `view-realm` |
| REST integration with UserService | UserService calls the Keycloak Admin REST API using the `jacapp-backend` client |
| No secrets in the repository | Client secret and admin credentials come from a git-ignored `.env` (Keycloak `${env.*}` placeholders) |

## Configuration

Copy the template and fill in the values:

```bash
cp .env.example .env
```

| Variable | Purpose |
|---|---|
| `KC_DB_NAME` / `KC_DB_USERNAME` | Keycloak database name / user |
| `KC_DB_PASSWORD` | Keycloak database password (required, no default) |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` | Bootstrap admin for the master realm (required) |
| `JC_CLIENT_SECRET` | Secret of the confidential `jacapp-backend` client (required). Must match `KEYCLOAK_CLIENT_SECRET` in the userService `.env` |

The realm import resolves the `${JC_CLIENT_SECRET}` placeholder into the client secret (this
requires `keycloak.migration.replace-placeholders=true`, which the compose file sets). No secret
value is stored in the repository.

## Run

```bash
docker compose up --build -d
```

Keycloak: http://localhost:8180 (admin console: bootstrap admin from `.env`)
Health: http://localhost:9000/health/ready
The database is reachable only inside the `jacapp-auth-network` network; inspect it with
`docker exec`.

The realm is imported on the first start. If `realm/jacapp-realm.json` changes later, remove the
volume and start again:

```bash
docker compose down -v
docker compose up --build -d
```

## Endpoints (built-in Keycloak OIDC)

| Purpose | Endpoint |
|---|---|
| Create access + refresh token (login) | `POST /realms/jacapp/protocol/openid-connect/token` |
| Refresh token | `POST /realms/jacapp/protocol/openid-connect/token` (`grant_type=refresh_token`) |
| Validate token | `GET /realms/jacapp/protocol/openid-connect/userinfo` or `POST .../token/introspect` |
| Logout | `POST /realms/jacapp/protocol/openid-connect/logout` |

Login example:

```bash
curl -X POST http://localhost:8180/realms/jacapp/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=jacapp-frontend" \
  -d "username=user@example.com" \
  -d "password=secret"
```

The access token contains the task-required claims `user_id` (the user id, same value as `sub`)
and `role` (a single string, `ADMIN` or `USER`), so downstream services do not have to parse
Keycloak-specific structures. Standard claims `sub` and `realm_access.roles` are still present.

The public `jacapp-frontend` client is restricted to `http://localhost:3000/*`
(redirect URIs, web origins and post-logout URI) with PKCE S256 enabled.

## BCrypt provider

The provider is a Keycloak SPI plugin:

- `PasswordHashProviderFactory` id: `bcrypt`
- cost factor: 12 (from the realm password policy `hashIterations(12)`; clamped to `4..31`)
- salt: 16 random bytes per password (BCrypt also embeds its own random salt)

Realm password policy:

```
length(8) and hashAlgorithm(bcrypt) and hashIterations(12)
```

Build the providers locally:

```bash
# from the authService root (Maven aggregator, builds both modules)
mvn clean package

# or a single module
cd providers/bcrypt-provider   # or providers/claims-mapper
mvn clean package
```

The jars are `providers/*/target/*.jar` and are copied into `/opt/keycloak/providers` by the
Docker image.

To verify that a password is stored as a BCrypt hash:

```bash
docker exec -it auth-service-postgres \
  psql -U keycloak -d keycloakdb \
  -c "select credential_data, secret_data from credential;"
```

`secret_data.value` starts with `$2a$12$...`.

## Notes

- Keycloak stores credentials in its own tables (`credential`, `user_entity`) — that is the
  "additional database" required by the task.
- UserService only creates/updates/deletes users through the Admin REST API; it never sees passwords.
- Access tokens are short-lived (15 minutes), so UserService must additionally check the local
  `active` flag of the user and revoke Keycloak sessions when an admin deactivates an account.
