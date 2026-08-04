# org-ietf-imap

A genuine IMAP4rev1 (RFC 3501) client -- no `curl` shell-out, no
`jakarta.mail`. Zero-dep `.cljc`, an injectable transport for testing, real
`SSLSocket` I/O by default.

**Name provenance**: follows this org's `org-<standards-body>-<spec>` naming
convention (see `org-ietf-turn`, `org-ietf-ical`, `org-w3-aria`) -- IMAP is
an IETF specification (RFC 3501), hence `org-ietf-imap`.

## Why this exists

`gftdcojp/local-manimani`'s email channel (ADR-0022) fetched mail by
shelling out to `curl --url imaps://...`, deliberately avoiding a real IMAP
client to stay dependency-free. That worked for "search UNSEEN, fetch a
few headers" but had no way to mark a message read after a decision was
made on it (a gap ADR-0022 itself documented) and can't be reused by any
other project without re-deriving the same curl incantations. This library
is a real, portable, independently-tested IMAP client -- fill the same
"one tested boundary instead of ad hoc shell-outs" role `com-cloudflare`/
`com-gmail` play for their protocols, for IMAP specifically. It is
deliberately **not** Gmail-specific: any IMAP account works, and a
Gmail-via-REST-API integration should use `kotoba-lang/com-gmail` instead
(see that library's README) rather than this one -- they're separate
channels, not alternatives for the same job.

## Design

```text
imap.transport -- Transport protocol (write!/read-line!/read-n!/close!) + real SSLSocket impl (JVM-only)
imap.protocol  -- pure command construction + response parsing (tags, literals, SEARCH, header blocks)
imap.client    -- the session driver: connect!/login!/select!/search-unseen!/fetch-header!/mark-seen!/logout!
```

`imap.protocol` has zero I/O -- every command-building and response-parsing
function is pure and tested without a socket. `imap.client` drives the
read-until-tagged-completion loop over an injected `Transport`, so it's
tested the same way (`test/imap/fake_transport.cljc`, a scripted in-memory
`Transport`) -- never only against a live server.

**Scope, deliberately narrow**: LOGIN (plaintext user/pass, e.g. an app
password), SELECT, UID SEARCH (any criteria), UID FETCH of a header-fields
literal *or of a whole message*, UID STORE flags both ways, LOGOUT. It does
not implement IDLE, MIME multipart parsing, SASL mechanisms beyond plaintext
LOGIN, or arbitrary FETCH data items.

Whole-message reads (`fetch-message!`, `list-recent!`) were added for
`cloud-itonami/cloud-itonami-app`, which displays a mailbox rather than
triaging one: `fetch-header!` answers who a message is from and what it is
about, and never what it says. `fetch-message!` returns `:headers`, the raw
`:body`, and `:text` — the body with its `Content-Transfer-Encoding` undone
(quoted-printable and base64, decoded over the whole byte sequence so
multi-byte characters survive).

**A multipart body is still returned raw**, boundaries and all. Selecting
the text part out of a multipart tree is MIME parsing, which is on the
not-implemented list above, and returning the first part would be doing it
badly rather than not doing it.

## Usage

```clojure
(require '[imap.client :as client])

(def session (client/connect! "imap.gmail.com"))
(client/login! session "you@gmail.com" "app-password")
(client/select! session "INBOX")

(client/list-unseen-headers! session {:limit 20})
;; => [{:from "..." :subject "..." :date "..." :message-id "..." :uid 5} ...]

;; Displaying a mailbox rather than triaging one: the newest N messages,
;; bodies included, oldest of those first.
(client/list-recent! session {:limit 40})
;; => [{:uid 5 :headers {:from "..." :subject "..."} :body "..." :text "..." :raw "..."} ...]

(client/fetch-message! session 5)          ; one whole message, BODY.PEEK
(client/search! session "SINCE 1-Jan-2026") ; any RFC 3501 search key

(client/mark-seen! session 5)   ; after a decision has actually been made on message 5
(client/mark-unseen! session 5) ; and back again
(client/logout! session)
```

## Tests

```sh
clojure -M:test
```

No live server or network access required -- every `imap.client` test
injects `imap.fake-transport`, a scripted in-memory `Transport`.
