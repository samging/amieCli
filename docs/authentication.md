# Authentication with Keycloak

The desktop app signs users in against the Keycloak that the Amie backend uses: the Docker container on this
machine, realm `master`, client `amie`. The code is in `desktopApp/src/main/kotlin/org/example/project/auth/`.

## Running Keycloak

Keycloak 22 runs from the `my-ubuntu-image` Docker Compose project together with a Postgres container. Keycloak
restarts in a loop while Postgres is down, so start both:

```bash
docker start my-ubuntu-image-postgresql-1
docker start my-ubuntu-image-keycloak-1     # or docker compose up -d in that project
curl -s http://localhost:8080/realms/master/.well-known/openid-configuration | head -c 200
```

Admin console: `http://localhost:8080/admin/master/console` (the admin user and password are the container's
`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` environment variables).

### Realm and client settings the app relies on

| Setting | Where | Needed for |
|---|---|---|
| Client `amie`, **Direct access grants** on | Clients → amie → Capability config | Username + password sign-in (`grant_type=password`) |
| Client `amie`, **Standard flow** on | same | Browser sign-in and registration (authorization code + PKCE) |
| Client `amie` is **confidential** with a secret | Clients → amie → Credentials | Every token request; the secret goes in `AMP/keycloak.json` |
| Valid redirect URIs include `http://localhost:48080/*` and `http://127.0.0.1:48080/*` | Clients → amie → Settings | The browser flows return to the app on that port (added for this app) |
| **User registration** on | Realm settings → Login | Create account (in-app and in the browser) |
| **Forgot password** on | Realm settings → Login | The "Forgot password?" link |
| **Login with email** | Realm settings → Login | Off in this realm: sign in with the username, an email address gives "Wrong username or password" (`user_not_found` in Keycloak's log) |
| **Verify email** | Realm settings → Login | When on, a new account must verify its email before it can sign in; the in-app register form then says so and the browser flow finishes it. Turn it off, or configure SMTP under Realm settings → Email, for a frictionless local setup |
| Frontend URL `http://192.168.1.114:8080` | Realm settings → General | The realm publishes that as issuer; the app verifies tokens against the issuer it discovers, so it works with either address |

## App configuration

`AMP/keycloak.json` is written with defaults on first start (the defaults have an empty `clientSecret`: the secret
comes only from the shipped seed or `KEYCLOAK_CLIENT_SECRET`) and is git-ignored because it carries the client secret:

```json
{
  "baseUrl": "http://localhost:8080",
  "realm": "master",
  "clientId": "amie",
  "clientSecret": "<the amie client's secret>",
  "redirectPort": 48080
}
```

Environment variables `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID` and `KEYCLOAK_CLIENT_SECRET` override
the file.

### Stale frontend URL

The realm's frontend URL (`http://192.168.1.114:8080`) is written into every form action and redirect Keycloak
renders. When the Mac gets another LAN address, that host stops answering and anything posted to it hangs until
it times out. The in-app **Create account** rewrites the form action to `baseUrl` from `AMP/keycloak.json`
(`KeycloakClient.sameServer`), so it keeps working; the browser flows (SSO sign-in, register in browser, forgot
password) still post to the frontend URL, so fix it under Realm settings → General when the address changes, or
clear it so Keycloak uses the request's host. Changing it also changes the issuer in tokens, which the Amie backend
checks.

## What the pages do

**Login page** (`render/templates/LoginPage.kt`)

- **Continue**: `POST /protocol/openid-connect/token` with `grant_type=password`. Keycloak's errors are turned into
  plain sentences ("Wrong username or password", "'Direct access grants' off", ...).
- **Sign in with browser (SSO)**: opens Keycloak's login page in the system browser with PKCE (S256) and a random
  state, listens on `http://localhost:48080/callback` for the code, and exchanges it. The hosted page handles
  identity providers, OTP and required actions.
- **Create account**: goes to the register page. **Forgot password?**: opens Keycloak's reset page in the browser.
- **Back to Settings**: leaves without signing in. The page opens from Account in Settings and returns there, done
  or not. Without an account the app runs as `guest-desktop-user`, no server needed; **Publish** on the Models page
  reads *Publish (sign in)* and leads to Account too.

**Register page** (`render/templates/RegisterPage.kt`)

- **Create account**: the same flow the Amie website's `sso-proxy` uses, done from the app: GET the registration
  page (with its session cookie), find the `kc-register-form` action, POST first name, last name, email, username and
  password, then read the code on the redirect back and exchange it. Field errors Keycloak renders are shown under
  the form. With "Verify email" on, Keycloak answers the POST with a redirect to its verify-email page instead of
  a code; the app opens that page server-side (that is what makes Keycloak send the mail) and shows a green
  notice naming the address the link went to (`EmailVerificationPending`). Open the link, then sign in with the
  username. The link uses the realm's frontend URL, so that must be reachable (see "Stale frontend URL").
- **Register in browser**: Keycloak's own registration page, returning through the loopback redirect.

**Every token** is checked before it becomes a session: it must be an RS256 JWT, its signature must verify against
the realm's JWKS (`/protocol/openid-connect/certs`), its issuer must match what the realm publishes, and it must
not be expired (`auth/Jwt.kt`, `KeycloakClient.sessionOf`).

## Sessions

`AuthState` (`auth/AuthSession.kt`) holds the signed-in user as a `StateFlow` that the Settings page and the Models
page observe. The session (access, refresh and id tokens plus the username and email claims)
is saved in `AMP/session.json`.

Every sign-in (password, browser and registration) asks for the scopes `openid profile email offline_access`
(`OFFLINE_SCOPE` in `KeycloakClient.kt`). With `offline_access`, Keycloak issues an **offline refresh token** that
outlives the SSO session: in this realm the SSO session idles out after 30 minutes and ends after 10 hours, while
an offline session only idles out after 30 days and is renewed every time the token is used. This needs the user to
hold the realm role `offline_access` (a default role for new users) and the client to offer the `offline_access`
scope (it is in the `amie` client's optional scopes). The access token lives 60 seconds, so every start refreshes it.

On the next start `resume()` puts the saved session back before the first page is drawn, with no network, so pages
show a signed-in person as signed in from their first frame and never as a guest while Keycloak is asked. `restore()`
then runs on a background thread. Nothing waits for it: the `start` route (`AppBaseTemplate.kt`) goes straight to the
main screen, so nobody meets a login page on start, whatever Keycloak is doing. With `AMIE_AUTORUN=1` it goes to the
sandbox the same way, and `main.kts` gets a fresh token from `accessToken()` when it first asks, sharing the renewal
already under way. A session is kept as is when its access token is valid, refreshed when it expired, and dropped only
when:

- Keycloak answered and refused the refresh token with a 400 or 401 (revoked, logged out elsewhere, offline session
  over), or
- the saved session belongs to another realm URL than `AMP/keycloak.json` names, or
- its refresh token's own expiry (`refreshExpiresAt`) has passed (no call to Keycloak is made then), or
- the user pressed **Log out**, which calls Keycloak's logout endpoint with the refresh token and deletes the file.

When Keycloak is **unreachable**, or answers with anything else (a 5xx while it starts, a proxy's 502), the session is
kept; `AuthState.accessToken()` retries the refresh on the next call that needs a bearer token. Refreshes run one at a
time, so the start and the first page that needs a token share one, and a **Log out** while Keycloak is answering
stands. The `auth:` lines in `AMP/logs.txt` say which of these happened.

## Relation to the Amie backend

The Spring Boot backend in the Amie repository keeps its own `/login` and `/register` endpoints with its own JWTs,
but since 2026-10-14 it also accepts the realm's access tokens as bearer tokens (`KeycloakTokenVerifier`). The
desktop app signs in with Keycloak directly, the way the Amie website's `oauthpage` does (same client, same grants),
and sends that token on every backend call, so one Keycloak account works for the website, the backend and the
app. Details in [backend-integration.md](backend-integration.md).
