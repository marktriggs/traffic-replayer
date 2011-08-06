(ns traffic-replayer
  (:import (java.io BufferedReader PrintWriter)
           (java.net InetSocketAddress URL URI)
           (java.util.concurrent ConcurrentHashMap)
           (java.nio ByteBuffer CharBuffer)
           (java.nio.charset Charset)
           (java.text SimpleDateFormat)
           (java.nio.channels Selector SelectionKey
                              SocketChannel SelectableChannel))
  (:use [clojure.contrib.duck-streams :only [reader writer]])
  (:gen-class))


(defrecord entry [date request])
(defrecord request [url start-time])


(def #^Selector *selector* (Selector/open))
(def *running* (atom true))
(def #^ConcurrentHashMap *active-requests* (ConcurrentHashMap.))


(defn hit-url
  "Request a URL and register the socket to receive a response."
  [#^URL url]
  (let [utf8 (Charset/forName "UTF-8")
        sock (SocketChannel/open (InetSocketAddress.
                                  #^String (.getHost url)
                                  (int (if (> (.getPort url) 0)
                                         (.getPort url)
                                         80))))
        msg (.encode utf8
                     (CharBuffer/wrap
                      (format (str "GET %s HTTP/1.1\r\n"
                                   "Host: %s\r\n"
                                   "User-Agent: replay.clj\r\n"
                                   "Connection: close\r\n\r\n")
                              (.getFile url)
                              (.getHost url))))]
    (.configureBlocking sock false)
    (.put *active-requests*
          (.register sock *selector* SelectionKey/OP_READ)
          (request. url (System/currentTimeMillis)))
    (.write sock msg)))


(defn response-collector
  "Look for URLs that have responded, eat their responses and log stats."
  [#^PrintWriter log]
  (let [#^ByteBuffer buf (ByteBuffer/allocate 8192)]
    (loop [count (int 0)]
      (if (some (fn [#^SelectionKey key] (.isValid key)) (.keys *selector*))
        (do (.select *selector* 5000)
            (let [keyset (.. *selector* selectedKeys iterator)]
              (doseq [#^SelectionKey key (iterator-seq keyset)]
                (.remove keyset)
                (when (.isReadable key)
                  (when (= (.read #^SocketChannel (.channel key) buf)
                           -1)
                    ;; The fun's over
                    (do (.close (.channel key))
                        (.cancel key)
                        (let [now (System/currentTimeMillis)
                              {:keys [url start-time]}
                              (.get *active-requests* key)]
                          (.println log
                                    (format "%d: %s\t%dms"
                                            now
                                            url
                                            (- now start-time))))
                        (.remove *active-requests* key)))
                  ;; discard input
                  (.clear buf)))))
        (Thread/sleep 200))

      (when @*running*
        (recur
         (int (if (zero? (mod count 1000))
                (do (.println log
                              (format "%d: %d requests currently active"
                                      (System/currentTimeMillis)
                                      (.size *active-requests*)))
                    1)
                (inc count))))))))


;;; I love how crazy Java is.  So kooky!
(defn url [s]
  (let [u (URL. s)]
    (.toURL (URI. (.getProtocol u)
                  (.getUserInfo u)
                  (.getHost u)
                  (.getPort u)
                  (.getPath u)
                  (.getQuery u)
                  (.getRef u)))))


(defn write-script
  "Generate a script file from a HAProxy input stream."
  [#^BufferedReader in #^PrintWriter out]
  (let [interesting-line-re #" \[([^ ]*)\] .*\"GET (.*) HTTP"
        date-parser (SimpleDateFormat. "dd/MMM/yyyy:hh:mm:ss.SSS")]
    (doseq [{:keys [date request]}
            (for [#^String line (line-seq in)
                  :let [entry (let [[date request]
                                    (rest (re-find interesting-line-re line))]
                                (try
                                 (entry. (.. date-parser (parse date) getTime)
                                         request)
                                 (catch Exception _ nil)))]
                  :when entry]
              entry)]
      (.println out (str date "\t" request)))))


(defn replay-script
  "Replay a script to a specified url base, logging interesting stuff as we go."
  [#^BufferedReader in #^String urlbase #^PrintWriter log]
  (reset! *running* true)
  (future (response-collector log))
  (let [lines (line-seq in)]
    (doseq [[[time1 _] [time2 request]]
            (partition 2 1
                       (map (fn [#^String line]
                              (let [[time request] (.split line "\t" 2)]
                                [(Long/parseLong time) request]))
                            (cons (first lines) lines)))]
      (let [delay (- time2 time1)]
        (if (> delay 5)
          (do (println "Sleeping" delay "ms before next request")
              (Thread/sleep delay))
          (println "No sleep!  Kaboom!")))
      (hit-url (url (str urlbase request)))))
  (while (> (.size *active-requests*)
            0)
    (Thread/sleep 1000))
  (reset! *running* false))


(defn -main [& args]
  (let [cmd (first args)]
    (cond (= cmd "generate")
          (let [[_ infile outfile] args
                sorter (.exec (Runtime/getRuntime)
                              (into-array ["sort" "-n" "-k1" "-o" outfile]))]
            (with-open [in (reader infile)
                        out (writer (.getOutputStream sorter))]
              (write-script in out)))

          (= cmd "replay")
          (let [[_ script urlbase logfile] args]
            (with-open [in (reader script)
                        log (writer logfile)]
              (replay-script in (.replaceAll urlbase "/+$" "") log)))

          :else
          (do (println
               "Usage: generate <haproxy log file> <output script>; or\n"
               "      replay   <script file> http://some/base/url <log file>"))))
  (shutdown-agents))
