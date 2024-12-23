(ns flanders.ocsf-test
  (:refer-clojure :exclude [prn println])
  (:require [babashka.process :as proc]
            [clompress.compression]
            [clojure.test :refer [deftest is testing]]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.walk :as walk]))

(defn prn [& args] (locking prn (apply clojure.core/prn args)))
(defn println [& args] (locking prn (apply clojure.core/println args)))

(defn sort-recursive [v]
  (walk/postwalk
    (fn [v]
      (cond
        (map? v) (into (sorted-map) v)

        (and (sequential? v)
             (not (map-entry? v))
             (every? (some-fn nil? string? number?) v))
        (sort v)

        :else v))
    v))

(defn ocsf-server-down []
  (println "down")
  (proc/shell {:dir "tmp/ocsf-server"
               :out *out*
               :err *err*}
              "docker-compose" "down"))

(defn ocsf-server-up [commit]
  (ocsf-server-down)
  (println "reset")
  (proc/shell {:dir "tmp/ocsf-schema"
               :out *out*
               :err *err*}
              "git" "reset" "--hard" commit)
  (println "up")
  (try (proc/shell {:dir "tmp/ocsf-server"
                    :out *out*
                    :err *err*}
                   "docker-compose" "up" "--wait")
       (catch Exception e
         ;;?? ocsf-server-ocsf-elixir-1 exited with code 0
         (prn e)))
  (reduce 
    (fn [_ _]
      (Thread/sleep 500)
      (try (-> "http://localhost:8080/" client/get)
           (reduced true)
           (catch Exception _)))
    nil (range 10)
    )
  )

(defn assert-map! [m] (assert (map? m)) m)

(defn gen-ocsf-json-schema [{:keys [origin version base-url ocsf-schema]
                             :or {origin "https://schema.ocsf.io"
                                  version "1.3.0"}}]
  (let [base-url (or base-url
                     (str origin "/" version "/"))
        export-schema (-> (str base-url "export/schema") client/get :body json/decode assert-map!)
        export-json-schema {"objects" (into {} (map (fn [name]
                                                      (when (Thread/interrupted) (throw (InterruptedException.)))
                                                      [name (let [url (str base-url "schema/objects/" name)]
                                                              (prn url)
                                                              (try (-> url client/get :body json/decode assert-map!)
                                                                   (catch Exception e
                                                                     (prn "FAILED" url)
                                                                     (throw e))))]))
                                            (keys (get export-schema "objects")))
                            "base_event" (let [url (str base-url "schema/classes/base_event")]
                                           (prn url)
                                           (try (-> url client/get :body json/decode assert-map!)
                                                (catch Exception e
                                                  (prn "FAILED" url)
                                                  (throw e))))
                            "classes" (into {} (map (fn [name]
                                                      (when (Thread/interrupted) (throw (InterruptedException.)))
                                                      [name (let [url (str base-url "schema/classes/" name)]
                                                              (prn url)
                                                              (try (-> url client/get :body json/decode assert-map!)
                                                                   (catch Exception e
                                                                     (prn "FAILED" url)
                                                                     (throw e))))]))
                                            (keys (get export-schema "classes")))}]
    (spit (format "resources/flanders/ocsf-%s-json-schema-export.json" version)
          (-> export-json-schema
              sort-recursive
              (json/encode {:pretty true})))))

;"https://schema.ocsf.io/api/1.3.0/classes/http_activity"
(defn gen-ocsf-schema [{:keys [origin version base-url ocsf-schema nsamples]
                 :or {origin "https://schema.ocsf.io"
                      version "1.3.0"}}]
  (let [base-url (or base-url
                     (str origin "/" version "/"))
        _ (prn "base-url" base-url)
        export-schema (-> (str base-url "export/schema") client/get :body json/decode assert-map!)
        _ (assert (map? export-schema))
        #_#_ ;;too big
        sample {"objects" (into {} (map (fn [name]
                                          (when (Thread/interrupted) (throw (InterruptedException.)))
                                          [name (doall
                                                  (pmap (fn [_]
                                                          (do (when (Thread/interrupted) (throw (InterruptedException.)))
                                                              (let [url (str base-url "sample/objects/" name)]
                                                                (prn url)
                                                                (try (-> url client/get :body json/decode assert-map!)
                                                                     (catch Exception e
                                                                       (prn url)
                                                                       (throw e))))))
                                                        (range nsamples)))]))
                                (keys (get export-schema "objects")))
                "base_event" (doall
                               (pmap (fn [_]
                                       (when (Thread/interrupted) (throw (InterruptedException.)))
                                       (let [url (str base-url "sample/base_event")]
                                         (prn url)
                                         (try (-> url client/get :body json/decode assert-map!)
                                              (catch Exception e
                                                (prn url)
                                                (throw e)))))
                                     (range nsamples)))
                "classes" (into {} (map (fn [name]
                                          (when (Thread/interrupted) (throw (InterruptedException.)))
                                          [name (doall (pmap (fn [_]
                                                               (do (when (Thread/interrupted) (throw (InterruptedException.)))
                                                                   (let [url (str base-url "sample/classes/" name)]
                                                                     (prn url)
                                                                     (try (-> url client/get :body json/decode assert-map!)
                                                                          (catch Exception e
                                                                            (prn url)
                                                                            (throw e))))))
                                                             (range nsamples)))]))
                                (keys (get export-schema "classes")))}]
    (try (spit (format "resources/flanders/ocsf-%s-export.json" version)
               (-> export-schema
                   sort-recursive
                   (json/encode {:pretty true})))
         (finally
           #_ ;;too big
           (spit (format "test-resources/flanders/ocsf-%s-sample.json" version)
                 (-> sample
                     sort-recursive
                     (json/encode {:pretty true})))))))

(defn prep-ocsf-repos []
  (doseq [repo ["ocsf-server" "ocsf-schema"]
          :let [tmp-dir "tmp"
                dir (str tmp-dir "/" repo)]]
    (if (.exists (io/file dir))
      (proc/shell {:dir dir
                   :out *out*
                   :err *err*}
                  "git" "fetch" "--all")
      (do (proc/shell {:out *out*
                       :err *err*}
                      "mkdir" "-p" tmp-dir)
          (proc/shell {:dir tmp-dir
                       :out *out*
                       :err *err*}
                      "git" "clone" (format "https://github.com/ocsf/%s.git" repo))))))

(def all-ocsf-exports
  [{:version "1.4.0-dev"
    :nsamples 10
    :nobjects 141
    :nclasses 78
    :ocsf-schema "origin/main"}
   {:version "1.3.0"
    :nsamples 10
    :nobjects 121
    :nclasses 72
    :ocsf-schema "1.3.0"}
   {:version "1.2.0"
    :nsamples 10
    :nobjects 111
    :nclasses 65
    :ocsf-schema "v1.2.0"}
   {:version "1.1.0"
    :nsamples 10
    :nobjects 106
    :nclasses 50
    :ocsf-schema "v1.1.0"}
   {:version "1.0.0"
    :nsamples 10
    :nobjects 84
    :nclasses 36
    :ocsf-schema "v1.0.0"}])

(defn sync-ocsf-export []
  (prep-ocsf-repos)
  (try (doseq [m all-ocsf-exports]
         (some-> (:ocsf-schema m) ocsf-server-up)
         (gen-ocsf-schema (assoc m :base-url "http://localhost:8080/"))
         #_ ;;don't need
         (gen-ocsf-json-schema (assoc m :base-url "http://localhost:8080/"))
         (ocsf-server-down))
       (finally
         (ocsf-server-down))))

(comment
  (sync-ocsf-export)
  ;;regenerate
  (gen-ocsf {:version "1.3.0"})
  (gen-ocsf {:version "1.4.0-dev"})
  (gen-ocsf {:base-url "http://localhost:8080/"
             :version "1.4.0-dev"
             :ocsf-schema "origin/main"})
  (gen-ocsf {:base-url "http://localhost:8080/"
             :version "1.3.0"
             :ocsf-schema "1.3.0"})
  (gen-ocsf {:base-url "http://localhost:8080/"
             :version "1.2.0"
             :ocsf-schema "v1.2.0"})
  (gen-ocsf {:base-url "http://localhost:8080/"
             :version "1.1.0"
             :ocsf-schema "v1.1.0"})
  (gen-ocsf {:base-url "http://localhost:8080/"
             :version "1.0.0"
             :ocsf-schema "v1.0.0"})
  )
