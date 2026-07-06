(ns imap.transport
  "The wire boundary for IMAP4rev1 (RFC 3501): a 4-fn `Transport` protocol
  (write!/read-line!/read-n!/close!) that `imap.client` drives and every
  other namespace in this library is blind to. The real transport
  (`tls-connect`) is JVM-only (a raw `SSLSocket`, byte-counted so
  `read-n!` can satisfy IMAP literals exactly); tests inject a fake
  in-memory `Transport` instead, the same injection shape as
  `cloudflare.client`'s `:http-fn` / `gmail.client`'s `:http-fn`, adapted
  for a stateful line-oriented protocol instead of one-shot HTTP requests.")

(defprotocol Transport
  (write! [t s] "Write string `s` (already CRLF-terminated by the caller) to the wire.")
  (read-line! [t] "Read one CRLF-terminated line, without the terminator. nil on EOF.")
  (read-n! [t n] "Read exactly `n` bytes as a string -- for IMAP literals ({n} syntax).")
  (close! [t]))

#?(:clj
(deftype SocketTransport [^java.net.Socket socket
                          ^java.io.InputStream in
                          ^java.io.OutputStream out]
  Transport
  (write! [_ s]
    (.write out (.getBytes ^String s "UTF-8"))
    (.flush out))
  (read-line! [_]
    (let [buf (java.io.ByteArrayOutputStream.)]
      (loop []
        (let [b (.read in)]
          (cond
            (neg? b) (when (pos? (.size buf)) (.toString buf "UTF-8"))
            (= b 10) (let [bytes (.toByteArray buf)
                          len (alength bytes)]
                      (if (and (pos? len) (= (aget bytes (dec len)) (byte 13)))
                        (String. bytes 0 (dec len) "UTF-8")
                        (String. bytes 0 len "UTF-8")))
            :else (do (.write buf b) (recur)))))))
  (read-n! [_ n]
    (let [buf (byte-array n)]
      (loop [off 0]
        (when (< off n)
          (let [r (.read in buf off (int (- n off)))]
            (when (pos? r) (recur (+ off r))))))
      (String. buf "UTF-8")))
  (close! [_] (.close socket))))

#?(:clj
(defn tls-connect
  "Real transport: connect to `host`:`port` over TLS (default port 993,
  IMAPS). `timeout-ms` bounds each individual socket read."
  [{:keys [host port timeout-ms] :or {port 993 timeout-ms 20000}}]
  (let [factory (javax.net.ssl.SSLSocketFactory/getDefault)
        socket (.createSocket ^javax.net.ssl.SSLSocketFactory factory ^String host (int port))]
    (.setSoTimeout ^javax.net.ssl.SSLSocket socket (int timeout-ms))
    (->SocketTransport socket (.getInputStream socket) (.getOutputStream socket)))))
