(defproject com.workiva/quartermaster "0.0.1"
  :description "Manages hierarchies of shared resources in a manner both simple and tolerant to failure."
  :url "https://github.com/Workiva/quartermaster"
  :license {:name "Apache License, Version 2.0"}
  :plugins [[lein-shell "0.5.0"]
            [lein-codox "0.10.3"]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.workiva/utiliva "0.2.0"]
                 [com.workiva/recide "1.0.0"]
                 [com.workiva/barometer "0.1.0"]]

  :source-paths ["src"]
  :test-paths ["test"]

  :global-vars {*warn-on-reflection* true}

  :aliases {"docs" ["do" "clean-docs," "codox"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:output-path "documentation"}

  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]
                    :source-paths ["dev/src"]}]})
