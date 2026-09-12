# FE (frontend) — React

> For a map of this module rather than its details, see
> [`docs/frontend.md`](../docs/frontend.md).

A React + TypeScript (Vite) web client replacing the JavaFX desktop UI in
[`../legacy`](../legacy), talking to [`../BE`](../BE) (Spring Boot) over REST
(auth) + WebSocket (chat). The visual design — colors, layout, copy — is
ported from the legacy JavaFX client on purpose; only the implementation
changed. See "Design system" below for how that's kept in sync.

## Status

Auth flow (login/register/forgot-password) is implemented end to end against
`BE`'s REST contract and gated by a token-based protected route.

The chat screen is ported from `legacy/Main.java`'s `openChat()`: left panel
with Chats (search, global room, online users, DM and group list with unread
badges) / Friends (pending requests, friend list) / Settings (Profile,
Privacy, Social, Danger Zone) tabs, a chat column with message bubbles themed
per the active bubble/background theme, and an "⋯" appearance overlay for
picking background/bubble/UI themes. `src/chat/useChat.ts` is a single hook
wrapping the `/ws` WebSocket connection and the whole client→server/
server→client protocol (see `src/chat/types.ts` and `src/chat/socket.ts`).

**Group chats** go beyond what `legacy/` had at all: `NewGroupOverlay.tsx`
creates one from your friend list, `GroupHeaderMenu.tsx` adds members,
renames and leaves, and the sidebar group list is re-pushed by the BE on
every membership change — see `../BE/README.md`'s "Group chats" for the
protocol and the permission model.

`ChatSocket` (`src/chat/socket.ts`) reconnects on drop by itself:
exponential backoff from 1s to a 30s ceiling, jittered so a restarted BE
doesn't get every client back in lockstep, and it gives up (firing
`onAuthFailed`) only when the token's own `exp` says the session is
actually dead — a bare close event isn't proof of that, since an expired
token, a BE that's down, and a laptop waking from sleep all look identical
to the browser (no `onopen`, code 1006). `useChat` re-syncs state after a
successful reconnect; `useChat.test.ts` covers that path.

Still not implemented: the "coming in a future update" items legacy itself
already punts on (custom avatar upload, last-seen).

## Run

```powershell
npm install
npm run dev
```

Dev server runs on `:5173` and proxies `/api` + `/ws` to `http://localhost:5000`
(see `vite.config.ts`), so `../BE` needs to be running locally for anything
past the client-side form validation to work — see `../BE/README.md` for how
to start it (needs `DB_PASSWORD` + `AUTH_TOKEN_SECRET`, and a Postgres
instance).

## Design system

`src/theme/tokens.css` ports the base color palette (`LILAC_*`/`SKY_*`, text
colors, soft success/danger colors) from `legacy/ChatTheme.java` by hand as
CSS custom properties — this is a known duplication risk flagged in
`../BE/README.md`, keep the two in sync manually if either changes.

The *catalogs* of selectable bubble/background/UI themes (the in-chat
appearance picker) are **not** hardcoded here — `src/theme/useThemeCatalog.ts`
fetches them once at startup from `GET /api/themes`, served by
`BE/web/ThemeController.java` directly off `ChatTheme.java`'s
`ALL_BUBBLE_THEMES`/`ALL_BACKGROUND_THEMES`/`ALL_UI_THEMES`.

## Auth contract

Matches `BE/web/AuthController.java` exactly (`src/api/auth.ts`):

| Endpoint | Body | Success |
|---|---|---|
| `POST /api/auth/register` | `{username, password, securityQuestion, securityAnswer}` | `200` |
| `POST /api/auth/login` | `{username, password}` | `200 {token, username}` |
| `POST /api/auth/reset/question` | `{username}` | `200 {question}` |
| `POST /api/auth/reset/verify` | `{username, answer, newPassword}` | `200` |

Failures are always `{error: string}` (see `src/api/client.ts`'s `ApiError`).
The token is stored in `localStorage` (`src/auth/AuthContext.tsx`) and handed
to the WebSocket as its subprotocol (`new WebSocket(url, [token])`) rather
than on the URL, which keeps it out of proxy access logs — see
`../BE/README.md`'s "Auth: REST, not WebSocket" and "The token travels as a
subprotocol, not in the URL" for why. On `username_changed` (renaming from
Settings → Profile), `useChat` calls back into `AuthContext` to swap in the
reissued token immediately, per that doc's warning that the old one stops
working on the next reconnect.

## WebSocket protocol

`src/chat/types.ts` documents the wire envelope (`WireMessage`) and room-key
convention (`"global"` / `"dm_<a>_<b>"` / `"group_<id>"`, the last built by
`groupRoomKey()`). `src/chat/useChat.ts` is the single source of truth for
every message type in both directions — mirrors
`BE/websocket/ClientHandler.java` exactly (message/dm/friend requests/
blocking/themes/profile/username changes/account deletion/group create-add-
rename-leave plus the `group_conversations` and `join_denied` pushes),
including folding `error`-type pushes (used for both failures and inline
confirmations like "✅ Friend request sent") into toast notices rather than
dropping them.

## Structure

```
src/
  api/        fetch wrappers for the REST auth contract
  auth/       token/session context + route guard
  chat/       WebSocket client + protocol types + the useChat hook
  components/ Avatar.tsx — SVG port of AvatarCatalog.java's 5 built-in avatars
  theme/      design tokens + theme catalog fetch, ported from ChatTheme.java
  pages/auth/ Login, Register, ForgotPassword — styled to match legacy/Main.java
  pages/chat/ ChatPage + LeftPanel tabs (Chats/Friends/Settings) + ChatArea
              + NewGroupOverlay / GroupHeaderMenu (group create & management)
```
