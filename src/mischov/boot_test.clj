(ns mischov.boot-test
  {:boot/export-tasks true}
  (:refer-clojure :exclude [test])
  (:require [boot.pod  :as pod]
            [boot.core :as core]))

(def pod-deps
  '[[org.clojure/tools.namespace "0.2.10" :exclusions [org.clojure/clojure]]
    [pjstadig/humane-test-output "0.6.0"  :exclusions [org.clojure/clojure]]])

(defn init [fresh-pod]
  (doto fresh-pod
    (pod/with-eval-in
     (require '[clojure.test :as t]
              '[clojure.test.junit :as tj]
              '[clojure.java.io :as io]
              '[pjstadig.humane-test-output :refer [activate!]]
              '[clojure.tools.namespace.parse :as namespace-parse]
              '[clojure.tools.namespace.find :as namespace-find])
     (activate!)
     
     ;; Fix for finding cljc namespaces.
     (defn read-ns-decl [rdr]
       (try
         (loop []
           (let [form (doto (read {:read-cond :allow} rdr) str)]
             (if (namespace-parse/ns-decl? form)
               form
               (recur))))
         (catch Exception e nil)))
     
     (defn read-file-ns-decl [file]
       (with-open [rdr (java.io.PushbackReader. (io/reader file))]
         (read-ns-decl rdr)))
     
     (defn find-ns-decls-in-dir [^java.io.File dir]
       (keep read-file-ns-decl (namespace-find/find-clojure-sources-in-dir dir)))
     
     (defn find-namespaces-in-dir [^java.io.File dir]
       (map second (find-ns-decls-in-dir dir)))
     
     ;; Get all namespaces.
     (defn get-all-ns [& dirs]
       (-> (mapcat #(find-namespaces-in-dir (io/file %)) dirs)))
     
     ;; Narrow down namespaces.
     (defn filter-namespaces
       [namespaces regex]
       (if regex
         (filter #(re-find regex (str %)) namespaces)
         namespaces))
     
     ;; Run tests.
     (defn test-with-formatting
       [namespaces formatter]
       (case formatter
         :junit (tj/with-junit-output
                  (apply t/run-tests namespaces))
         (apply t/run-tests namespaces)))
     
     (defn run-tests
       [namespaces output-path formatter]
       (if output-path
         (with-open [writer (io/writer output-path)]
           (binding [t/*test-out* writer]
             (test-with-formatting namespaces formatter)))
         (test-with-formatting namespaces formatter))))))


;;; This prevents a name collision WARNING between the test task and
;;; clojure.core/test, a function that nobody really uses or cares
;;; about.
(if ((loaded-libs) 'boot.user)
  (ns-unmap 'boot.user 'test))


(core/deftask test
  "Run clojure.test tests in a pod."
  [n namespaces NAMESPACE #{sym} "The set of namespace symbols to run tests in."
   r regex REGEX regex "A regex for filtering namespaces."
   o output-path PATH str "A string representing the filepath to output test results to. Defaults to *out*."
   f formatter FORMATTER kw "Tag defining formatter to use with test results. Currently accepts `junit`. Defaults to standard clojure.test output."]

  (let [worker-pods (pod/pod-pool (update-in (core/get-env) [:dependencies] into pod-deps) :init init)]
    (core/cleanup (worker-pods :shutdown))
    (core/with-pre-wrap fileset
      (println "Starting test...")
      (let [worker-pod (worker-pods :refresh)
            namespaces (or (seq namespaces)
                               (pod/with-eval-in worker-pod
                                 (-> (get-all-ns ~@(->> fileset
                                                        core/input-dirs
                                                        (map (memfn getPath))))
                                     (filter-namespaces ~regex))))]
        (if (seq namespaces)
          (let [summary (pod/with-eval-in worker-pod
                          (doseq [ns '~namespaces] (require ns))
                          (run-tests '~namespaces ~output-path ~formatter))]
            (println "Test complete.")
            (println "Test summary: " (dissoc summary :type))
            (when (> (apply + (map summary [:fail :error])) 0)
              (System/exit 1)))
          (println "No namespaces were tested."))
        fileset))))
