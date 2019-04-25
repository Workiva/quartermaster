(defproject com.workiva/quartermaster "0.1.0"
  :description "Manages hierarchies of shared resources in a manner both simple and tolerant to failure."
  :url "https://github.com/Workiva/quartermaster"
  :license {:name "Apache License, Version 2.0"}

  :plugins [[lein-shell "0.5.0"]
            [lein-cljfmt "0.6.4"]
            [lein-codox "0.10.3"]]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.workiva/utiliva "0.2.0"]
                 [com.workiva/recide "1.0.0"]
                 [com.workiva/barometer "0.1.2"]]

  :deploy-repositories {"clojars"
                        {:url "https://repo.clojars.org"
                         :username :env/clojars_username
                         :password :env/clojars_password
                         :sign-releases false}}

  :source-paths ["src"]
  :test-paths ["test"]

  :global-vars {*warn-on-reflection* true}

  :aliases {"docs" ["do" "clean-docs," "with-profile" "docs" "codox"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:metadata {:doc/format :markdown}
          :themes [:rdash]
          :html {:transforms [[:title]
                              [:substitute [:title "Quartermaster API Docs"]]
                              [:span.project-version]
                              [:substitute nil]
                              [:pre.deps]
                              [:substitute [:a {:href "https://clojars.org/com.workiva/quartermaster"}
                                            [:img {:src "https://img.shields.io/clojars/v/com.workiva/quartermaster.svg"}]]]]}
          :output-path "documentation"}

  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]
                    :source-paths ["dev/src"]}]
             :docs {:dependencies [[codox-theme-rdash "0.1.2"]]}})
