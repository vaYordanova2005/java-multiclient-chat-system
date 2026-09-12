# Frontend

React 19 + TypeScript, built with Vite. A single-page app: three auth
screens and one chat screen, everything else is state inside that chat
screen.

[`FE/README.md`](../FE/README.md) is the authoritative document for the
design system and the protocol mirror — this page is the map.

## Structure

```
src/
├── main.tsx / App.tsx        routes + providers
├── api/
│   ├── client.ts             fetch wrapper, ApiError, apiBase()
│   └── auth.ts               the four REST auth calls
├── auth/
│   ├── AuthContext.tsx       token + username in localStorage
│   ├── RequireAuth.tsx       route guard
│   └── token.ts              decodes the token's exp (no verification)
├── chat/
│   ├── socket.ts             ChatSocket — connect, reconnect, backoff
│   ├── types.ts              WireMessage + room-key helpers
│   ├── useChat.ts            THE hook: all protocol state, both directions
│   └── useChat.test.ts       14 tests over the tricky parts
├── components/Avatar.tsx     SVG port of the 5 built-in avatars
├── theme/
│   ├── tokens.css            base palette, hand-ported from ChatTheme.java
│   ├── catalog.ts            types for the catalog
│   └── useThemeCatalog.ts    fetches GET /api/themes at startup
├── pages/auth/               Login, Register, ForgotPassword
└── pages/chat/               ChatPage + tabs + overlays (see below)
```

### Routes

| Path | Screen | Guard |
|---|---|---|
| `/login`, `/register`, `/forgot-password` | auth screens | — |
| `/chat` | the whole app | `RequireAuth` — no token, no entry |
| `/`, anything else | redirect | |

### The chat screen

`ChatPage` owns the layout: a left panel with three tabs and a chat column.

| Component | Role |
|---|---|
| `ChatsTab` | search, global room, online users, DM + group list with unread badges |
| `FriendsTab` | pending requests, friend list |
| `SettingsTab` | Profile, Privacy, Social, Danger Zone |
| `ChatArea` + `MessageRow` | the message list and bubbles |
| `AppearanceOverlay` | the "⋯" bubble/background/UI theme picker |
| `NewGroupOverlay` | create a group from your friend list |
| `GroupHeaderMenu` | add member / rename / leave |

## State: one hook

`useChat.ts` is the single source of truth for the chat protocol — every
message type in both directions, mirroring `ClientHandler.java`'s switch.
Components get state and callbacks from it; none of them touch the socket.

Non-obvious behaviors it owns, each covered by a test:

- **Message dedupe** — identical signatures are dropped, but two messages
  differing only by timestamp are kept (people do send the same word twice).
- **Stale room absorption** — a late reply for a room you already switched
  away from is discarded, so history from an abandoned room can't land in
  the one you're looking at now.
- **Reconnect resync** — after a reconnect it re-requests the active room
  and absorbs the BE's automatic `global` reload.
- **Friend-request reply correlation** — replies are matched to the right
  target by sequence when two requests overlap, instead of being attributed
  to whichever was most recent.
- **Theme seeding** — catalog defaults arriving after mount seed the theme,
  but never overwrite a preference the BE already sent.

```bash
cd FE && npm test
```

## The socket

`ChatSocket` (`socket.ts`) wraps one `WebSocket`:

- **Auth by subprotocol** — `new WebSocket(url, [token])`, so the token
  goes in a header rather than the URL (see
  [architecture.md](architecture.md#how-a-session-starts)).
- **Reconnect with backoff** — 1s doubling to a 30s ceiling, with jitter so
  a restarted BE doesn't get every client back in lockstep.
- **Knowing when to stop** — it gives up only when the token's own `exp`
  says the session is dead. A close event alone proves nothing: an expired
  token, a downed BE and a laptop waking from sleep all look identical to
  the browser (no `onopen`, code 1006).
- **Origin derivation** — the WS origin comes from the same
  `VITE_API_BASE_URL` the REST calls use, so a split deploy (FE and BE on
  different hosts, which is exactly what Render does) doesn't send socket
  traffic to the FE's own host.

## Theming

Two halves, on purpose:

- **Base palette** — `theme/tokens.css`, hand-ported from
  `ChatTheme.java` as CSS custom properties. This is a known duplication
  risk: if the Java side changes, this changes by hand.
- **Selectable catalogs** — the bubble/background/UI themes in the picker
  are **not** hardcoded. `useThemeCatalog.ts` fetches them once from
  `GET /api/themes`, served straight off `ChatTheme.java`. That's the ~300
  lines of color data that would otherwise drift.

## Build & run

```bash
cd FE && npm install && npm run dev
```

Dev runs on `:5173` and proxies `/api` and `/ws` to `localhost:5000`
(`vite.config.ts`), which is why local development needs no CORS or
`ALLOWED_ORIGIN_PATTERNS` setup — but does need `BE` running for anything
past client-side form validation.

| Command | What |
|---|---|
| `npm run dev` | Vite dev server, HMR |
| `npm test` | Vitest (jsdom) |
| `npm run lint` | oxlint |
| `npm run build` | `tsc -b` then `vite build` → `dist/` |

`npm run build` is also what Render runs for the static site; the type check
is part of it, so a type error fails the deploy rather than shipping.

## Not implemented

Reconnect-on-drop **is** implemented (above). What's still missing are the
items `legacy/` itself punted on: custom avatar upload and last-seen.
