(ns cljs-cli.test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell :refer [with-sh-dir]]
            [clojure.string :as str]
            [cljs-cli.util :refer
             [cljs-main output-is with-sources with-in with-post-condition
              with-repl-env-filter repl-title]]
            [clojure.string :as string]))

(deftest eval-test
  (-> (cljs-main "-e" 3 "-e" nil "-e" 4)
      (output-is 3 4)))

(deftest init-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (def x 3)"}
                (-> (cljs-main "-i" "src/foo/core.cljs" "-e" 'foo.core/x)
                    (output-is 3))))

(deftest main-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (defn -main [] (prn :hi))"}
                (-> (cljs-main "-m" "foo.core")
                    (output-is :hi))))

(deftest command-line-args-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (prn *command-line-args*)"}
                (-> (cljs-main "src/foo/core.cljs" "alpha" "beta" "gamma")
                    (output-is (pr-str '("alpha" "beta" "gamma"))))))

(deftest command-line-args-empty-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (prn *command-line-args*)"}
                (-> (cljs-main "src/foo/core.cljs")
                    (output-is nil))))

(deftest initial-ns-test
  (-> (cljs-main "-e" "::foo")
      (output-is ":cljs.user/foo")))

(deftest source-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (prn :hi)"}
                (-> (cljs-main "src/foo/core.cljs")
                    (output-is :hi))))

(deftest compile-test
  (with-sources {"src/foo/core.cljs" "(ns foo.core) (defn -main [] (prn :hi))"}
                (with-post-condition
                  (fn [dir] (.exists (io/file dir "out" "main.js")))
                  (-> (cljs-main "-o" "out/main.js" "-c" "foo.core")
                      (output-is)))))

(deftest run-optimized-node-test
  (with-repl-env-filter
    #{"node"}
    (with-sources
      {"src/foo/core.cljs" "(ns foo.core) (prn :hello-from-node)"}
      (with-post-condition
        (fn [dir]
          (= {:exit 0, :out ":hello-from-node\n", :err ""}
             (with-sh-dir dir
                          (shell/sh "node"
                                    (str (io/file dir "out" "main.js"))))))
        (-> (cljs-main "-t" "node"
                       "-o" "out/main.js"
                       "-O" "advanced"
                       "-c" "foo.core")
            (output-is))))))

(deftest test-cljs-2645
  (with-sources
    {"src/foo/core.cljs"
       "(ns foo.core) (goog-define configurable \"default-value\") (defn -main [& args] (println configurable))"}
    (-> (cljs-main "-m" "foo.core")
        (output-is "default-value"))
    (-> (cljs-main
          "-co"
            "{:closure-defines {foo.core/configurable \"configured-value\"}}"
          "-m" "foo.core")
        (output-is "configured-value"))))

(deftest test-cljs-2650-loader-does-not-exists
  (doseq [optimizations [:none :advanced]]
    (let [src (io/file "src" "test" "cljs_build" "hello-modules" "src")
          opts {:output-dir "out",
                :asset-path "/out",
                :optimizations optimizations,
                :modules {:foo {:entries '#{foo.core}, :output-to "out/foo.js"},
                          :bar {:entries '#{bar.core},
                                :output-to "out/bar.js"}}}]
      (with-sources
        {"src/foo/core.cljs" (slurp (io/file src "foo" "core.cljs")),
         "src/bar/core.cljs" (slurp (io/file src "bar" "core.cljs"))}
        (let [result (cljs-main "--compile-opts" (pr-str opts)
                                "--compile" "foo.core")]
          (is (zero? (:exit result)))
          (is (str/blank? (:err result))))))))

(deftest test-cljs-2673
  (with-repl-env-filter
    #{"node"}
    (->
      (cljs-main
        "-e" "(require 'cljs.js)"
        "-e"
          "(cljs.js/eval-str (cljs.js/empty-state) \"(+ 1 2)\" nil {:eval cljs.js/js-eval :context :expr} prn)")
      (output-is "{:ns cljs.user, :value 3}"))))

(deftest test-cljs-2724
  (with-repl-env-filter #{"node"}
                        (-> (cljs-main "-e" "(require 'fs)" "-e" "fs/R_OK")
                            (output-is 4))))

(deftest test-cljs-2775
  (with-repl-env-filter
    #{"node"}
    (-> (cljs-main "-co" "{:npm-deps {:left-pad \"1.3.0\"} :install-deps true}"
                   "-d" "out"
                   "-e" "(require 'left-pad)"
                   "-e" "(left-pad 3 10 0)")
        (output-is "\"0000000003\""))))

(deftest test-cljs-2780
  (with-repl-env-filter #{"node" "nashorn" "graaljs"}
                        (-> (cljs-main
                              "-e" "(do (js/setTimeout #(prn :end) 500) nil)"
                              "-e" ":begin")
                            (output-is :begin :end))))

(deftest test-graaljs-polyglot
  (with-repl-env-filter #{"graaljs"}
                        (-> (cljs-main "-e"
                                       "(.eval js/Polyglot \"js\" \"1+1\")")
                            (output-is 2))))
