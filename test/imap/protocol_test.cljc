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

;; --- whole-message reads (fetch-message! / list-recent!) --------------------

(deftest split-message-separates-headers-from-body-at-the-first-blank-line
  (let [raw "From: a@example.com\r\nSubject: hi\r\n\r\nthe body\r\nsecond line\r\n"
        {:keys [headers body]} (p/split-message raw)]
    (is (= {:from "a@example.com" :subject "hi"} headers))
    (is (= "the body\nsecond line\n" body))))

(deftest split-message-treats-a-bodyless-message-as-an-empty-body
  (testing "no blank line means every line is a header, which is what a
            bodyless message is -- not a parse failure"
    (let [{:keys [headers body]} (p/split-message "From: a@example.com\r\nSubject: hi\r\n")]
      (is (= {:from "a@example.com" :subject "hi"} headers))
      (is (= "" body)))))

(deftest split-message-keeps-blank-lines-inside-the-body
  (testing "only the FIRST blank line splits: a body with paragraphs must not
            lose everything after its first one"
    (is (= "one\n\ntwo\n"
           (:body (p/split-message "Subject: s\r\n\r\none\r\n\r\ntwo\r\n"))))))

(deftest decode-body-undoes-quoted-printable-including-soft-line-breaks
  (is (= "日本語 text"
         (p/decode-body
          {:headers {:content-transfer-encoding "quoted-printable"}
           :body "=E6=97=A5=E6=9C=AC=E8=AA=9E te=\nxt"}))))

#?(:clj
(deftest decode-body-undoes-base64
  (is (= "hello" (p/decode-body {:headers {:content-transfer-encoding "base64"}
                                 :body "aGVsbG8="})))))

(deftest decode-body-passes-through-unencoded-and-unknown-encodings
  (is (= "plain" (p/decode-body {:headers {} :body "plain"})))
  (is (= "plain" (p/decode-body {:headers {:content-transfer-encoding "7bit"}
                                 :body "plain"}))))

(deftest decode-body-leaves-multipart-raw-rather-than-half-parsing-it
  (testing "picking the text part out of a multipart tree is MIME parsing,
            which this library says it does not do -- returning the first part
            would be doing it badly rather than not doing it"
    (let [body "--b\r\nContent-Type: text/plain\r\n\r\nhi\r\n--b--\r\n"]
      (is (= body (p/decode-body
                   {:headers {:content-type "multipart/alternative; boundary=b"
                              :content-transfer-encoding "7bit"}
                    :body body}))))))

(deftest content-type-drops-parameters-and-normalises-case
  (is (= "text/plain" (p/content-type {:content-type "Text/Plain; charset=\"UTF-8\""})))
  (is (nil? (p/content-type {}))))
