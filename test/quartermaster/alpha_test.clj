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

(ns quartermaster.alpha-test
  (:require [quartermaster.core :as rm]
            [quartermaster.alpha :as alpha]
            [quartermaster.error :as qe]
            [clojure.test :refer :all])
  (:import [java.util.concurrent CountDownLatch]))

(rm/defmanager test-manager
  :discriminator (fn [user config] config)
  :constructor (fn [disc config] config))

(deftest release-user
  (let [r1 (rm/acquire test-manager :user1 :config1)
        r2 (rm/acquire test-manager :user1 :config2)

        r3 (rm/acquire test-manager :user2 :config1)]

    (is (= 2 (count (rm/handle-map test-manager))))
    (is (= #{#{:user1 :user2} #{:user1}}
           (->> test-manager :resources deref vals (map :handles) set)))

    (alpha/release-user test-manager :user1)

    (is (= 1 (count (rm/handle-map test-manager))))
    (is (= #{#{:user2}}
           (->> test-manager :resources deref vals (map :handles) set)))))
