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
