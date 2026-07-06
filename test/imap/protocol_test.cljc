(ns imap.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [imap.protocol :as p]))

(deftest command-builds-a-crlf-terminated-tagged-line
  (is (= "A1 LOGIN \"me\" \"secret\"\r\n" (p/command "A1" "LOGIN" (p/quote-string "me") (p/quote-string "secret"))))
  (is (= "A2 LOGOUT\r\n" (p/command "A2" "LOGOUT"))))

(deftest quote-string-escapes-backslashes-and-quotes
  (is (= "\"plain\"" (p/quote-string "plain")))
  (is (= "\"a\\\"b\"" (p/quote-string "a\"b")))
  (is (= "\"a\\\\b\"" (p/quote-string "a\\b"))))

(deftest tagged-completion-matches-only-its-own-tag
  (is (true? (p/tagged-completion? "A1" "A1 OK LOGIN completed")))
  (is (false? (p/tagged-completion? "A1" "A11 OK LOGIN completed")) "A11 must not match A1's prefix")
  (is (false? (p/tagged-completion? "A1" "* 3 EXISTS")))
  (is (false? (p/tagged-completion? "A1" "A1 something-not-a-status"))))

(deftest completion-status-parses-ok-no-bad
  (is (= {:status :ok :text "LOGIN completed"} (p/completion-status "A1" "A1 OK LOGIN completed")))
  (is (= {:status :no :text "[AUTHENTICATIONFAILED] bad credentials"}
         (p/completion-status "A1" "A1 NO [AUTHENTICATIONFAILED] bad credentials")))
  (is (= {:status :bad :text ""} (p/completion-status "A1" "A1 BAD")))
  (is (nil? (p/completion-status "A1" "A2 OK unrelated tag"))))

(deftest literal-size-reads-the-trailing-brace-marker
  (is (= 123 (p/literal-size "* 3 FETCH (UID 9 BODY[HEADER.FIELDS (FROM)] {123}")))
  (is (nil? (p/literal-size "* 3 EXISTS"))))

(deftest search-uids-parses-numbers-and-empty-results
  (is (= [1 2 42] (p/search-uids "* SEARCH 1 2 42")))
  (is (= [] (p/search-uids "* SEARCH")))
  (is (nil? (p/search-uids "* 3 EXISTS"))))

(deftest parse-header-block-extracts-known-fields-case-insensitively
  (let [block "From: a@example.com\r\nSubject: hi\r\nDate: Mon, 1 Jan 2026 00:00:00 +0000\r\nMessage-ID: <1@x>\r\n\r\n"]
    (is (= {:from "a@example.com" :subject "hi"
           :date "Mon, 1 Jan 2026 00:00:00 +0000" :message-id "<1@x>"}
           (p/parse-header-block block)))))

(deftest parse-header-block-unfolds-continuation-lines
  (testing "a folded Subject (continuation line starting with whitespace) is joined with a space"
    (let [block "Subject: line one\r\n line two\r\n\r\n"]
      (is (= "line one line two" (:subject (p/parse-header-block block)))))))
