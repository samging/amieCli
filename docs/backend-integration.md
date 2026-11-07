# Amie backend and website

The desktop app, the Amie website (Kobweb site on port 8081) and the Amie backend (Spring Boot
`packageRepository`) share one identity: the Keycloak realm `master`, client `amie`. Since 2026-10-14 the
backend accepts Keycloak access tokens directly, so the account you sign in with in the app is the same account
the website shows, and what you publish from the app appears on the website's dashboard.

Since the same day the backend keeps its files in the Amie S3 bucket (reached through the awsS3 service on port
8088) instead of a GitHub repository: your packages under `uploads/<user>/<lang>/<file>`, your saved states under
`uploads/<user>/state/<name>.json` and the error log of your app under `uploads/<user>/state/errors.jsonl`. The
endpoint names the app calls did not change (`/list-github` is kept as an alias of the new `/packages`), and no
`GITHUB_TOKEN` is needed anywhere anymore.

## Configuration

`AMP/backend.json` (written with defaults on first start; no secrets, so it can be committed):

```json
{
  "baseUrl": "http://localhost:8080",
  "siteUrl": "http://localhost:8081",
  "s3ServiceUrl": "http://localhost:8088"
}
```

`AMIE_BACKEND_URL`, `AMIE_SITE_URL` and `AMIE_S3_URL` override the file. The S3 service is only asked for a
package jar the website cannot serve. `BackendConfig.load()` (`backend/BackendConfig.kt`) is
the single place the addresses come from; nothing else in the app hardcodes a host.

On a machine where Keycloak already listens on 8080, start the backend on another port (it reads
`AMIE_BACKEND_PORT`; the Amie repo's `run.sh` picks 8090 by itself) and point `baseUrl` there, for example
`http://localhost:8090`. The app checks this once per run (`BackendConfig.resolve()`, at start-up and before the
first backend call): when `baseUrl` answers like a Keycloak realm and the same host answers on 8090 like the
backend, it switches to 8090, saves the file and says so in the console. `AMIE_BACKEND_URL` is never changed.

The backend must accept the issuer the app's tokens carry. The realm has no fixed frontend URL, so the issuer is
the address the app reached Keycloak on (`http://localhost:8080/realms/master` with the default `keycloak.json`);
the backend's `amie.keycloak.issuer` (`KEYCLOAK_ISSUER`) is a comma-separated list and `run.sh` includes
localhost, 127.0.0.1 and the machine's LAN address. A 401 from the backend with "Expected iss claim" or "issuer
... is none of" in its log is this list missing the app's address.

## What talks to the backend

| Code | Endpoint | Token | Used by |
|---|---|---|---|
| `render/templates/Client.kt` `rest()` | `/get-device-status`, `/update-device-status`, `/fetch-endpoints`, `/post-endpoints` (`POST`), `/list-github`, `/list-github-metadata`, `/handle-login-error` | bearer when signed in | device dashboard, endpoints, plugins, device terminal |
| `backend/AmieBackend.kt` | `/me`, `/user-packages`, `/upload` (`POST`, `DELETE`; a delete also removes the old fixed names `model.onnx`, `module.json` and `guide.md`) | bearer, required | Models page: **Publish** on a model or a jar, **Remove from the website** |
| `backend/AmieBackend.kt` | site `/api/sso/handoff` | the session's tokens, in the body | `AmieBackend.websiteUrl(path)`: the signed-in handoff to the browser (kept in the client; no button opens it since the Models page lost its Website button) |
| `backend/AmieBackend.kt` | `/errors` (`POST`, `GET`) | bearer; a guest's report goes to `/handle-login-error` | `ErrorReporter`: every ERROR console line |
| `backend/AmieBackend.kt` | `/state`, `/state/{name}` (`GET`, `PUT`, `DELETE`) | bearer, required | the sandbox's `saveState` / `loadState` / `listStates`; Backup in Settings |
| `util/DownloadService.kt` | `/list-github` | none | package list text |

`Client.rest()` attaches `Authorization: Bearer <Keycloak access token>` through `AuthState.accessToken()`, which
refreshes the token when it is about to expire. Guests send no token. An absolute URL passed as the slug is used
as is; a path is joined to `baseUrl`. Failures land in `lastErrorMessage` and the dialog flags; only failures the
server answered are reported to `/handle-login-error`.

**Devices** are stored on the backend per username. `AppBaseTemplate` calls `DeviceManager.setSession` with the
Keycloak username whenever the session changes, so the backend sees the real user instead of a guest id. When the
backend is unreachable the local `AMP/componentSettings.json` is kept (it used to be overwritten with `{}`).

## Models page and package jars

The Models page shows no backend status; it uses the Keycloak session for one thing: **Publish** on a model
component calls `AmieBackend.publishModule`, which uploads `<name>.onnx`, `<name>.module.json` and `<name>.guide.md` through `POST /upload`
with `progLanguage=onnx` (stored as `uploads/<user>/onnx/<file>` in the S3 bucket). The backend answers 200
"File uploaded successfully", 400 with the reasons it refused a jar (a package jar must have `run(String): List`
and `close()`) or 502 when the store is unreachable; the page shows the reason. The package store keeps `guide.md` but leaves
`.md` files out of its package listings (they are the description the website shows on a file's page), so the
"is it on the website?" check after publishing only looks for `<name>.onnx` and `<name>.module.json`. A guest pressing Publish is sent to
Account in Settings.

`AmieBackend.websiteUrl(path)` would open `siteUrl/dashboard?username=<you>` signed in as the app's account (the Models page no longer has a button for it; a publish notice names the plain address instead).
The browser has no Keycloak session of its own, so `AmieBackend.websiteUrl(path)` hands the app's tokens to the
site's server (`POST /api/sso/handoff`, the token JSON as Keycloak answers it), gets a one-time code back (dies
after two minutes or its first use) and opens `siteUrl/sso?code=<code>&next=<path>`. The site's `/sso` page claims
the code (`POST /api/sso/claim`), verifies the tokens the way its own sign-in does (signature against the realm's
JWKS, issuer, expiry) and keeps the session, then continues to `next`. The tokens never appear in a URL. When
signed out, or when the site does not answer the handoff, the plain address opens and the page shows as a guest.

**Publish** on a jar in the Packages tab (a jar on this machine: in `AMP/pluginDir` or an env) uploads it with
`progLanguage=jar` through `AmieBackend.publishJar`, like the website's upload page; the backend refuses a jar
without `run(String): List` and `close()` and the reasons show in the notice. Afterwards the site is asked to
re-read its jar listing (`/api/jars?refresh=1`) so the jar appears under "On the server" and on `/downloads`.

Package jars flow the other way and need no token. `initMod("name.jar")` in the sandbox fetches a jar the server
lists (the website's `/api/jars` and `/api/jar-download?name=`, else the S3 service's `/api/aws/jars` and
`/api/aws/jars/<name>`) into `AMP/pluginDir` and opens a new instance of it; `packages.available()` lists what the
server offers. The S3 service address is `s3ServiceUrl` in `AMP/backend.json` (`AMIE_S3_URL` overrides it; default
`http://localhost:8088`). See [Packages](sandbox-scripting.md#packages).

## Error reports

Every ERROR-level console entry (`logError`, `logInternal(..., LogLevel.ERROR)`, a script's `error("...")`) is also
sent to the backend when a user is signed in: `logToConsole` hands it to `backend/ErrorReporter.kt`, which queues
it and posts `{"error": <source>, "message": <text>}` to `POST /errors` from a background coroutine, so the console
never waits. The same source and message are sent at most once a minute, messages are cut to 2000 characters, and
guests are not reported (a guest's failed backend call still goes to `/handle-login-error` as before). A report
that fails is written to the debug log only; it never becomes another console error. The lines of a script's `shell`
and `shellScript` commands are left out: what a command prints on this computer stays there. The backend appends each
report to `uploads/<user>/state/errors.jsonl` (the last 200 are kept), the website's dashboard shows them to the
owner under **Errors from your app** with a Clear button, and `AmieBackend.errors()` reads the same list
(`GET /errors?username=`, newest first, `{time, error, message}`).

## Saved states

The backend keeps small JSON documents per user under `uploads/<user>/state/<name>.json`; your devices
(`AMP/componentSettings.json`, synced through `/update-device-status`) are stored there as `devices`. The app reaches
them through `AmieBackend`: `listStates()` (`GET /state`, the names), `loadState(name)` (`GET /state/{name}`, the
JSON text or null), `saveState(name, json)` (`PUT /state/{name}`, any JSON up to 1 MiB) and `deleteState(name)`
(`DELETE /state/{name}`, false when there was none). A name matches `[A-Za-z0-9._-]{1,64}`. Scripts get the first
three as `saveState`, `loadState` and `listStates`, see [Saved states](sandbox-scripting.md#saved-states).

The limit is the backend's `MAX_STATE_BYTES`, 1 MiB of the request's JSON text, over which it answers 413; the app has
it as `AmieBackend.MAX_STATE_BYTES`. A `PUT /state/{name}` also counts against the upload rate limit, 20 a minute per
client by default, over which the backend answers 429. [Backup in Settings](settings.md#backup) is the state
`amp-backup`; a script that saves a state of that name replaces your backup.

## Cloud save

[Cloud save](sandbox.md#cloud-save) needs no new endpoint: it keeps one saved state per file, through the `/state`
calls above. The backend's code is in the Amie repository, not in this one, so this page is the whole contract the app
relies on, and it is what the app's tests (`CloudSaveTest`, against a fake backend on a localhost port) check.

| Call | Meaning |
|---|---|
| `GET /state` | the names of the account's saved states; the app reads those that start with `cloud-` |
| `GET /state/cloud-<id>` | one file, or 404 when the account has none |
| `PUT /state/cloud-<id>` | stores one file, replacing the last (204) |

`<id>` is the first 32 hex digits of the SHA-256 of the file's path, so the name is `[a-z0-9-]` and 38 characters. The
document is JSON, at most 1 MiB (413 over that):

```json
{"v": 1, "path": "templates/hello.kts", "text": "…", "updatedAt": "2026-11-01T09:30:00Z", "hash": "…"}
```

`path` is relative to `AMP` and is one of `settings.json`, `pluginDir/main.kts`, `templates/<name>.kts` or
`templates/<name>.json`; the app writes nothing else, whatever a document says, and ignores a document whose name is not
the hash of its path. `updatedAt` is when the file was last written on the Mac that sent it, and `hash` the SHA-256 of
its text (of a template's json without `loads`, `lastLoadedAt`, `createdAt` and `updatedAt`, which differ per Mac).

Limits to know: the backend counts every `PUT /state` against its upload rate limit (20 a minute), so a first sync of
many templates stops at 429 and the rest follows a minute later or at the next save; and `PUT` has no
precondition, so two Macs saving the same file in the same second can still overwrite one another. The app checks the
cloud's copy just before it sends, which closes all but that window. A conditional write (`If-Match` on the document's
`hash`, answering 412) would close it; the app doesn't need it to work. The backend is not changed by this feature.

## Backend side (Amie repository)

`packageRepository` validates bearer tokens in `UserService.validateToken`: first as a Keycloak token
(`KeycloakTokenVerifier`: RS256 signature against the realm's JWKS, issuer check, expiry), then as one of its own
HS512 login tokens. A Keycloak user gets a `users` row on first contact. Properties:

```properties
server.port=${AMIE_BACKEND_PORT:8080}
amie.keycloak.issuer=${KEYCLOAK_ISSUER:http://192.168.1.114:8080/realms/master}
amie.keycloak.jwks-uri=${KEYCLOAK_JWKS_URI:http://localhost:8080/realms/master/protocol/openid-connect/certs}
```

The issuer must equal the `iss` claim Keycloak writes, which is the realm's **frontend URL** plus `/realms/master`.
The JWKS URI is where the backend process can reach Keycloak, which may be a different host. If the realm's
frontend URL changes, change `KEYCLOAK_ISSUER` with it.

New endpoint `GET /me` (bearer) returns `{id, username, fullName, email, bio}` for the token's user; `GET /profile`
returns the same shape and no longer includes the password hash.

## Website side

The site reads its addresses from `config/AmieConfig.kt`: backend and Keycloak default to the host the page was
loaded from on port 8080, overridable per browser with `localStorage.setItem("amie.backendUrl", ...)` and
`localStorage.setItem("amie.keycloakUrl", ...)`. The Keycloak token exchange moved to the site's server
(`/api/keycloak/token`), which adds the client secret from `KEYCLOAK_CLIENT_SECRET`, so the secret is no longer in
browser JavaScript and the password login on the site works again. The server also reads `KEYCLOAK_URL` and
`AMIE_SITE_URL` for the redirect URI.

## What the website shows of a published model

A published module appears on the website under the author's `onnx` folder, in a tree beside the author's other
folders, under its own name: `intents.onnx`, `intents.module.json` and `intents.guide.md` for a model called
intents, so two models never overwrite each other. Opening the `.onnx` file there shows the model's structure (inputs, every node of the graph with its inputs,
outputs and attributes, the weights with their shapes and sizes, outputs, metadata) read from the file on the
site's server; `module.json` and `guide.md` open as text with syntax highlighting. Nothing runs on the website.

## Checking the whole chain

`scripts/e2e-auth-publish.py` in the Amie repository exercises everything on this page against the running
services: a Keycloak sign-in the way the app does it, `/me`, publishing a jar with the token, the site's listing
and upload proxy, the SSO handoff and its one-time code, saved states and the error log. Run it after changing
the backend, the site or the realm; it cleans up after itself.
