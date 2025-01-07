(defproject io.github.threatgrid/ocsf-schema-export "1.0.0-SNAPSHOT"
  :description "ocsf-schema-export"
  :url "https://github.com/threatgrid/ocsf-schema-export"
  :license {:name "Eclipse Public License"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :pedantic? :abort
  :global-vars {*warn-on-reflection* true}
  :release-tasks [["clean"]
                  ["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :resource-paths ["resources"]
  :profiles {:dev
             {:dependencies [[org.clojure/clojure "1.12.0"]
                             [babashka/process "0.5.22"]
                             [org.clojars.alperenbayramoglu/clompress "0.1.0"]
                             [commons-io "2.16.1"]
                             [cheshire "5.13.0"]
                             [clj-http "3.13.0"]]
              :resource-paths ["test-resources"]}})
