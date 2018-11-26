;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.module-graph-tests
  (:require [clojure.test :as test :refer [deftest is testing]]
            [cljs.closure :as closure]
            [cljs.util :as util]
            [cljs.module-graph :as module-graph])
  (:import [clojure.lang ExceptionInfo]))

(def opts {:output-dir "out"})

(defn modules
  [{:keys [output-dir], :as opts}]
  {:shared {:entries '[shared.a shared.b],
            :output-to (str output-dir "/shared.js")},
   :page1 {:entries '[page1.a page1.b],
           :depends-on [:shared],
           :output-to (str output-dir "/page1.js")},
   :page2 {:entries '[page2.a page2.b],
           :depends-on [:shared],
           :output-to (str output-dir "/page2.js")}})

(defn inputs
  ([] (inputs {:output-dir "out"}))
  ([{:keys [output-dir], :as opts}]
   [{:provides '[goog], :out-file (str output-dir "/goog/base.js")}
    {:provides '[cljs.core], :out-file (str output-dir "/cljs/core.js")}
    {:provides ["cljs.reader"],
     :requires ["cljs.core"],
     :out-file (str output-dir "/cljs/reader.js")}
    {:provides '[events "event.types"],
     :requires ["cljs.core"],
     :out-file (str output-dir "/events.js")}
    {:provides '[shared.a],
     :requires ["cljs.core"],
     :out-file (str output-dir "/shared/a.js")}
    {:provides '[shared.b],
     :requires '[cljs.core],
     :out-file (str output-dir "/shared/b.js")}
    {:provides ["page1.a"],
     :requires ["cljs.core" "cljs.reader" "events" 'shared.a],
     :out-file (str output-dir "/page1/a.js")}
    {:provides ["page1.b"],
     :requires '[cljs.core shared.b],
     :out-file (str output-dir "/page1/b.js")}
    {:provides ["page2.a"],
     :requires ["cljs.core" "events" 'shared.a],
     :out-file (str output-dir "/page2/a.js")}
    {:provides ["page2.b"],
     :requires ['cljs.core 'shared.b],
     :out-file (str output-dir "/page2/b.js")}]))

(deftest test-add-cljs-base
  (is (true? (contains? (module-graph/add-cljs-base (modules opts))
                        :cljs-base))))

(deftest test-add-cljs-base-dep
  (let [modules' (-> (modules opts)
                     module-graph/add-cljs-base
                     module-graph/add-cljs-base-dep)]
    (is (not (some #{:cljs-base} (get-in modules' [:cljs-base :depends-on]))))
    (is (some #{:cljs-base} (get-in modules' [:shared :depends-on])))
    (is (not (some #{:cljs-base} (get-in modules' [:page1 :depends-on]))))
    (is (not (some #{:cljs-base} (get-in modules' [:page2 :depends-on]))))))

(deftest test-module-deps
  (let [modules (-> (modules opts)
                    module-graph/add-cljs-base
                    module-graph/add-cljs-base-dep)]
    (is (= (module-graph/deps-for-module :page1 modules)
           [:cljs-base :shared]))))

(deftest test-entry-deps
  (let [inputs (module-graph/index-inputs (inputs opts))]
    (is (= (module-graph/deps-for-entry "page2.a" inputs)
           ["cljs.core" "events" "shared.a"]))
    (is (some #{"shared.a"} (module-graph/deps-for-entry "page1.a" inputs)))))

(deftest test-canonical-name
  (let [ins (module-graph/index-inputs (inputs opts))]
    (is (= "events" (module-graph/canonical-name 'events ins)))
    (is (= "events" (module-graph/canonical-name "events" ins)))
    (is (= "events" (module-graph/canonical-name 'event.types ins)))
    (is (= "events" (module-graph/canonical-name "event.types" ins)))))

(deftest test-inputs->assigned-modules
  (let [modules (modules opts)
        modules' (-> modules
                     module-graph/add-cljs-base
                     module-graph/add-cljs-base-dep
                     module-graph/annotate-depths)
        inputs' (inputs opts)
        indexed (module-graph/index-inputs inputs')
        assigns (module-graph/inputs->assigned-modules inputs' modules')
        assigns' (reduce-kv
                   (fn [ret module-name {:keys [entries]}]
                     (merge ret
                            (zipmap (map #(module-graph/canonical-name %
                                                                       indexed)
                                      entries)
                                    (repeat module-name))))
                   {}
                   modules)]
    ;; every input assigned, including orphans
    (is (every? #(contains? assigns %)
                (map #(module-graph/canonical-name % indexed)
                  (mapcat :provides inputs'))))
    ;; every user specified assignment should be respected
    (is (every? (fn [[e m]] (= m (get assigns e))) assigns'))
    ;; events should not have been moved to :cljs-base as an orphan even though
    ;; it provides multiple nses
    (is (= (get assigns "events") :shared))))

(def bad-modules
  {:page1 {:entries '[page1.a page1.b events], :output-to "out/page1.js"},
   :page2 {:entries '[page2.a page2.b event.types], :output-to "out/page2.js"}})

(deftest test-duplicate-entries
  (let [modules' (-> bad-modules
                     module-graph/add-cljs-base
                     module-graph/add-cljs-base-dep)
        index (module-graph/index-inputs (inputs opts))]
    (is (= (try (module-graph/validate-modules modules' index)
                (catch Throwable t :caught))
           :caught))))

(deftest test-module->module-uris
  (is (= (module-graph/modules->module-uris (modules opts)
                                            (inputs opts)
                                            {:output-dir (:output-dir opts),
                                             :asset-path "/asset/js",
                                             :optimizations :none})
         {:shared ["/asset/js/cljs/core.js" "/asset/js/events.js"
                   "/asset/js/shared/a.js" "/asset/js/shared/b.js"],
          :page1 ["/asset/js/cljs/reader.js" "/asset/js/page1/a.js"
                  "/asset/js/page1/b.js"],
          :page2 ["/asset/js/page2/a.js" "/asset/js/page2/b.js"],
          :cljs-base ["/asset/js/goog/base.js"]}))
  (is (= (module-graph/modules->module-uris (modules opts)
                                            (inputs opts)
                                            {:output-dir (:output-dir opts),
                                             :asset-path "/asset/js",
                                             :optimizations :advanced})
         {:cljs-base ["/asset/js/cljs_base.js"],
          :shared ["/asset/js/shared.js"],
          :page1 ["/asset/js/page1.js"],
          :page2 ["/asset/js/page2.js"]})))

(deftest test-module-for
  (is (= :page1 (module-graph/module-for 'page1.a (modules opts))))
  (is (= :page1 (module-graph/module-for "page1.a" (modules opts)))))

(def circular-inputs
  [{:provides ["foo.core"], :requires ["bar.core"]}
   {:provides ["bar.core"], :requires ["baz.core"]}
   {:provides ["baz.core"], :requires ["foo.core"]}])

(deftest test-circular-deps
  (is (nil? (module-graph/validate-inputs (inputs))))
  (is (thrown? ExceptionInfo (module-graph/validate-inputs circular-inputs))))
