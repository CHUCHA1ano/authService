# authService

Authentication and authorization service for jACApp, implemented with **Keycloak 26.8.1**.

The service owns credentials (login/password), roles and JWT issuing. User profiles, cards and
other business data stay in the corresponding business services (e.g. `userService`).

## What is inside

```
authService/
├── Dockerfile                  # custom Keycloak image with the BCrypt provider
├── docker-compose.yaml         # Keycloak + its own PostgreSQL database
├── realm/
│   └── jacapp-realm.json       # realm, roles, clients, token lifespan, password policy
└── providers/
    └── bcrypt-provider/        # Keycloak PasswordHashProvider -> BCrypt
```

## Requirements covered

| Requirement | Implementation |
|---|---|
| Separate auth DB | PostgreSQL container `auth-service-postgres`, database `keycloakdb` |
| Hashed passwords, unique salt per password | Custom `bcrypt` `PasswordHashProvider`; BCrypt embeds a random salt in every hash |
| Login/password authentication | Keycloak OIDC token endpoint (password grant for the public frontend client) |
| JWT access + refresh | Keycloak realm `jacapp`, clients `jacapp-frontend` (public) and `jacapp-backend` (service account) |
| Token lifespan 12h | `accessTokenLifespan`, `ssoSessionIdleTimeout`, `ssoSessionMaxLifespan` = `43200` seconds |
| Roles admin/user | Realm roles `ADMIN` and `USER` |
| Admin API access | Service account of `jacapp-backend` with `manage-users`, `view-users`, `query-users`, `view-realm` |
| REST integration with UserService | UserService calls the Keycloak Admin REST API using the `jacapp-backend` client |

## Run

```bash
docker compose up --build -d
```

Keycloak: http://localhost:8180 (admin console: `admin` / `admin`)
Health: http://localhost:9000/health/ready
Auth DB: `localhost:5433`, database `keycloakdb`, user `keycloak` / `keycloak`

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

The access token contains `sub` (user id) and `realm_access.roles` (`ADMIN` / `USER`).

## BCrypt provider

The provider is a Keycloak SPI plugin:

- `PasswordHashProviderFactory` id: `bcrypt`
- cost factor: 12 (from the realm password policy `hashIterations(12)`; clamped to `4..31`)
- salt: 16 random bytes per password (BCrypt also embeds its own random salt)

Realm password policy:

```
length(8) and hashAlgorithm(bcrypt) and hashIterations(12)
```

Build the provider locally:

```bash
cd providers/bcrypt-provider
mvn clean package
```

The shaded jar is `target/bcrypt-password-hash-provider.jar` and is copied into
`/opt/keycloak/providers` by the Docker image.

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
- Because access tokens live for 12 hours, UserService must additionally check the local `active`
  flag of the user and revoke Keycloak sessions when an admin deactivates an account.
