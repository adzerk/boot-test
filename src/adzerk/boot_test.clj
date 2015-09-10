(ns adzerk.boot-test
  {:boot/export-tasks true}
  (:refer-clojure :exclude [test])
  (:require [boot.pod  :as pod]
            [boot.core :as core]))

(def pod-deps
  '[[org.clojure/tools.namespace "0.2.11" :exclusions [org.clojure/clojure]]
    [pjstadig/humane-test-output "0.6.0"  :exclusions [org.clojure/clojure]]])

(defn init [fresh-pod]
  (doto fresh-pod
    (pod/with-eval-in
     (require '[clojure.test :as t]
              '[clojure.java.io :as io]
              '[pjstadig.humane-test-output :refer [activate!]]
              '[clojure.tools.namespace.find :refer [find-namespaces-in-dir]])
     (activate!)
     (defn all-ns* [& dirs]
       (mapcat #(find-namespaces-in-dir (io/file %)) dirs))
     (defn test-ns* [pred ns]
       (binding [t/*report-counters* (ref t/*initial-report-counters*)]
         (let [ns-obj (the-ns ns)]
           (t/do-report {:type :begin-test-ns :ns ns-obj})
           (t/test-vars (filter pred (vals (ns-publics ns))))
           (t/do-report {:type :end-test-ns :ns ns-obj}))
         @t/*report-counters*)))))

;;; This prevents a name collision WARNING between the test task and
;;; clojure.core/test, a function that nobody really uses or cares
;;; about.
(if ((loaded-libs) 'boot.user)
  (ns-unmap 'boot.user 'test))

(core/deftask test
  "Run clojure.test tests in a pod.

  The --namespaces option specifies the namespaces to test. The default is to
  run tests in all namespaces found in the project.

  The --filters option specifies Clojure expressions that are evaluated with %
  bound to a Var in a namespace under test. All must evaluate to true for a Var
  to be considered for testing by clojure.test/test-vars."

  [n namespaces NAMESPACE #{sym} "The set of namespace symbols to run tests in."
   f filters    EXPR      #{edn} "The set of expressions to use to filter namespaces."]

  (let [worker-pods (pod/pod-pool (update-in (core/get-env) [:dependencies] into pod-deps) :init init)]
    (core/cleanup (worker-pods :shutdown))
    (core/with-pre-wrap fileset
      (let [worker-pod (worker-pods :refresh)
            namespaces (or (seq namespaces)
                           (pod/with-eval-in worker-pod
                             (all-ns* ~@(->> fileset
                                             core/input-dirs
                                             (map (memfn getPath))))))]
        (if (seq namespaces)
          (let [filterf `(~'fn [~'%] (and ~@filters))
                summary (pod/with-eval-in worker-pod
                          (doseq [ns '~namespaces] (require ns))
                          (let [ns-results (map (partial test-ns* ~filterf) '~namespaces)]
                            (-> (reduce (partial merge-with +) ns-results)
                                (assoc :type :summary)
                                (doto t/do-report))))]
            (when (> (apply + (map summary [:fail :error])) 0)
              (throw (ex-info "Some tests failed or errored" summary))))
          (println "No namespaces were tested."))
        fileset))))
