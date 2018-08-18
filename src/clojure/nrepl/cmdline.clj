(ns nrepl.cmdline
  "A proof-of-concept command-line client for nREPL.  Please see
  e.g. reply for a proper command-line nREPL client @
  https://github.com/trptcolin/reply/"
  {:author "Chas Emerick"}
  (:require [nrepl.core :as repl]
            [nrepl.ack :refer [send-ack]]
            [nrepl.server :refer [start-server]]))

(def colored-output
  {:err #(binding [*out* *err*]
           (print "\033[31m")
           (print %)
           (print "\033[m")
           (flush))
   :out print
   :value (fn [x]
            (print "\033[34m")
            (print x)
            (println "\033[m")
            (flush))})

(defn- run-repl
  ([host port]
   (run-repl host port nil))
  ([host port {:keys [prompt err out value]
               :or {prompt #(print (str % "=> "))
                    err print
                    out print
                    value println}}]
   (let [transport (repl/connect :host host :port port)
         client (repl/client-session (repl/client transport Long/MAX_VALUE))
         ns (atom "user")
         {:keys [major minor incremental qualifier]} *clojure-version*]
     (println (format "nREPL %s" repl/version-string))
     (println (str "Clojure " (clojure-version)))
     (println (System/getProperty "java.vm.name") (System/getProperty "java.runtime.version"))
     (loop []
       (prompt @ns)
       (flush)
       (doseq [res (repl/message client {:op "eval" :code (pr-str (read))})]
         (when (:value res) (value (:value res)))
         (when (:out res) (out (:out res)))
         (when (:err res) (err (:err res)))
         (when (:ns res) (reset! ns (:ns res))))
       (recur)))))

(def #^{:private true} unary-options #{"--interactive" "--color" "--help"})

(defn- split-args
  "Convert `args` into a map of options + a list of args.
  Unary options are set to true during this transformation.
  Returns a vector combining the map and the list."
  [args]
  (loop [[arg & rem-args :as args] args
         options {}]
    (if-not (and arg (re-matches #"--.*" arg))
      [options args]
      (if (unary-options arg)
        (recur rem-args
               (assoc options arg true))
        (recur (rest rem-args)
               (assoc options arg (first rem-args)))))))

(defn- display-help
  []
  (println "Usage:

  --interactive            Start nREPL and connect to it with the built-in client.
  --color                  Use colors to differentiate values from output in the REPL. Must be combined with --interactive.
  --bind                   Bind address, by default \"::\" (falling back to \"localhost\" if \"::\" isn't resolved by the underlying network stack).
  --port PORT              Start nREPL on PORT. Defaults to 0 (random port) if not specified.
  --ack ACK-PORT           Acknowledge the port of this server to another nREPL server running on ACK-PORT.
  --handler HANDLER        The nREPL message handler to use for each incoming connection; defaults to the result of `(nrepl.server/default-handler)`.
  --middleware MIDDLEWARE  A sequence of vars, representing middleware you wish to mix in to the nREPL handler.
  --help                   Show this help message."))

(defn- require-and-resolve
  "Require and resolve `thing`."
  [thing]
  (require (symbol (namespace thing)))
  (resolve thing))

(def ^:private resolve-mw-xf
  (comp (map require-and-resolve)
        (keep identity)))

(defn- handle-seq-var
  [var]
  (let [x @var]
    (if (sequential? x)
      (into [] resolve-mw-xf x)
      [var])))

(def ^:private mw-xf
  (comp (map symbol)
        resolve-mw-xf
        (mapcat handle-seq-var)))

(defn- ->mw-list
  [middleware-var-strs]
  (into [] mw-xf middleware-var-strs))

(defn- build-handler
  "Build an nREPL handler from `middleware`.
  `middleware` is a sequence of vars or string which can be resolved
  to vars, representing middleware you wish to mix in to the nREPL
  handler. Vars can resolve to a sequence of vars, in which case
  they'll be flattened into the list of middleware."
  [middleware]
  (apply nrepl.server/default-handler (->mw-list middleware)))

(defn -main
  [& args]
  (let [[options args] (split-args args)]
    (when (options "--help")
      (display-help)
      (System/exit 0))
    (let [port (Integer/parseInt (or (options "--port") "0"))
          bind (options "--bind")
          ;; if some handler was explicitly passed we'll use it, otherwise we'll build one
          ;; from whatever was passed via --middleware
          handler (and (options "--handler") (read-string (options "--handler")))
          middleware (and (options "--middleware") (read-string (options "--middleware")))
          handler (if handler (handler) (build-handler middleware))
          server (start-server :port port :bind bind :handler handler)
          ^java.net.ServerSocket ssocket (:server-socket server)]
      (when-let [ack-port (options "--ack")]
        (binding [*out* *err*]
          (println (format "ack'ing my port %d to other server running on port %s"
                           (.getLocalPort ssocket) ack-port)
                   (:status (send-ack (.getLocalPort ssocket) (Integer/parseInt ack-port))))))
      (let [port (:port server)
            host (.getHostName (.getInetAddress ssocket))]
        ;; The format here is important, as some tools (e.g. CIDER) parse the string
        ;; to extract from it the host and the port to connect to
        (println (format "nREPL server started on port %d on host %s - nrepl://%s:%d"
                         port host host port))
        (if (options "--interactive")
          (run-repl host port (when (options "--color") colored-output))
          ;; need to hold process open with a non-daemon thread -- this should end up being super-temporary
          (Thread/sleep Long/MAX_VALUE))))))
