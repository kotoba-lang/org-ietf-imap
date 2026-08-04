(ns imap.protocol
  "Pure IMAP4rev1 (RFC 3501) command construction and response parsing --
  no I/O here. `imap.transport` is the wire, `imap.client` drives the
  request/response loop; this namespace only turns data into command
  strings and turns response lines back into data, so it's testable
  without a socket at all.

  Scope is deliberately narrow: enough to log in, select a mailbox,
  UID SEARCH, UID FETCH a header-fields literal or a whole message, UID
  STORE flags, and log out -- the exact shape `imap.client`'s convenience
  fns need. It does not implement IDLE or general-purpose FETCH data items
  beyond one literal per response.

  It parses a fetched message far enough to separate the header block from
  the body and to read a single-part body's transfer encoding -- which is
  what a caller showing a message needs and is not the same as parsing
  MIME. `decode-body` says out loud where that line is: a multipart message
  comes back as its raw parts, because picking one out of them is MIME
  parsing and this library still does not do that."
  (:require [clojure.string :as str]))

(defn command
  "One tagged command line (CRLF-terminated) for `tag` (e.g. \"A1\")."
  [tag verb & args]
  (str tag " " verb (when (seq args) (str " " (str/join " " args))) "\r\n"))

(defn quote-string
  "IMAP quoted string: wrap in double quotes, escaping embedded quotes and
  backslashes (RFC 3501 section 4.3)."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(def ^:private statuses #{"OK" "NO" "BAD"})

(defn- after-tag
  "The rest of `line` after \"<tag> \", or nil if `line` doesn't start with
  that exact tag (plain string match -- IMAP tags are caller-generated
  alphanumeric atoms, never regex metacharacters, so no escaping is
  needed)."
  [tag line]
  (let [prefix (str tag " ")]
    (when (str/starts-with? (str line) prefix)
      (subs (str line) (count prefix)))))

(defn tagged-completion?
  "True if `line` is the tagged completion for `tag` (\"<tag> OK/NO/BAD ...\")."
  [tag line]
  (boolean (when-let [rest-line (after-tag tag line)]
             (contains? statuses (str/upper-case (first (str/split rest-line #"\s" 2)))))))

(defn completion-status
  "Parse a tagged completion line into {:status :ok|:no|:bad :text \"...\"}."
  [tag line]
  (when-let [rest-line (after-tag tag line)]
    (let [[status text] (str/split rest-line #"\s" 2)]
      (when (contains? statuses (str/upper-case status))
        {:status (keyword (str/lower-case status)) :text (or text "")}))))

(defn literal-size
  "The byte count `n` if `line` ends with a literal marker \"{n}\", else nil."
  [line]
  (when-let [[_ n] (re-find #"\{(\d+)\}\s*$" (str line))]
    (parse-long n)))

(defn search-uids
  "Parse a \"* SEARCH <n1> <n2> ...\" untagged line into a vector of longs,
  or nil if `line` isn't a SEARCH response (including an empty result,
  \"* SEARCH\" with no numbers, which parses to [])."
  [line]
  (when-let [[_ nums] (re-matches #"\* SEARCH(.*)" (str line))]
    (->> (str/split (str/trim nums) #"\s+") (remove str/blank?) (mapv parse-long))))

(defn parse-header-block
  "A raw RFC 2822 header block (an IMAP literal payload) -> a map keyed by
  lower-cased field name (e.g. :from :subject :date :message-id), value
  trimmed. Folded (continuation) lines are unfolded first; unknown fields
  are ignored, not just the four this library's `imap.client` requests by
  default -- callers can pass any field list through."
  [text]
  (let [unfolded (str/replace (str text) #"\r\n[ \t]+" " ")
        lines (remove str/blank? (str/split-lines unfolded))]
    (reduce (fn [acc line]
              (if-let [[_ k v] (re-matches #"([!-9;-~]+):\s*(.*)" line)]
                (assoc acc (keyword (str/lower-case k)) (str/trim v))
                acc))
            {} lines)))

(defn split-message
  "A whole RFC 2822 message -> `{:headers {...} :body \"...\"}`.

  The split is the first blank line, which is what separates a header block
  from a body in RFC 2822 and is the one piece of message structure a caller
  fetching `BODY.PEEK[]` cannot avoid needing. A message with no blank line
  is all headers and an empty body, which is what a bodyless message
  actually is rather than an error."
  [raw]
  (let [text (str/replace (str raw) #"\r\n" "\n")
        [head body] (str/split text #"\n\n" 2)]
    {:headers (parse-header-block (str/replace (str head) #"\n" "\r\n"))
     :body (or body "")}))

(defn- qp-bytes
  "Quoted-printable text -> the byte values it encodes.

  Bytes, not characters. A `=XX` escape is one *byte*, and a non-ASCII
  character is several of them in a row (`=E6=97=A5` is the three UTF-8
  bytes of 日) -- so turning each escape into a character as it is met
  produces one Latin-1 character per byte and mangles every multi-byte
  character in the message. The decode has to happen over the whole byte
  sequence at once, which is what this hands to the caller."
  [text]
  (let [;; Soft line breaks first: `=` at end of line means "this line
        ;; continues", and decoding `=XX` before removing them would read
        ;; the newline that follows as part of a hex pair.
        text (str/replace (str text) #"=\r?\n" "")
        n (count text)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (nth text i)]
          (if (and (= \= c) (<= (+ i 3) n)
                   (re-matches #"[0-9A-Fa-f]{2}" (subs text (inc i) (+ i 3))))
            (recur (+ i 3)
                   (conj! out (#?(:clj Long/parseLong :cljs js/parseInt)
                               (subs text (inc i) (+ i 3)) 16)))
            (recur (inc i) (conj! out (int c)))))))))

(defn- decode-quoted-printable [text]
  (let [bytes (qp-bytes text)]
    #?(:clj (String. (byte-array (map unchecked-byte bytes)) "UTF-8")
       :cljs (.decode (js/TextDecoder. "utf-8")
                      (js/Uint8Array.from (clj->js bytes))))))

#?(:clj
(defn- decode-base64 [text]
  (try
    (String. (.decode (java.util.Base64/getMimeDecoder) ^String (str text))
             "UTF-8")
    ;; Undecodable base64 is not worth losing the message over: a caller
    ;; showing mail would rather see the raw text than an exception.
    (catch Exception _ (str text)))))

(defn content-type
  "The `Content-Type` header's media type, lower-cased, without parameters."
  [headers]
  (some-> (:content-type headers)
          (str/split #";") first str/trim str/lower-case not-empty))

(defn decode-body
  "A message body as text, undoing its `Content-Transfer-Encoding`.

  Handles the two encodings that actually carry non-ASCII mail --
  quoted-printable and base64 -- and passes anything else through, which is
  correct for 7bit/8bit/binary.

  **Multipart is deliberately not handled**: a `multipart/*` body is
  returned as its raw parts, boundaries and all. Selecting the text part out
  of a multipart tree is MIME parsing, this library says in its own docstring
  that it does not do that, and a version of this that quietly returned the
  first part would be doing it badly rather than not doing it."
  [{:keys [headers body]}]
  (let [encoding (some-> (:content-transfer-encoding headers)
                         str/trim str/lower-case)]
    (cond
      (some-> (content-type headers) (str/starts-with? "multipart/")) body
      (= "quoted-printable" encoding) (decode-quoted-printable body)
      #?@(:clj [(= "base64" encoding) (decode-base64 body)])
      :else body)))
