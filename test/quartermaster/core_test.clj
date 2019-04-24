;; Copyright 2017-2019 Workiva Inc.
;; 
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;; 
;;     http://www.apache.org/licenses/LICENSE-2.0
;; 
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns quartermaster.core-test
  (:require [quartermaster.core :as rm]
            [quartermaster.error :as qe]
            [barometer.core :as em]
            [clojure.test :refer :all])
  (:import [java.util.concurrent CountDownLatch]))

(defprotocol MagicalResource
  (do-something-magical [_] "This really amazing resource does something really amazing."))

(def terminal-total-all-time (atom 0))

(defrecord TerminalResource [resource-id config]
  rm/SharedResource
  (resource-id [_] (some-> resource-id deref))
  (initiated? [this] (boolean (rm/resource-id this)))
  (status* [this] {})
  (initiate [this]
    (if (rm/initiated? this)
      this
      (do
        (when (and (map? config)
                   (contains? config :initiate-sleep))
          (Thread/sleep (:initiate-sleep config)))
        (swap! terminal-total-all-time inc)
        (assoc this :resource-id (atom (rm/new-resource-id))))))
  (terminate [this]
    (if (rm/terminated? this)
      this
      (do (when (and (map? config)
                     (contains? config :terminate-sleep))
            (Thread/sleep (:terminate-sleep config)))
          (reset! resource-id nil)
          (assoc this :resource-id nil))))
  (force-terminate [this]
    (if (rm/terminated? this)
      this
      (rm/terminate this)))
  MagicalResource
  (do-something-magical [this]
    (if (rm/initiated? this)
      true
      (qe/raise-terminated "Magic ain't free." {}))))

(defrecord FakeTerminalResourceForTesting [resource-id config]
  rm/SharedResource
  (resource-id [_] (some-> resource-id deref))
  (initiated? [this] (boolean (rm/resource-id this)))
  (status* [this] {})
  (initiate [this]
    (if (rm/initiated? this)
      this
      (assoc this :resource-id (atom (rm/new-resource-id)))))
  (terminate [this]
    (if (rm/terminated? this)
      this
      (do (reset! resource-id nil)
          (assoc this :resource-id nil))))
  (force-terminate [this]
    (if (rm/terminated? this)
      this
      (rm/terminate this)))
  MagicalResource
  (do-something-magical [this]
    (if (rm/initiated? this)
      #{:magic-for-the-win}
      (qe/raise-terminated "Magic ain't free." {}))))

(rm/defmanager terminal-resource-manager
  :discriminator
  (fn [user-id config] config)
  :constructor
  (fn [_ config] (map->TerminalResource {:config config})))

(defn terminal-counter []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.terminal-resource-manager.extant.counter"))

(defn terminal-init-timer []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.terminal-resource-manager.construction.timer"))

(defn terminal-term-timer []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.terminal-resource-manager.termination.timer"))

(defn reset-terminal-metrics []
  (em/register-all
   em/DEFAULT
   {"quartermaster.core-test.terminal-resource-manager.extant.counter"
    (em/counter ""),
    "quartermaster.core-test.terminal-resource-manager.construction.timer"
    (em/timer ""),
    "quartermaster.core-test.terminal-resource-manager.termination.timer"
    (em/timer "")}))

(defmacro time*
  [v expr]
  `(let [v# ~v
         start# (. System (nanoTime))
         ret# ~expr]
     (vreset! v# (/ (double (- (. System (nanoTime)) start#)) 1000000.0))
     ret#))

(defrecord ParentResource [resource-id config terminal-a terminal-b]
  rm/SharedResource
  (resource-id [_] (some-> resource-id deref))
  (initiated? [this] (boolean (rm/resource-id this)))
  (status* [this] {})
  (initiate [this]
    (if (rm/initiated? this)
      this
      (let [res-id (rm/new-resource-id)
            terminal-a (rm/acquire terminal-resource-manager res-id (:terminal-a config))
            terminal-b (rm/acquire terminal-resource-manager res-id (:terminal-b config))]
        (assoc this
               :resource-id (atom res-id)
               :terminal-a terminal-a
               :terminal-b terminal-b))))
  (terminate [this]
    (if (rm/terminated? this)
      this
      (let [res-id @resource-id]
        (reset! resource-id nil)
        (rm/release terminal-a true)
        (rm/release terminal-b true)
        (assoc this
               :resource-id nil
               :terminal-a nil
               :terminal-b nil))))
  (force-terminate [this]
    (if (rm/terminated? this)
      this
      (do (rm/force-terminate terminal-a)
          (rm/force-terminate terminal-b)
          (rm/terminate this))))
  MagicalResource
  (do-something-magical [this]
    (if (rm/initiated? this)
      (and (do-something-magical @terminal-a)
           (do-something-magical @terminal-b))
      (qe/raise-terminated "I ain't available no more." {}))))

(rm/defmanager parent-resource-manager
  :discriminator
  (fn [user-id config] (:parent-config config))
  :constructor
  (fn [parent-id config] (map->ParentResource {:config config})))

(defn parent-counter []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.parent-resource-manager.extant.counter"))

(defn parent-init-timer []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.parent-resource-manager.construction.timer"))

(defn parent-term-timer []
  (em/get-metric
   em/DEFAULT
   "quartermaster.core-test.parent-resource-manager.termination.timer"))

(defn reset-parent-metrics []
  (em/register-all
   em/DEFAULT
   {"quartermaster.core-test.parent-resource-manager.extant.counter"
    (em/counter ""),
    "quartermaster.core-test.parent-resource-manager.construction.timer"
    (em/timer ""),
    "quartermaster.core-test.parent-resource-manager.termination.timer"
    (em/timer "")}))

(deftest test:acquire-release
  (testing "just one terminal, short timers"
    (reset-terminal-metrics)
    (rm/testing-for-resource-leaks
     (rm/acquire terminal-resource-manager :testing-1 :terminal-a)
     (is (= 1 (em/count (terminal-counter))))
     (is (> 100E6 (em/max (terminal-init-timer))))
     (rm/release* terminal-resource-manager :testing-1 :terminal-a true)
     (is (> 100E6 (em/max (terminal-term-timer))))
     (is (= 0 (em/count (terminal-counter))))))
  (testing "just one terminal, doubly acquired, short timers"
    (reset-terminal-metrics)
    (rm/testing-for-resource-leaks
     (rm/acquire terminal-resource-manager :testing-1 :terminal-a)
     (is (= 1 (em/count (terminal-counter))))
     (rm/acquire terminal-resource-manager :testing-2 :terminal-a)
     (is (= 1 (em/count (terminal-counter))))
     (is (> 100E6 (em/max (terminal-init-timer))))
     (rm/release* terminal-resource-manager :testing-1 :terminal-a true)
     (is (= 1 (em/count (terminal-counter))))
     (rm/release* terminal-resource-manager :testing-2 :terminal-a true)
     (is (= 0 (em/count (terminal-counter))))
     (is (> 100E6 (em/max (terminal-term-timer))))))
  (testing "just one terminal, lengthy timers"
    (reset-terminal-metrics)
    (rm/testing-for-resource-leaks
     (rm/acquire terminal-resource-manager :testing-1 {:initiate-sleep 100, :terminate-sleep 100})
     (is (< 100E6 (em/min (terminal-init-timer))))
     (rm/release* terminal-resource-manager :testing-1 {:initiate-sleep 100, :terminate-sleep 100} true)
     (is (< 100E6 (em/min (terminal-term-timer))))))
  (testing "multiple terminals, lengthy timers"
    (reset-terminal-metrics)
    (let [configs (map (partial assoc {:initiate-sleep 100 :terminate-sleep 100} :id) (range 10))
          time (volatile! nil)]
      (rm/testing-for-resource-leaks
       (time* time
               (doseq [config configs]
                 (rm/acquire terminal-resource-manager :testing-1 config)))
       (is (<= 1000 @time))
       (time* time
              (doseq [config configs]
                (rm/release* terminal-resource-manager :testing-1 config true)))
       (is (<= 1000 @time))
       (time* time
              (doall
               (pmap #(rm/acquire terminal-resource-manager :testing-1 %) configs)))
       (is (>= 200 @time))
       (time* time
              (doall
               (pmap #(rm/release* terminal-resource-manager :testing-1 %) configs)))
       (is (>= 200 @time)))))
  (testing "just one parent with one terminal, short timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a :terminal-1, :terminal-b :terminal-1}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 1 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (> 100E6 (em/max (terminal-init-timer))))
       (is (> 100E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter)))))))
  (testing "just one parent with two terminals, short timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a :terminal-1, :terminal-b :terminal-2}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (> 100E6 (em/max (terminal-init-timer))))
       (is (> 100E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter)))))))
  (testing "one parent with one terminal, doubly acquired, short timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a :terminal-1, :terminal-b :terminal-1}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 1 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (rm/acquire parent-resource-manager :testing-2 config)
       (is (= 1 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (> 100E6 (em/max (terminal-init-timer))))
       (is (> 100E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (= 1 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (rm/release* parent-resource-manager :testing-2 config true)
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter)))))))
  (testing "one parent with two terminals, doubly acquired, short timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a :terminal-1, :terminal-b :terminal-2}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (rm/acquire parent-resource-manager :testing-2 config)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (> 100E6 (em/max (terminal-init-timer))))
       (is (> 100E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (rm/release* parent-resource-manager :testing-2 config true)
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter)))))))
  (testing "one parent with two terminals, doubly acquired, short timers, alternate release"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a :terminal-1, :terminal-b :terminal-2}
           parent-1 (rm/acquire parent-resource-manager :testing-1 config)]
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (let [parent-2 (rm/acquire parent-resource-manager :testing-2 config)]
         (is (= 2 (em/count (terminal-counter))))
         (is (= 1 (em/count (parent-counter))))
         (is (> 100E6 (em/max (terminal-init-timer))))
         (is (> 100E6 (em/max (parent-init-timer))))
         (rm/release parent-1 true)
         (is (= 2 (em/count (terminal-counter))))
         (is (= 1 (em/count (parent-counter))))
         (rm/release parent-2 true))
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter)))))))
  (testing "just one parent with one terminal, mixed timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [terminal-config {:id :terminal-1, :initiate-sleep 100}
           config {:terminal-a terminal-config, :terminal-b terminal-config}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 1 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (< 100E6 (em/max (terminal-init-timer))))
       (is (< 100E6 (em/max (parent-init-timer)) 200E6))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter))))
       (is (> 100E6 (em/max (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer)))))))
  (testing "just one parent with two terminals, mixed timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [terminal-1-config {:id :terminal-1, :initiate-sleep 100}
           terminal-2-config {:id :terminal-2, :initiate-sleep 100, :terminate-sleep 100}
           config {:terminal-a terminal-1-config :terminal-b terminal-2-config}]
       (rm/acquire parent-resource-manager :testing-1 config)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (< 100E6 (em/max (terminal-init-timer)) 200E6))
       (is (< 200E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 config true)
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter))))
       (is (> 100E6 (em/min (terminal-term-timer))))
       (is (< 100E6 (em/max (terminal-term-timer))))
       (is (< 100E6 (em/max (parent-term-timer)))))))
  (testing "two parents with three terminals, mixed timers"
    (reset-terminal-metrics)
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [terminal-1-config {:id :terminal-1}
           terminal-2-config {:id :terminal-2, :initiate-sleep 100, :terminate-sleep 100}
           terminal-3-config {:id :terminal-3, :initiate-sleep 200}
           parent-1-config {:parent-config :parent-1,
                            :terminal-a terminal-1-config,
                            :terminal-b terminal-2-config}
           parent-2-config {:parent-config :parent-2,
                            :terminal-a terminal-2-config,
                            :terminal-b terminal-3-config}]
       (rm/acquire parent-resource-manager :testing-1 parent-1-config)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (> 100E6 (em/min (terminal-init-timer))))
       (is (< 100E6 (em/max (terminal-init-timer)) 200E6))
       (is (< 100E6 (em/max (parent-init-timer)) 200E6))
       (rm/acquire parent-resource-manager :testing-1 parent-2-config)
       (is (= 3 (em/count (terminal-counter))))
       (is (= 2 (em/count (parent-counter))))
       (is (< 200E6 (em/max (terminal-init-timer))))
       (is (< 200E6 (em/max (parent-init-timer)) 300E6))
       (rm/release* parent-resource-manager :testing-1 parent-1-config true)
       (is (= 2 (em/count (terminal-counter))))
       (is (= 1 (em/count (parent-counter))))
       (is (>= 100E6 (em/max (terminal-term-timer)) (em/min (terminal-term-timer))))
       (is (> 100E6 (em/max (parent-term-timer))))
       (rm/release* parent-resource-manager :testing-1 parent-2-config true)
       (is (= 0 (em/count (terminal-counter))))
       (is (= 0 (em/count (parent-counter))))
       (is (< (em/min (terminal-term-timer)) 100E6 (em/max (terminal-term-timer))))
       (is (< (em/min (parent-term-timer)) 100E6 (em/max (parent-term-timer))))
       (rm/acquire parent-resource-manager :testing-1 parent-2-config)
       (is (< 300E6 (em/max (parent-init-timer))))
       (rm/release* parent-resource-manager :testing-1 parent-2-config true)))))

(deftest test:resource-handle
  (testing "auto-detects termination and reacquires"
    (rm/testing-for-resource-leaks
     (let [terminal (rm/acquire terminal-resource-manager :testing-1 :config)
           terminal-2 (rm/acquire terminal-resource-manager :testing-2 :config)
           actual-resource @terminal]
       (is (and (identical? @terminal @terminal-2)
                (identical? @terminal actual-resource)))
       (rm/terminate terminal-2)
       (is (and (identical? @terminal @terminal-2)
                (not= @terminal actual-resource)))
       (rm/release terminal true)
       (rm/release terminal-2 true)))))

(deftest test:resource-recovery
  (testing "terminals throwing error when used after termination"
    (rm/testing-for-resource-leaks
     (let [terminal (rm/acquire terminal-resource-manager :testing-1 :terminal-1)]
       (is (do-something-magical @terminal))
       (rm/acquire terminal-resource-manager :testing-2 :terminal-1)
       (is (do-something-magical @terminal))
       (rm/release* terminal-resource-manager :testing-1 :terminal-1 true)
       (is (do-something-magical @terminal))
       (rm/release* terminal-resource-manager :testing-2 :terminal-1 true)
       (is (thrown? Exception (do-something-magical @terminal))))))
  (testing "parent resources throwing error when used after termination."
    (reset-parent-metrics)
    (rm/testing-for-resource-leaks
     (let [config-1 {:parent-config :parent-1,
                     :terminal-a :terminal-a,
                     :terminal-b :terminal-b}
           config-2 {:parent-config :parent-2,
                     :terminal-a :terminal-a,
                     :terminal-b :terminal-b}
           parent-1 (rm/acquire parent-resource-manager :testing-1 config-1)
           parent-2 (rm/acquire parent-resource-manager :testing-1 config-2)]
       (is (= 2 (em/count (parent-counter))))
       (is (do-something-magical @parent-1))
       (is (do-something-magical @parent-2))
       (rm/release* parent-resource-manager :testing-1 config-1 true)
       (is (= 1 (em/count (parent-counter))))
       (is (do-something-magical @parent-2))
       (is (thrown? Exception (do-something-magical @parent-1)))
       (rm/release* parent-resource-manager :testing-1 config-2 true)
       (is (thrown? Exception (do-something-magical @parent-2))))))
  (testing "parent recovers when its resources are destroyed."
    (rm/testing-for-resource-leaks
     (let [config {:terminal-a 1, :terminal-b 2}
           resource (rm/acquire parent-resource-manager :testing-1 config)
           terminal-a @(:terminal-a @resource)
           terminal-b @(:terminal-b @resource)]
       (is (and (do-something-magical @resource)
                (do-something-magical terminal-a)
                (do-something-magical terminal-b)))
       (rm/terminate terminal-a)
       (is (thrown? Exception (do-something-magical terminal-a)))
       (is (do-something-magical @resource))
       (rm/release* parent-resource-manager :testing-1 config true)))))

(deftest test:shared-resources-recovery
  (testing "simple-shared"
    (rm/testing-for-resource-leaks
     (let [config-1 {:parent-config :parent-1,
                     :terminal-a :terminal-1,
                     :terminal-b :terminal-2}
           config-2 {:parent-config :parent-2,
                     :terminal-a :terminal-2,
                     :terminal-b :terminal-3}
           parent-1 @(rm/acquire parent-resource-manager :testing-1 config-1)
           parent-2 @(rm/acquire parent-resource-manager :testing-1 config-2)
           terminal-a-1 (:terminal-a parent-1)
           terminal-a-2 (:terminal-a parent-2)
           terminal-b-1 (:terminal-b parent-1)
           terminal-b-2 (:terminal-b parent-2)]
       (is (identical? @terminal-b-1 @terminal-a-2))
       (is (and (do-something-magical parent-1)
                (do-something-magical parent-2)))
       (rm/terminate parent-1)
       (is (thrown? Exception (do-something-magical parent-1)))
       (is (-> terminal-b-1 :cached-resource deref .get do-something-magical))
       (rm/release* parent-resource-manager :testing-1 config-1 true)
       (rm/release* parent-resource-manager :testing-1 config-2 true))))
  (testing "parent recovers from other parent's force-terminating"
    (rm/testing-for-resource-leaks
     (let [config-1 {:parent-config :parent-1,
                     :terminal-a :terminal-1,
                     :terminal-b :terminal-2}
           config-2 {:parent-config :parent-2,
                     :terminal-a :terminal-2,
                     :terminal-b :terminal-3}
           parent-1 @(rm/acquire parent-resource-manager :testing-1 config-1)
           parent-2 @(rm/acquire parent-resource-manager :testing-1 config-2)
           terminal-a-1 @(:terminal-a parent-1)
           terminal-a-2 @(:terminal-a parent-2)
           terminal-b-1 @(:terminal-b parent-1)
           terminal-b-2 @(:terminal-b parent-2)]
       (is (identical? terminal-b-1 terminal-a-2))
       (is (and (do-something-magical parent-1)
                (do-something-magical parent-2)))
       (rm/force-terminate parent-1)
       (is (thrown? Exception (do-something-magical parent-1)))
       (is (thrown? Exception (do-something-magical terminal-b-1)))
       (is (thrown? Exception (do-something-magical terminal-a-2)))
       (is (do-something-magical parent-2))
       (rm/release* parent-resource-manager :testing-1 config-1 true)
       (rm/release* parent-resource-manager :testing-1 config-2 true))))
  (testing "reacquires don't get thrashy."
    (reset! terminal-total-all-time 0)
    (reset-terminal-metrics)
    (rm/testing-for-resource-leaks
     (let [number-of-parents 20
           terminal-1 @(rm/acquire terminal-resource-manager :testing :terminal-1)
           terminal-2 @(rm/acquire terminal-resource-manager :testing :terminal-2)
           configs (for [i (range number-of-parents)
                         :let [terminals (shuffle [:terminal-1 :terminal-2])]]
                     {:parent-config (keyword (str "parent-" i)),
                      :terminal-a (first terminals),
                      :terminal-b (second terminals)})
           parents (doall
                    (for [config configs]
                      @(rm/acquire parent-resource-manager :testing config)))
           result (atom true)
           go-latch (CountDownLatch. 1)
           stop-latch (CountDownLatch. number-of-parents)]
       (is (= 2 (em/count (terminal-counter))))
       (is (= 2 @terminal-total-all-time))
       (doseq [parent parents]
         (future
           (.await ^CountDownLatch go-latch)
           (let [r (do-something-magical parent)] ;; reacquires if necessary!
             (swap! result #(and % r)))
           (.countDown ^CountDownLatch stop-latch)))
       (rm/terminate terminal-1)
       (rm/terminate terminal-2)
       (is (thrown? Exception (do-something-magical terminal-1)))
       (is (thrown? Exception (do-something-magical terminal-2)))
       (.countDown ^CountDownLatch go-latch)
       (.await ^CountDownLatch stop-latch)
       (is (true? @result))
       (is (= 2 (em/count (terminal-counter))))
       (is (= 4 @terminal-total-all-time))
       (rm/release* terminal-resource-manager :testing :terminal-1)
       (rm/release* terminal-resource-manager :testing :terminal-2)
       (doseq [config configs]
         (rm/release* parent-resource-manager :testing config true))))))

(deftest test:overriding
  (testing "regular behavior..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [terminals (doall
                      (for [user-id (range 20)]
                        @(rm/acquire terminal-resource-manager user-id :terminal)))]
       (is (= 1 @terminal-total-all-time))
       (is (= 1 (count (into #{} terminals))))
       (dotimes [user-id 20]
         (rm/release* terminal-resource-manager user-id :terminal true)))))
  (testing "overridden discriminator..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [terminals (doall
                      (for [user-id (range 20)]
                        (rm/overriding [terminal-resource-manager {:discriminator (fn [user-id config] [user-id config])}]
                                       @(rm/acquire terminal-resource-manager user-id :terminal))))]
       (is (= 20 @terminal-total-all-time))
       (is (= 20 (count (into #{} terminals))))
       (rm/overriding [terminal-resource-manager {:discriminator (fn [user-id config] [user-id config])}]
                      (dotimes [user-id 20]
                        (rm/release* terminal-resource-manager user-id :terminal true))))))
  (testing "overridden constructor..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [terminals (doall
                      (for [user-id (range 20)]
                        (rm/overriding [terminal-resource-manager
                                        {:constructor
                                         (fn [description config]
                                           (map->FakeTerminalResourceForTesting
                                            {:config config}))}]
                                       @(rm/acquire terminal-resource-manager user-id :terminal))))]
       (is (= 0 @terminal-total-all-time))
       (is (apply = #{:magic-for-the-win}
                  (for [terminal terminals]
                    (do-something-magical terminal))))
       (dotimes [user-id 20]
         (rm/release* terminal-resource-manager user-id :terminal true)))))
  (testing "overridden terminator..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [termination-counter (atom 0)
           terminals (doall
                      (for [user-id (range 20)]
                        @(rm/acquire terminal-resource-manager user-id :terminal)))]
       (is (= 1 @terminal-total-all-time))
       (is (= 1 (count (into #{} terminals))))
       (dotimes [user-id 19]
         (rm/overriding [terminal-resource-manager
                                        {:terminator
                                         (fn [_] (swap! termination-counter inc))}]
                        (rm/release* terminal-resource-manager user-id :terminal true)))
       (is (= 0 @termination-counter))
       (rm/overriding [terminal-resource-manager
                       {:terminator
                        (fn [_] (swap! termination-counter inc))}]
                      (rm/release* terminal-resource-manager 19 :terminal true))
       (is (= 1 @termination-counter)))))
  (testing "override (allthethings)..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [termination-counter (atom 0)]
       (rm/overriding [terminal-resource-manager {:constructor
                                                  (fn [description config]
                                                    (map->FakeTerminalResourceForTesting
                                                     {:config config}))
                                                  :discriminator
                                                  (fn [user-id config] [user-id config])
                                                  :terminator
                                                  (fn [_] (swap! termination-counter inc))}]
                      (let [terminals (doall
                                       (for [user-id (range 20)]
                                         @(rm/acquire terminal-resource-manager user-id :terminal)))]
                        (is (= 0 @terminal-total-all-time))
                        (is (= 20 (count (into #{} terminals))))
                        (dotimes [user-id 19]
                          (rm/release* terminal-resource-manager user-id :terminal true))
                        (is (= 19 @termination-counter))
                        (rm/release* terminal-resource-manager 19 :terminal true)
                        (is (= 20 @termination-counter)))))))
  (testing "across thread boundaries..."
    (reset! terminal-total-all-time 0)
    (rm/testing-for-resource-leaks
     (let [creation-threads (atom #{})]
       (rm/overriding [parent-resource-manager {:discriminator (fn [user-id config] user-id)}
                       terminal-resource-manager {:discriminator (fn [user-id config]
                                                                   (swap! creation-threads conj (Thread/currentThread))
                                                                   [user-id config])}]
                      (let [terminal-1-config {:id :terminal-1, :initiate-sleep 100}
                            terminal-2-config {:id :terminal-2, :initiate-sleep 100, :terminate-sleep 100}
                            config {:terminal-a terminal-1-config :terminal-b terminal-2-config}
                            this-thread (Thread/currentThread)]
                        ;; terminal gets created in some other thread:
                        @(future (rm/acquire parent-resource-manager :testing-1 config))
                        (is (= 2 (em/count (terminal-counter)))) ;; normally 1
                        @(future (rm/acquire parent-resource-manager :testing-2 config))
                        (is (= 4 (em/count (terminal-counter)))) ;; normally 1
                        (is (not (empty? @creation-threads)))
                        (is (not (contains? @creation-threads this-thread)))
                        (rm/release* parent-resource-manager :testing-1 config true)
                        (rm/release* parent-resource-manager :testing-2 config true)
                        (is (= 0 (em/count (terminal-counter))))))))))

(deftest test:acquiring
  (testing "let fails in this regard"
    (rm/testing-for-resource-leaks
     (let [handle (rm/acquire terminal-resource-manager :outer :thingy)
           thingy @handle]
       (is (thrown? Exception
                    (let [another-use (rm/acquire terminal-resource-manager :inner :thingy)
                          _ (rm/release handle true)
                          _ (throw (Exception. "hi"))]
                      (try (do-something-magical @another-use)
                           (finally (rm/release* terminal-resource-manager :inner :thingy))))))
       (is (rm/initiated? thingy))
       (is (rm/initiated? handle))
       (rm/release* terminal-resource-manager :inner :thingy)
       (is (rm/terminated? thingy))
       (is (rm/terminated? handle)))))
  (testing "acquiring cleans up"
    (rm/testing-for-resource-leaks
     (let [handle (rm/acquire terminal-resource-manager :outer :thingy)
           thingy @handle]
       (is (thrown? Exception
                    (rm/acquiring [another-use (rm/acquire terminal-resource-manager :inner :thingy)
                                   _ (rm/release handle true)
                                   _ (throw (Exception. "hi"))]
                                  (do-something-magical @another-use))))
       (is (not (rm/initiated? thingy)))
       (is (not (rm/initiated? handle)))))))

(deftest test:release-all
  (testing "release all releases all"
    (reset-terminal-metrics)
    (rm/testing-for-resource-leaks
     (let [terminals (doall
                      (for [user (range 10)]
                        (rm/acquire terminal-resource-manager user :same-thing)))
           one-more (rm/acquire terminal-resource-manager :funky :same-thing)
           two-more (rm/acquire terminal-resource-manager :thing-2 :same-thing)
           res-id (rm/resource-id two-more)]
       (is (= 1 (em/count (terminal-counter))))
       (is (every? rm/initiated? terminals))
       (rm/release one-more true)
       (is (= 1 (em/count (terminal-counter))))
       (is (every? rm/initiated? terminals))
       (rm/release-all two-more res-id)
       (is (= 0 (em/count (terminal-counter))))
       (is (every? rm/terminated? terminals))))))

(rm/defmanager computation-manager
  :discriminator
  (fn [_ {:keys [f args]}] ;; [user-id config]
    [f args])
  :constructor
  (fn [[f args] _] ;; [unique-identifier config]
    (future (apply f args))))
