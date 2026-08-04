(ns imap.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [imap.client :as client]
            [imap.fake-transport :as fake]))

(deftest connect-reads-the-greeting-and-throws-on-a-bad-one
  (let [{:keys [transport]} (fake/make ["* OK IMAP4rev1 Service Ready"])]
    (is (some? (:transport (client/connect! "imap.example.com" {:transport transport})))))
  (let [{:keys [transport]} (fake/make ["* BAD not a greeting"])]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"greeting"
         (client/connect! "imap.example.com" {:transport transport})))))

(deftest full-session-happy-path
  (let [script ["* OK IMAP4rev1 Service Ready"                          ; greeting
               "A1 OK LOGIN completed"                                  ; login!
               "* 3 EXISTS" "* 0 RECENT"                                ; select! untagged noise
               "A2 OK [READ-WRITE] SELECT completed"
               "* SEARCH 5 7"                                           ; search-unseen!
               "A3 OK UID SEARCH completed"
               "* 1 FETCH (UID 5 BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)] {58}"
               {:literal "From: a@example.com\r\nSubject: hi\r\nMessage-ID: <1@x>\r\n"}
               ")"
               "A4 OK UID FETCH completed"                              ; fetch-header! uid 5
               "* 1 FETCH (UID 7 BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)] {30}"
               {:literal "From: b@example.com\r\nSubject: yo\r\n"}
               ")"
               "A5 OK UID FETCH completed"                              ; fetch-header! uid 7
               "A6 OK UID STORE completed"                              ; mark-seen!
               "* BYE IMAP4rev1 Server logging out"
               "A7 OK LOGOUT completed"]                                ; logout!
        {:keys [transport written]} (fake/make script)
        session (client/connect! "imap.example.com" {:transport transport})]
    (client/login! session "user@example.com" "app-password")
    (client/select! session "INBOX")
    (let [uids (client/search-unseen! session)]
      (is (= [5 7] uids))
      (let [headers (mapv #(client/fetch-header! session %) uids)]
        (is (= [{:from "a@example.com" :subject "hi" :message-id "<1@x>"}
               {:from "b@example.com" :subject "yo"}]
               headers))))
    (is (true? (client/mark-seen! session 5)))
    (is (nil? (client/logout! session)))
    (testing "the exact wire commands sent, in order"
      (is (= ["A1 LOGIN \"user@example.com\" \"app-password\"\r\n"
             "A2 SELECT \"INBOX\"\r\n"
             "A3 UID SEARCH UNSEEN\r\n"
             "A4 UID FETCH 5 (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)])\r\n"
             "A5 UID FETCH 7 (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)])\r\n"
             "A6 UID STORE 5 +FLAGS.SILENT (\\Seen)\r\n"
             "A7 LOGOUT\r\n"
             :closed]
             @written)))))

(deftest list-unseen-headers-combines-search-and-fetch-with-uid-attached
  (let [script ["* OK ready"
               "A1 OK LOGIN completed"
               "* SEARCH 9"
               "A2 OK UID SEARCH completed"
               "* 1 FETCH (UID 9 BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)] {20}"
               {:literal "From: c@example.com\r\n"}
               ")"
               "A3 OK UID FETCH completed"]
        {:keys [transport]} (fake/make script)
        session (client/connect! "imap.example.com" {:transport transport})]
    (client/login! session "u" "p")
    (is (= [{:from "c@example.com" :uid 9}] (client/list-unseen-headers! session)))))

(deftest login-failure-throws-with-the-servers-text
  (let [{:keys [transport]} (fake/make ["* OK ready" "A1 NO [AUTHENTICATIONFAILED] bad credentials"])
        session (client/connect! "imap.example.com" {:transport transport})]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"LOGIN failed"
         (client/login! session "u" "wrong")))))

;; --- whole-message reads ---------------------------------------------------

(deftest fetch-message-returns-headers-body-and-decoded-text
  (let [raw (str "From: a@example.com\r\nSubject: hi\r\n"
                 "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                 "caf=C3=A9\r\n")
        script ["* OK ready"
                (str "* 1 FETCH (UID 5 BODY[] {" (count raw) "}")
                {:literal raw}
                ")"
                "A1 OK UID FETCH completed"]
        {:keys [transport written]} (fake/make script)
        session (client/connect! "imap.example.com" {:transport transport})
        message (client/fetch-message! session 5)]
    (is (= 5 (:uid message)))
    (is (= "a@example.com" (get-in message [:headers :from])))
    (is (= "café\n" (:text message)) "the body with its transfer encoding undone")
    (is (= raw (:raw message)) "what the server actually sent is kept too")
    (testing "BODY.PEEK, so reading a message does not silently mark it read"
      (is (= ["A1 UID FETCH 5 (BODY.PEEK[])\r\n"] @written)))))

(deftest list-recent-takes-the-newest-uids-and-fetches-those
  (let [body-for (fn [n] (str "Subject: m" n "\r\n\r\nbody" n "\r\n"))
        script (concat
                ["* OK ready"
                 "* SEARCH 1 2 3 4"
                 "A1 OK UID SEARCH completed"]
                (mapcat (fn [uid tag]
                          [(str "* 1 FETCH (UID " uid " BODY[] {" (count (body-for uid)) "}")
                           {:literal (body-for uid)}
                           ")"
                           (str tag " OK UID FETCH completed")])
                        [3 4] ["A2" "A3"]))
        {:keys [transport written]} (fake/make script)
        session (client/connect! "imap.example.com" {:transport transport})
        messages (client/list-recent! session {:limit 2})]
    (testing "the newest two by UID, oldest of those first"
      (is (= [3 4] (mapv :uid messages)))
      (is (= ["body3\n" "body4\n"] (mapv :text messages))))
    (testing "the UID list is unbounded but only :limit messages are fetched"
      (is (= ["A1 UID SEARCH ALL\r\n"
              "A2 UID FETCH 3 (BODY.PEEK[])\r\n"
              "A3 UID FETCH 4 (BODY.PEEK[])\r\n"]
             @written)))))

(deftest list-recent-headers-only-skips-the-body-fetch
  (let [header "From: a@example.com\r\n"
        script ["* OK ready"
                "* SEARCH 9"
                "A1 OK UID SEARCH completed"
                (str "* 1 FETCH (UID 9 BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)] {"
                     (count header) "}")
                {:literal header}
                ")"
                "A2 OK UID FETCH completed"]
        {:keys [transport written]} (fake/make script)
        session (client/connect! "imap.example.com" {:transport transport})]
    (is (= [{:from "a@example.com" :uid 9}]
           (client/list-recent! session {:headers-only? true})))
    (is (= "A2 UID FETCH 9 (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)])\r\n"
           (second @written)))))

(deftest search-passes-criteria-through-verbatim
  (let [{:keys [transport written]} (fake/make ["* OK ready" "* SEARCH" "A1 OK UID SEARCH completed"])
        session (client/connect! "imap.example.com" {:transport transport})]
    (is (= [] (client/search! session "SINCE 1-Jan-2026")))
    (is (= ["A1 UID SEARCH SINCE 1-Jan-2026\r\n"] @written))))

(deftest mark-unseen-clears-the-flag-mark-seen-sets
  (let [{:keys [transport written]} (fake/make ["* OK ready" "A1 OK UID STORE completed"])
        session (client/connect! "imap.example.com" {:transport transport})]
    (is (true? (client/mark-unseen! session 5)))
    (is (= ["A1 UID STORE 5 -FLAGS.SILENT (\\Seen)\r\n"] @written))))
