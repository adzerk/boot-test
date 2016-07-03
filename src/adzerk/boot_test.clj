(ns adzerk.boot-test
  {:boot/export-tasks true}
  (:refer-clojure :exclude [test])
  (:require [boot.pod  :as pod]
            [boot.task.built-in :refer [target]]
            [boot.core :as core]))

(def base-pod-deps
  '[[org.clojure/tools.namespace "0.2.11" :exclusions [org.clojure/clojure]]
    [pjstadig/humane-test-output "0.6.0"  :exclusions [org.clojure/clojure]]])

(defn init [requires fresh-pod]
  (dorun (map (partial pod/require-in fresh-pod) requires))
  (doto fresh-pod
    (pod/with-eval-in
     (require '[clojure.test :as t]
              '[clojure.java.io :as io]
              '[clojure.test.junit :as junit]
              '[pjstadig.humane-test-output :refer [activate!]]
              '[clojure.tools.namespace.find :refer [find-namespaces-in-dir]])
     (activate!)

     (defn all-ns* [& dirs]
       (distinct (mapcat #(find-namespaces-in-dir (io/file %)) dirs)))

     (defn junit-plus-default-report [old-report junit-out m]
       (old-report m)
       (binding [t/*test-out* junit-out
                 ;; junit will inc the counters, but old-report is already doing that
                 ;; so we pass a new counters ref that will be discarded to avoid
                 ;; duplicate counters
                 t/*report-counters* (ref {})]
         (junit/junit-report m)))

     (defn run-tests-with-junit-reporter [run-tests-fn output-to]
       (let [junit-out-filename output-to
             old-report t/report]
         (with-open [junit-out (io/writer junit-out-filename)]
           (binding [junit/*var-context* (list)
                     junit/*depth* 1
                     t/report (partial junit-plus-default-report old-report junit-out)]
             (binding [*out* junit-out]
               (println "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
               (println "<testsuites>"))
             (let [result  (run-tests-fn)]
               (binding [*out* junit-out]
                 (println "</testsuites>"))
               result)))))

     (defn test-ns* [pred junit-output-to ns]
       (binding [t/*report-counters* (ref t/*initial-report-counters*)]
         (let [ns-obj (the-ns ns)
               run-tests* (fn []
                            (t/do-report {:type :begin-test-ns :ns ns-obj})
                            (t/test-vars (filter pred (vals (ns-publics ns))))
                            (t/do-report {:type :end-test-ns :ns ns-obj})
                            @t/*report-counters*)]
           (if junit-output-to
             (run-tests-with-junit-reporter run-tests* (io/file junit-output-to (str (name ns) ".xml")))
             (run-tests*))))))))

;;; This prevents a name collision WARNING between the test task and
;;; clojure.core/test, a function that nobody really uses or cares
;;; about.
(if ((loaded-libs) 'boot.user)
  (ns-unmap 'boot.user 'test))

(core/deftask run-tests
  "Run clojure.test tests in a pod.

  The --namespaces option specifies the namespaces to test. The default is to
  run tests in all namespaces found in the project.

  The --exclusions option specifies the namespaces to exclude from testing.

  The --filters option specifies Clojure expressions that are evaluated with %
  bound to a Var in a namespace under test. All must evaluate to true for a Var
  to be considered for testing by clojure.test/test-vars."

  [n namespaces NAMESPACE #{sym} "The set of namespace symbols to run tests in."
   e exclusions NAMESPACE #{sym} "The set of namespace symbols to be excluded from test."
   f filters    EXPR      #{edn} "The set of expressions to use to filter namespaces."
   r requires   REQUIRES  #{sym} "Extra namespaces to pre-load into the pool of test pods for speed."
   j junit-output-to JUNIT-OUT str "The directory where a junit formatted report will be generated for each ns"]

  (let [pod-deps (update-in (core/get-env) [:dependencies] into base-pod-deps)
        worker-pods (pod/pod-pool pod-deps :init (partial init requires))]
    (core/cleanup (worker-pods :shutdown))
    (core/with-pre-wrap fileset
      (let [worker-pod (worker-pods :refresh)
            namespaces (or (seq namespaces)
                           (pod/with-eval-in worker-pod
                             (all-ns* ~@(->> fileset
                                             core/input-dirs
                                             (map (memfn getPath))))))
            namespaces (remove (or exclusions #{}) namespaces)]
        (if (seq namespaces)
          (let [filterf `(~'fn [~'%] (and ~@filters))
                tmp (core/tmp-dir!)
                junit-output-to (when junit-output-to
                                  (str (clojure.java.io/file tmp junit-output-to)))
                summary (pod/with-eval-in worker-pod
                          (doseq [ns '~namespaces] (require ns))
                          (when ~junit-output-to (io/make-parents ~junit-output-to "foo"))
                          (let [ns-results (map (partial test-ns* ~filterf ~junit-output-to) '~namespaces)]
                            (-> (reduce (partial merge-with +) ns-results)
                                (assoc :type :summary)
                                (doto t/do-report))))]
            (-> fileset
                (vary-meta assoc :clojure.test/result summary)
                (core/add-asset tmp)
                core/commit!))
          (let [summary {:test 0, :pass 0, :fail 0, :error 0}] ; from clojure.test/*initial-report-counters*
            (println "No namespaces were tested.")
            (-> fileset
                (vary-meta assoc :clojure.test/result summary))))))))

(core/deftask test
  "Run clojure.test tests in a pod. Throws on test errors or failures.

  The --namespaces option specifies the namespaces to test. The default is to
  run tests in all namespaces found in the project.

  The --exclusions option specifies the namespaces to exclude from testing.

  The --filters option specifies Clojure expressions that are evaluated with %
  bound to a Var in a namespace under test. All must evaluate to true for a Var
  to be considered for testing by clojure.test/test-vars.

  The --junit-output-to option specifies the path to a directory relative to the
  target directory where a junit xml file for each test namespace will be
  generated by using the clojure.test.junit facility. When present it will make
  the target to be synced even when there are test errors or failures"
  [n namespaces NAMESPACE #{sym} "The set of namespace symbols to run tests in."
   e exclusions NAMESPACE #{sym} "The set of namespace symbols to be excluded from test."
   f filters    EXPR      #{edn} "The set of expressions to use to filter namespaces."
   r requires   REQUIRES  #{sym} "Extra namespaces to pre-load into the pool of test pods for speed."
   j junit-output-to JUNIT_OUT str "The directory where a junit formatted report will be generated for each ns"]
  (comp
    (run-tests :namespaces namespaces
               :exclusions exclusions
               :filters filters
               :requires requires
               :junit-output-to junit-output-to)
    (if junit-output-to
      (target)
      identity)
    (core/with-pre-wrap
      fileset
      (let [summary (:clojure.test/result (meta fileset))]
        (if (> (apply + (map summary [:fail :error])) 0)
          (throw (ex-info "Some tests failed or errored" summary))
          fileset)))))
