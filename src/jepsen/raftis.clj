(ns jepsen.raftis
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [taoensso.carmine :as car :refer (wcar)]
            [knossos.model :as model]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defn client
  "A client for a single compare-and-set register"
  [conn]
  (reify client/Client
    (setup! [_ test node]
      (def redis-conn {:pool {} :spec {:host node :port 6379}})
                                       ; :password "71ad83e43c8252af"}})
      (client redis-conn))

    (invoke! [this test op]
      (case (:f op)
        :read (assoc op :type :ok, :value (parse-long (get (car/wcar conn (car/get "r")) 0 "0")))
        :write (do (car/wcar conn (car/set "r" (:value op)))
                   (assoc op :type, :ok))))

    (teardown! [_ test])))

(defn db
  "Raftis DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing raftis" version))

    (teardown! [_ test node]
      (info node "tearing down raftis"))))

(defn raftis-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "raftis"
          :db (db "v2.0.2")
          :client (client nil)
          :nemesis (nemesis/partition-random-halves)
          :model (model/cas-register 0)
          :checker (checker/compose
                     {:perf     (checker/perf)
                      :timeline (timeline/html)
                      :linear   checker/linearizable})
          :generator (->> (gen/mix [r w])
                          (gen/stagger 1/30)
                          (gen/nemesis
                            (gen/seq (cycle [(gen/sleep 5)
                                             {:type :info, :f :start}
                                             (gen/sleep 5)
                                             {:type :info, :f :stop}])))
                          (gen/time-limit (:time-limit opts)))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn raftis-test})
                   (cli/serve-cmd))
            args))
