# Signing in

The app never stops you to sign in. It opens on the sandbox straight away, as a guest when nobody is signed in.
Signing in lives in [Settings](settings.md#account), under **Account**. It gives a backup of your scripts and settings
there, and on the Models page **Publish** and the search over the Amie package repository.

You sign in **once**. The app keeps the session in `AMP/session.json` and signs you back in silently on every start,
for as long as Keycloak's offline session allows (30 days without use by default, renewed each time the app uses it).
The app doesn't wait for that, and neither do you: the saved session is read before the first page is drawn, so
Settings shows you as signed in as soon as it opens, before Keycloak has answered. The session only ends when you
pressed **Log out**, when Keycloak refused the saved session (for example the account was disabled), or after a month
without opening the app; the app then runs as a guest until you sign in again.

Without an account the app uses the name `guest-desktop-user`, and no server is needed. Guests can use the sandbox,
the Models page and local metrics; they cannot publish to the backend, back up, search the package repository or
sync devices under their own name.

## The login page

**Sign in** and **Create account** under Account in Settings open the login and register pages. Each returns to
Settings when you are done or back out, by the header's back arrow or by the login page's **Back to Settings**, and
going from one to the other doesn't change that.

| Button | What happens |
|---|---|
| **Continue** | Username and password are sent to Keycloak (`grant_type=password`). The field reads *Username or email*, but use the **username**: this realm does not log in by email. |
| **Sign in with browser (SSO)** | Keycloak's own login page opens in your browser; it handles identity providers, one-time codes and required actions, then returns to the app on `http://localhost:48080/callback`. |
| **Create account** | A registration form inside the app (first name, last name, email, username, password). With *Verify email* on in the realm you get a mail first; open its link, then sign in with the username. |
| **Register in browser** | Keycloak's hosted registration page. |
| **Forgot password?** | Keycloak's reset page in the browser. |
| **Back to Settings** | Leaves without signing in. |

## How the session is kept

- Every sign-in asks Keycloak for the scopes `openid profile email offline_access`. The last one makes Keycloak issue
  an **offline refresh token**, the same mechanism "remember me" uses in other apps: it does not die with the
  browser-style SSO session (30 minutes idle), only with the realm's offline session idle timeout.
- The access token itself is short-lived (60 seconds in this realm). On start the app refreshes it with the offline
  token. When Keycloak **cannot be reached**, or answers with an error of its own (a 503 while it starts, a proxy's
  502), the session is kept and the refresh is retried on the next call that needs a token, so a stopped Keycloak
  container no longer logs you out. What needs the backend meanwhile says that Keycloak isn't answering.
- The session is dropped only when Keycloak answers and refuses the token (a 400 or 401), when the realm in
  `AMP/keycloak.json` changed, or, without asking Keycloak, once its refresh token's own expiry (`refreshExpiresAt`) has passed. The
  access token counts as expired 30 seconds early, so it is refreshed before a call can fail on it.
- **Log out**, under Account in Settings, ends the Keycloak session and deletes `AMP/session.json`.

The realm and client settings this relies on (direct access grants, standard flow, the `offline_access` role the
realm gives new users, redirect URIs) are listed in [Keycloak setup](authentication.md).

## Where the session is used

- Account in Settings names you. The window header has no account.
- **Publish** on the Models page uploads under your account. It reads *Publish (sign in)* for a guest, and pressing it
  goes to Account in Settings.
- The [backup](settings.md#backup), under Account in Settings, and the [package repository](models-page.md#repository),
  on the Models page's Packages tab, are only available signed in.
- The device dashboard (not reachable from the sidebar at the moment) stores devices on the backend per user.
- Every call to the Amie backend carries the Keycloak access token as a bearer token.
