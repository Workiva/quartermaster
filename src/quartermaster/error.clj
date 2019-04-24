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

(ns quartermaster.error
  (:require [recide.core :refer [deferror-group deferror]]))

(deferror terminated
  :quartermaster/resource-terminated
  "Terminated resource")

(deferror-group manager-error
  (:quartermaster [:rm-id])
  (discriminate-error "Error in uniqueness resolution")
  (swap-error "Error when swapping a resource")
  (construct-error "Error in constructing resource")
  (initiate-error "Error in initiating resource")
  (terminate-error "Error in terminating resource")
  (reinitiate-error "Error in reinitiating resource")
  (reacquire-error "Error in reacquiring resource")
  (no-such-handle "The specified user has no handle on this resource")
  (no-such-resource "No such resource exists")
  (bizarre "A bizarre error occured in a resource manager"))
