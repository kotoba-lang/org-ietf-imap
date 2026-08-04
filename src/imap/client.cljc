(ns imap.client
  "IMAP4rev1 (RFC 3501) session driver: connect!/login!/select!/
  search-unseen!/fetch-header!/mark-seen!/logout! over an `imap.transport/
  Transport`. `imap.protocol` supplies the pure command/response
  functions; this namespace is the stateful loop that reads lines (and any
  literal a response carries) until a command's tagged completion arrives."
  (:require [clojure.string :as str]
            [imap.protocol :as p]
            [imap.transport :as t]))

#?(:clj
(defn connect!
  "Open the transport (real TLS unless `:transport` is given, e.g. a test
  fake) and read the server greeting. Throws if the greeting isn't
  \"* OK ...\". Returns a session map; every other fn in this namespace
  takes and returns this session."
  ([host] (connect! host {}))
  ([host opts]
   (let [transport (or (:transport opts) (t/tls-connect (assoc opts :host host)))
         greeting (t/read-line! transport)]
     (when-not (str/starts-with? (str greeting) "* OK")
       (t/close! transport)
       (throw (ex-info "IMAP greeting was not * OK" {:greeting greeting})))
     {:transport transport :tag-counter (atom 0)}))))

(defn- tag! [{:keys [tag-counter]}] (str "A" (swap! tag-counter inc)))

(defn- run-command!
  "Send one tagged command; collect untagged lines until the tagged
  completion. Handles at most one literal per response (this library's own
  requests -- UID FETCH of a single header-fields item -- never provoke
  more than one). Returns {:completion {:status :text} :lines [...]
  :literal <string-or-nil>}."
  [{:keys [transport] :as session} verb & args]
  (let [tag (tag! session)]
    (t/write! transport (apply p/command tag verb args))
    (loop [lines [] literal nil]
      (let [line (t/read-line! transport)]
        (cond
          (nil? line)
          (throw (ex-info "IMAP connection closed mid-response" {:tag tag :verb verb :lines lines}))

          (p/tagged-completion? tag line)
          {:completion (p/completion-status tag line) :lines lines :literal literal}

          (p/literal-size line)
          (recur lines (t/read-n! transport (p/literal-size line)))

          :else (recur (conj lines line) literal))))))

(defn- assert-ok! [{:keys [completion] :as resp} verb]
  (when-not (= :ok (:status completion))
    (throw (ex-info (str "IMAP " verb " failed: " (:text completion)) (assoc resp :verb verb))))
  resp)

(defn login!
  "LOGIN with a plaintext user/pass (an app password for Gmail-style
  accounts, per this library's TLS-only transport). Returns `session`."
  [session user pass]
  (assert-ok! (run-command! session "LOGIN" (p/quote-string user) (p/quote-string pass)) "LOGIN")
  session)

(defn select!
  "SELECT `mailbox` (e.g. \"INBOX\"). Returns `session`."
  [session mailbox]
  (assert-ok! (run-command! session "SELECT" (p/quote-string mailbox)) "SELECT")
  session)

(defn search!
  "UID SEARCH `criteria` -> a vector of UIDs (longs), possibly empty.

  `criteria` is raw IMAP search-key text (\"ALL\", \"UNSEEN\",
  \"SINCE 1-Jan-2026\", \"FROM \\\"a@b\\\"\"). Passed through rather than
  modelled: RFC 3501's search grammar is large, and a partial model of it
  would be a vocabulary a caller has to learn *and* work around."
  [session criteria]
  (let [resp (assert-ok! (run-command! session "UID SEARCH" criteria) "UID SEARCH")]
    (or (some p/search-uids (:lines resp)) [])))

(defn search-unseen!
  "UID SEARCH UNSEEN -> a vector of UIDs (longs), possibly empty."
  [session]
  (search! session "UNSEEN"))

(defn search-all!
  "UID SEARCH ALL -> every UID in the selected mailbox, ascending.

  The whole mailbox rather than a page of it, because IMAP has no cursor:
  UID SEARCH is how a client learns what exists, and the paging happens
  afterwards on the returned UIDs (see `list-recent!`, which takes the tail).
  This is a list of integers, not of messages -- the expensive call is the
  per-UID fetch that follows, and that one is bounded."
  [session]
  (search! session "ALL"))

(defn fetch-header!
  "UID FETCH `uid`'s header, limited to `fields` (default FROM SUBJECT
  DATE MESSAGE-ID) -> a map keyed by lower-cased field name (see
  `imap.protocol/parse-header-block`). Uses BODY.PEEK so the message's
  \\Seen flag is untouched -- see `mark-seen!` to set it explicitly once a
  decision has actually been made."
  ([session uid] (fetch-header! session uid ["FROM" "SUBJECT" "DATE" "MESSAGE-ID"]))
  ([session uid fields]
   (let [resp (assert-ok! (run-command! session "UID FETCH" (str uid)
                                        (str "(BODY.PEEK[HEADER.FIELDS (" (str/join " " fields) ")])"))
                          "UID FETCH")]
     (p/parse-header-block (or (:literal resp) "")))))

(defn list-unseen-headers!
  "The convenience most callers want: every unseen message's header
  (up to `:limit`, default 50), each with its `:uid` attached."
  ([session] (list-unseen-headers! session {}))
  ([session {:keys [limit fields] :or {limit 50}}]
   (mapv (fn [uid] (assoc (if fields (fetch-header! session uid fields) (fetch-header! session uid))
                          :uid uid))
         (take limit (search-unseen! session)))))

(defn fetch-message!
  "UID FETCH `uid`'s whole message -> `{:uid :headers {...} :body \"...\"
  :text \"...\" :raw \"...\"}`.

  `:body` is the body as it arrived; `:text` is that body with its
  `Content-Transfer-Encoding` undone (see `imap.protocol/decode-body`,
  including what it does *not* do for multipart). `:raw` is kept because a
  caller archiving the message wants what the server actually sent, not this
  library's reading of it.

  BODY.PEEK, like `fetch-header!`, so reading a message does not silently
  mark it read -- that stays an explicit `mark-seen!`. This is the call that
  lets a mailbox be displayed rather than merely triaged: `fetch-header!`
  answers who and what about, and never what it says."
  [session uid]
  (let [resp (assert-ok! (run-command! session "UID FETCH" (str uid)
                                       "(BODY.PEEK[])")
                         "UID FETCH")
        raw (or (:literal resp) "")
        parsed (p/split-message raw)]
    (assoc parsed :uid uid :raw raw :text (p/decode-body parsed))))

(defn list-recent!
  "The newest `:limit` messages in the selected mailbox, oldest first.

  Newest by UID, which in IMAP ascends with arrival, so the tail of
  `search-all!` is the recent end of the mailbox without a date search. The
  fetch is per-UID and therefore bounded by `:limit` (default 50) -- the
  unbounded part is the UID list, which is integers.

  `:headers-only?` fetches header fields instead of whole messages, for a
  caller building a list view that will fetch bodies on demand."
  ([session] (list-recent! session {}))
  ([session {:keys [limit headers-only?] :or {limit 50}}]
   (let [uids (take-last limit (search-all! session))]
     (mapv (fn [uid]
             (if headers-only?
               (assoc (fetch-header! session uid) :uid uid)
               (fetch-message! session uid)))
           uids))))

(defn mark-seen!
  "UID STORE `uid` +FLAGS.SILENT (\\Seen) -- the ADR-0022 gap this library
  closes: curl-imap-fetch had no way to mark a message read after a
  decision was made on it."
  [session uid]
  (assert-ok! (run-command! session "UID STORE" (str uid) "+FLAGS.SILENT" "(\\Seen)") "UID STORE")
  true)

(defn mark-unseen!
  "UID STORE `uid` -FLAGS.SILENT (\\Seen) -- the inverse of `mark-seen!`.

  Present because a mark that cannot be taken off is a trap: an interface
  offering \"mark as unread\" over a client with only `mark-seen!` has to
  either lie or keep the difference locally, where the server never learns
  it and the next client to open the mailbox disagrees."
  [session uid]
  (assert-ok! (run-command! session "UID STORE" (str uid) "-FLAGS.SILENT" "(\\Seen)") "UID STORE")
  true)

(defn logout!
  "LOGOUT and close the transport. Swallows a failed LOGOUT response (the
  transport is closed either way) but not a transport-level exception
  raised before that point."
  [{:keys [transport] :as session}]
  (try (run-command! session "LOGOUT") (catch #?(:clj Exception :cljs :default) _ nil))
  (t/close! transport)
  nil)
