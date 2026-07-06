(ns imap.protocol
  "Pure IMAP4rev1 (RFC 3501) command construction and response parsing --
  no I/O here. `imap.transport` is the wire, `imap.client` drives the
  request/response loop; this namespace only turns data into command
  strings and turns response lines back into data, so it's testable
  without a socket at all.

  Scope is deliberately narrow: enough to log in, select a mailbox,
  UID SEARCH, UID FETCH a header-fields literal, UID STORE flags, and log
  out -- the exact shape `imap.client`'s convenience fns need. It does not
  parse full MIME bodies, IDLE, or general-purpose FETCH data items beyond
  a single header-fields literal per response."
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
