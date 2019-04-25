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

(ns quartermaster.core
  (:require [quartermaster.error :refer [raise-terminated raise-manager-error]]
            [utiliva.control :refer [guarded-let locking-when ?->>]]
            [utiliva.core :refer [map-vals locking-vswap!]]
            [recide.core :refer [insist]] ; TODO - waiting on recidere rename
            [recide.sanex.logging :refer [warn warnf infof debugf]]
            [barometer.core :as metrics]
            [clojure.set :refer [superset?]]
            [clojure.string :refer [join]]
            [clojure.test :refer [is]])
  (:import [java.util UUID]
           [java.lang.ref WeakReference]
           [clojure.lang Var IDeref]))

;; PROTOCOLS

(defprotocol SharedResource
  (resource-id [_]
    "Returns this resource's unique resource-id if initiated; nil if terminated.")
  (initiate [_]
    "If not initiated, puts this resource into a usable state by generating a
     unique resource-id and acquiring any resources it requires (threads,
     channels, SharedResources, etc.). Returns an updated version of this
     resource. Calling initiate on a ResourceReference behaves as though calling
     initiate on the resource itself. Idempotent. May raise an error with type
     :quartermaster/initiate-error.")
  (initiated? [_] "Has this resource been initiated?")
  (status* [_] "Returns a (possibly empty) map of resource-specific status information.")
  (terminate [_]
    "Used to tell this resource to release all resources it required and to clean
     itself up, putting itself into an unusable state. Attempting to use a terminated
     resource may result in a :quartermaster/resource-terminated error being raised.
     Idempotent.")
  (force-terminate [_]
    "Used by resource managers when reinitiating a resource. The shared resource
     that has already been terminated will do nothing. Otherwise, it is expected to
     force-terminate all its own resources before terminating itself. Idempotent."))

(defprotocol ResourceHandle
  (resource [handle]
    "Returns the resource. Potentially repairs the resource. Use this or deref for
     cases that don't involve registered `on-acquisition` functions. This, and deref,
     should never be called inside a registerd `on-acquisition` function.")
  (resource* [handle]
    "Returns the resource. Makes no attempt to check health or make repairs. Use
     this inside registered `on-acquisition` functions.")
  (reinitiate [handle] [handle target-resource-id]
    "If this resource handle is still valid, this attempts to force-terminate the
     resource and all its own resource, then reinitiate a new one, swapping the new
     resource for the old in the resource manager.

     Any user-ids registered as having handlers on the old resource will still
     be registered as having handlers on the new resource, and the new resource
     will be accessible through all the old ResourceHandles.

     If the resource implements SharedResource, then the target-resource-id is
     required; the reinitiation only takes effect if the target-resource-id matches
     the resource id of the resource in the manager. target-resource-id should be
     left off if the resource does not implement SharedResource.

     Returns this ResourceHandle upon completion. If an exception is encountered
     this may raise a :quartermaster/reinitiate-error error.")
  (release [handle] [handle block-on-release?]
    "Informs the manager that the user-id associated with this handle no longer
     wishes to maintain its handle on the resource, exactly as if the user called
     SharedResourceManager/release*. ResourceHandles are expected to hold only
     WeakReferences to the resource s.t. leaking a ResourceHandle does not prevent
     GC of the resource itself once the manager has dropped it.

     Returns true upon completion. If :quartermaster/terminate-error is raised, this
     will attempt to log a warning.")
  (release-all [handle target-resource-id] [handle target-resource-id block-on-release?]
    "Informs the manager that the user-id associated with this handle wishes
    to release *all* handles to this resource by *all* users, then to terminate
    the resource. ONLY TAKES EFFECT IF target-resource-id MATCHES THE RESOURCE
    KNOWN TO THE MANAGER. Use with caution.

    If the resource does not implement SharedResource, nil may be passed as
    target-resource-id.

    Returns true upon completion. If :quartermaster/terminate-error is raised, this will
    attempt to log a warning."))

(defprotocol SharedResourceManager
  (acquire [rm user-id description] [rm user-id description on-acquisition]
    "Attempts to acquire the resource uniquely determined by description.
     Returns a ResourceReference on success. May raise various
     :quartermaster/* errors correponding to the management/lifecycle
     stage in which exceptions were encountered.

     The mapping from description to unique resource is determined by means
     of the equivalence class indicated by a discriminator passed to the
     resource manager's constructor.

     If an appropriate resource does not exist already, it will be created by
     passing description to the construction function passed to the resource
     manager's constructor. If the constructed resource implements
     SharedResource, it will be initiated before being cached by the manager.

     Either way, this method will ensure that user-id is registered as having
     a handle on the resource. The resource will be evicted from the cache when
     the last user-id has released the resource. If it is a SharedResource, it
     will be terminated as well.

     Calling `acquire` a second time with the same arguments should merely
     ensure that the resource, if SharedResource, is initiated, then return
     a new handle object without any additional side effects.

     Some users require that certain side-effects be executed the first time
     the resource is created and any time it is dynamically swapped out by the
     resource manager for any reason. If this is the case, an optional
     on-acquisition argument may be passed. It is expected to be a function
     of one argument, a ResourceReference, which executes the side effects.
     The returned ResourceReference will ensure that this function is called
     appropriately.")
  (reacquire [rm user-id description]
    "Attempts to fetch a resource already acquired. Useful if that resource
     instance was terminated for any reason. If user-id doesn't already have a handle
     on it, then this throws an exception. Mainly used by ResourceReference.

     DOES NOT RETURN A ResourceReference. Returns the resource itself.")
  (release* [rm user-id description] [rm user-id description block-on-release?]
    "Informs the manager that user-id no longer wishes to maintain its handle on
     the resource determined by description.

     If user-id has the last handle on the resource, all references to the resource
     are dropped by the resource manager.

     If a dropped resource implements SharedResource, it is terminated when it
     is dropped. By default, this occurs asynchronously; if block-on-release?
     is true, release will not return until the resource has been terminated.
     If the only other references to the resource are ResourceReferences, the
     resource may be garbage-collected.

     Returns true. May raise a :quartermaster/terminate-error error.
     Mainly intended for internal use by ResourceHandle/release.")
  (release-all* [rm user-id description target-resource-id] [rm user-id description target-resource-id block-on-release?]
    "If there is a resource determined by description being managed by this
    manager, this method will attempt atomically to release *all* handles to
    this resource and then terminate the resource -- BUT ONLY IF THAT RESOURCE'S
    ID IS resource-id. Use with caution. If the manager's discriminator function
    depends at all on user-id, then user-id must be correspond to that used at
    the resource's acquisition. In all other cases, its value is irrelevant.

    Returns true. May raise a :quartermaster/terminate-error error.
    Mainly intended for use by ResourceReference/release-all.")
  (reinitiate*
    [rm user-id description]
    [rm user-id description resource-id]
    [rm user-id description resource-id on-acquisition]
    "If there is a resource determined by description being managed by
     this manager, this method will attempt to destroy the old resource and
     replace it with a new one, BUT ONLY IF THAT RESOURCE'S ID IS resource-id.

     All user-ids registered as having handlers on the old resource will
     still be registered as having handlers on the replacement resource.
     Any ResourceReference that pointed to the old resource will now point
     to the new resource.

     The old resource is destroyed by means of `force-terminate`. It is
     expected that the old resource will transitively call `force-terminate`
     on all its own resources.

     Forcibly terminated resources are expected to raise a :shared-resource/terminated
     error, so that users may know to retry or to attempt to reinitiate resources.

     DOES NOT RETURN A ResourceReference. Returns the resource itself.
     Mainly intended for use by ResourceHandle/reinitiate.")
  (handle-map [rm]
    "Returns a snapshot {equivalence-class --> #{& user-ids}} for the resource-manager."))

;; LOGIC

(defn new-resource-id
  "Returns a unique identifier. Takes the form
  {:uuid UUID,
   :tag tag}
  with the :tag key optional."
  ([] {:uuid (UUID/randomUUID)})
  ([tag] (assoc (new-resource-id) :tag tag)))

(defn status
  "Returns the resource's status map, with the following keys merged in:
  #{:quartermaster/initiated?}"
  [resource]
  (insist (satisfies? SharedResource resource))
  (assoc (status* resource)
         :quartermaster/initiated? (initiated? resource)))

(defn terminated?
  "Is it in a terminated state?"
  [resource]
  (not (initiated? resource)))

(defn- reacquire-for-reference
  "Calls reacquire on the manager and resets the cached weak reference to the
  result. Returns the resource itself."
  [{:as resource-reference
    :keys [manager user-id description cached-resource on-reacquire]}]
  (let [resource (reacquire manager user-id description)
        new? (not (identical? resource (.get ^WeakReference @cached-resource)))]
    (when new? (on-reacquire resource-reference))
    (reset! cached-resource (WeakReference. resource))
    resource))

(defn- get-resource
  [{:as resource-reference
    :keys [manager user-id description cached-resource on-reacquire]}]
  (locking-when ;; If there are no side-effects, we don't really care about locking.
   (not (identical? on-reacquire identity)) resource-reference
   (if-let [resource (.get ^WeakReference @cached-resource)]
     resource
     (reacquire-for-reference resource-reference))))

(defrecord ResourceReference
           [manager ;; source of truth!
            satisfies-shared-resource? ;; boolean
            cached-resource ;; atom containing a WeakReference to the resource as last known
            user-id ;; every resource reference is tied to a particular user
            description ;; description used to acquire this reference
            on-reacquire] ;; atom containing a side-effecting fn to call on new resource at reacquire
  SharedResource
  (resource-id [this]
    (when satisfies-shared-resource?
      (resource-id (get-resource this))))
  (initiate [this]
    (if satisfies-shared-resource?
      (initiate (get-resource this))
      (.get ^WeakReference @cached-resource)))
  (initiated? [this]
    (if satisfies-shared-resource?
      (initiated? (get-resource this))
      true)) ;; We assume!
  (status* [this]
    (if satisfies-shared-resource?
      (status* (get-resource this))
      {}))
  (terminate [this]
    (if satisfies-shared-resource?
      (terminate (get-resource this))
      (.get ^WeakReference @cached-resource)))
  (force-terminate [this]
    (if satisfies-shared-resource?
      (force-terminate (get-resource this))
      (.get ^WeakReference @cached-resource)))
  ResourceHandle
  (resource [this]
    ;; If there are no side-effects, there's no need to lock here
    (locking-when (not (identical? on-reacquire identity)) this
                  (let [resource (get-resource this)]
                    (if (or (not (satisfies? SharedResource resource))
                            (terminated? resource))
                      (reacquire-for-reference this)
                      resource))))
  (resource* [this] (.get ^WeakReference @cached-resource))
  (reinitiate [this]
    (when satisfies-shared-resource?
      (raise-manager-error :reinitiate-error
                           "ResourceHandle/reinitiate requires target-resource-id for SharedResources."
                           {:rm-id (:rm-id manager)}))
    (reinitiate this nil))
  (reinitiate [this target-resource-id]
    (locking this ;; Reinitiating is a side-effect.
      (let [old-resource (.get ^WeakReference @cached-resource)
            new-resource (reinitiate* manager user-id description target-resource-id)]
        (reset! cached-resource (WeakReference. new-resource))
        (when (not= new-resource old-resource) ;; reinitiate based on stale information is a no-op.
          (on-reacquire this))))
    this)
  (release [this]
    (release* manager user-id description))
  (release [this block-on-release?]
    (release* manager user-id description block-on-release?))
  (release-all [this target-resource-id]
    (release-all* manager user-id description target-resource-id))
  (release-all [this target-resource-id block-on-release?]
    (release-all* manager user-id description target-resource-id block-on-release?))
  IDeref
  (deref [this] (resource this)))

(defn- create-reference
  "Creates a ResourceReference to represent this user's handle on this resource."
  ([manager user-id description resource]
   (create-reference manager user-id description resource identity))
  ([manager user-id description resource on-reacquire]
   (let [shared-resource? (satisfies? SharedResource resource)]
     (map->ResourceReference
      {:manager manager
       :satisfies-shared-resource? shared-resource?
       :cached-resource (atom (WeakReference. resource))
       :user-id user-id
       :description description
       :on-reacquire (memoize on-reacquire)}))))

(defmacro ^:private count-reliably
  "Will increment or decrement a counter every time the body of code runs, but
  *only* if it does so without throwing any exceptions."
  [counter inc-or-dec & body]
  `(let [r# (do ~@body)]
     ;; Exceptions will bubble upward.
     ~(case inc-or-dec
        inc `(metrics/increment ~counter)
        dec `(metrics/decrement ~counter))
     r#))

(defn- apply-discriminator
  "Uses discriminate to map description to a unique id of the resource.
  Raises :quartermaster/discriminate-error if exception is caught."
  [{:as manager :keys [discriminator rm-id]} user-id description method]
  (let [discriminator @discriminator]
    (try (discriminator user-id description)
         (catch Throwable e
           (raise-manager-error :discriminate-error
                                (format "resource description failed to resolve for %s." method)
                                {:rm-id rm-id}
                                e)))))

(defn- construct
  "Constructs a resource. If applicable, initiates it. Throws appropriate
  exceptions when errors are encountered."
  [{:as manager :keys [rm-id constructor construction-timer extant-counter]} description unique-image]
  (metrics/with-timer (metrics/get-metric metrics/DEFAULT construction-timer)
    (let [constructor @constructor
          resource (try (constructor unique-image description)
                        (catch Throwable e
                          (raise-manager-error :construct-error
                                               "constructor threw an exception."
                                               {:rm-id rm-id}
                                               e)))]
      (cond-> resource
        (satisfies? SharedResource resource)
        (as-> resource
              (try (initiate resource)
                   (catch Throwable e
                     (raise-manager-error :initiate-error
                                          "initiate threw an exception."
                                          {:rm-id rm-id}
                                          e))))))))

(defn- release-helper
  "Terminates the resource if appropriate. Logs a warning if exception is caught."
  [{:as manager :keys [rm-id terminator termination-timer]} resource block-on-release?]
  (when (or (not= terminator identity)
            (satisfies? SharedResource resource))
    (let [terminator @terminator
          do-terminate (fn []
                         (metrics/with-timer (metrics/get-metric metrics/DEFAULT termination-timer)
                           (try (-> resource
                                    (?->> (satisfies? SharedResource) terminate)
                                    terminator)
                                (catch Throwable e
                                  (warnf e
                                         "Resource manager %s encountered an error while terminating resource."
                                         rm-id)))))]
      (if block-on-release?
        (do-terminate)
        (future (do-terminate))))))

(defn- resource-swapper
  "Constructs a function suitable for use with locking-vswap! within the
  ResourceManager."
  ([{:as manager :keys [constructor terminator]} user-id description unique-image res-id]
   (resource-swapper manager user-id description unique-image res-id false))
  ([{:as manager :keys [constructor terminator]} user-id description unique-image res-id override!]
   (let [rm-id (:rm-id manager)
         terminator @terminator]
     (fn [resources]
       (cond (not (contains? resources unique-image))
             (do (warnf "Attempted to reinitiate nonextant resource from manager %s." rm-id)
                 resources)
             (and (not override!)
                  (nil? res-id)
                  (satisfies? SharedResource (get-in resources [unique-image :resource])))
             (do (warnf "Resource manager %s ignoring attempt to reinitiate a SharedResource with a nil resource-id")
                 resources)
             (and (not override!)
                  (satisfies? SharedResource (get-in resources [unique-image :resource]))
                  (not= res-id (resource-id (get-in resources [unique-image :resource]))))
             (do (debugf "Attempted to reinitiate stale resource from manager %s." rm-id)
                 resources)
             (not (contains? (get-in resources [unique-image :handles]) user-id))
             (do (warnf "Unknown user attempted to reinitiate a resource in manager %s." rm-id)
                 resources)
             :else
             (do (infof "Attempting to reinitiate resource in manager %s." rm-id)
                 (-> (get-in resources [unique-image :resource])
                     (?->> (satisfies? SharedResource) force-terminate)
                     terminator)
                 (infof "resource-swapper terminated resource in manager %s." rm-id)
                 ;; At this exact moment, any user with a handle on the resource
                 ;; may encounter Problems and attempt to reinitiate.
                 (assoc-in resources [unique-image :resource]
                           (construct manager description unique-image))))))))

(defn- ensure-still-good
  "If the resource has been terminated, stands a new one back up in its place.
  (Side effecty!) Locks the entire resource table during the swap.
  Returns {:recreated? Boolean,
            :resource resource}"
  ([{:as manager :keys [resources constructor terminator]}
    user-id description unique-image resource]
   (cond (not (satisfies? SharedResource resource))
         {:recreated? false, ;; VV hack: -- stops this from executing in the *middle* of a reinitiate swap.
          :resource (get-in (locking-vswap! resources identity) [unique-image :resource])}
         ;; the resource is still in good shape:
         (initiated? resource)
         {:recreated? false,
          :resource resource}
         ;; it's been terminated, probably (we hope) via reinitiation code.
         ;; But the existing handles still represent valid users, so we just
         ;; swap in a new good resource.
         :else
         (let [resource (get-in
                         (locking-vswap! resources
                                         (let [resource-id (when (satisfies? SharedResource resource) (resource-id resource))]
                                           (resource-swapper manager user-id description unique-image resource-id true)))
                         [unique-image :resource])]
           {:recreated? true,
            :resource resource}))))

(defn- fetch*
  "Returns the ResourceTable if it exists; nil otherwise."
  [this unique-image]
  (-> this :resources deref (get unique-image)))

(defrecord ResourceTable [resource handles])

(defn- add-new-user
  "Locks and swaps the volatile `resources` to add a new user for a resource, assuming the resource
  still exists. Returns ::no-such-resource/::handle-exists/::handle-added."
  [{:as manager :keys [resources rm-id extant-counter]}
   unique-image user-id description]
  (let [success? (volatile! false)]
    (locking-vswap! resources
                    (fn [resources]
                      (cond (not (contains? resources unique-image))
                            (do (vreset! success? ::no-such-resource)
                                resources)
                            (some? (get-in resources [unique-image :handles user-id]))
                            (do (vreset! success? ::handle-exists)
                                resources)
                            :else
                            (do (vreset! success? ::handle-added)
                                (update-in resources [unique-image :handles] conj user-id)))))
    @success?))

(defn- terminate-resource
  [resource {:as manager :keys [terminator rm-id termination-timer]} block-on-release?]
  (let [terminator @terminator]
    (when (or (not= terminator identity)
              (satisfies? SharedResource resource))
      (letfn [(do-terminate []
                (metrics/with-timer (metrics/get-metric metrics/DEFAULT termination-timer)
                  (try (-> resource
                           (?->> (satisfies? SharedResource) terminate)
                           terminator)
                       (catch Throwable e
                         (warnf e "Resource manager %s encountered an error while terminating resource."
                                rm-id)))))]
        (if block-on-release?
          (do-terminate)
          (future (do-terminate)))))))

(defn- ensured-reference
  "Creates and returns a reference to the resource. Rebuilds the resource if necessary, triggering any
  on-acquisition fn. Also calls on-acquisition fn if optional trigger-on-acquisition? is true.
  Regardless of the value of trigger-on-acquisition, on-acquisition will be called on the resource if
  the resource had to be created again."
  ([manager user-id description unique-image resource on-acquisition]
   (ensured-reference manager user-id description unique-image resource on-acquisition false))
  ([manager user-id description unique-image resource on-acquisition trigger-on-acquisition?]
   (let [{:keys [resource recreated?]} (ensure-still-good manager user-id description unique-image resource)
         reference (create-reference manager user-id description resource on-acquisition)]
     (when (or recreated? trigger-on-acquisition?) (on-acquisition reference))
     reference)))

;; Revised process to delinearize resource creation:
;; 1. Lock the in-flight map (L1)
;;   1.a. if there's a registered lock (L2), return it (L2)
;;   1.b. if there's not, make one (L2), then return it (L2)
;; 2. Release L1.
;;
;; 3. Lock L2.
;;   3.a. Check the in-flight map to ensure L2 is still extant; if not, release L2 and start all over
;;   3.b. Check the extant map for resource existence
;;     3.b.i. If it does not exist, create it, lock-swap it (L3) into extant-map.
;;
;;   3.c. Lock the in-flight map (L1)
;;     3.c.i. Remove the in-flight lock object (L2).
;;   3.d. Release L1.
;;
;;   3.e. Return the resource in extant-map.
;; 4. Release L2.

(defn- acquire-in-flight-lock
  "Locks access to the locks table. Retrieves (creating if necessary) lock object
  corresponding to unique-image."
  [{:as manager :keys [locks]} unique-image]
  (locking locks
    (or (get @locks unique-image)
        (-> (vswap! locks assoc unique-image (Object.))
            (get unique-image)))))

(defn- ensure-resource-handle
  "If the resource exists, adds a handle for the user to the resource, then returns the resource.
  Otherwise, constructs a new one, and *then* lock-swaps into the resources table, also giving new-user
  a handle on the resource. ONLY SAFE WHEN RUN IN ISOLATION PER unique-image."
  [{:as manager :keys [extant-counter resources rm-id]} description unique-image new-user]
  (if-let [{:keys [resource handles]} (get @resources unique-image)]
    ;; resource entry exists!
    (if (contains? handles new-user)
      resource
      (if (identical? ::handle-added (add-new-user manager unique-image new-user description))
        resource
        ;; ::no-such-resource:
        (recur manager description unique-image new-user)))
    (let [resource (construct manager description unique-image)]
      (try
        (locking-vswap! resources
                        (fn [resources]
                          (count-reliably (metrics/get-metric metrics/DEFAULT extant-counter) inc
                                          (->> (->ResourceTable resource #{new-user})
                                               (assoc resources unique-image)))))
        (catch InterruptedException e
          (try
            (terminate resource)
            (catch Throwable e
              (warnf e "The resource manager %s constructed a resource during normal operations, then received a thread interrupt. It tried to terminate this resource to prevent leaks but encountered an exception." rm-id)))
          (throw e)))
      resource)))

(defn- ensure-resource-handle-in-isolation
  "Returns (creating if necessary) the resource. Enforces isolation/linearization per unique-image."
  [{:as manager :keys [rm-id locks resources]} description unique-image new-user]
  ;; Acquire a lock object:
  (let [lock (acquire-in-flight-lock manager unique-image)
        return (locking lock
                 ;; Check the in-flight map to ensure this lock is still extant;
                 (if (not= lock (get @locks unique-image))
                   ;; if not, start over.
                   ::recur              ; <- cannot recur inside lock
                   ;; Create the resource if necessary:
                   (let [resource (ensure-resource-handle manager description unique-image new-user)]
                     ;; Lock in-flight map to remove lock object:
                     (loop [attempts 1000
                            state ::start]
                       (if (> attempts 0)
                         (cond (nil? state)
                               (raise-manager-error :bizarre "could not destroy lock." {:rm-id rm-id})
                               (or (identical? ::start state)
                                   (identical? ::interrupted state))
                               (recur (dec attempts)
                                      (try
                                        (locking-vswap! locks dissoc unique-image)
                                        (catch InterruptedException e ::interrupted))))))
                     ;; Return resource:
                     resource)))]
    (if (identical? return ::recur)
      (recur manager description unique-image new-user)
      return)))

(defn- drop-user
  "Returns {} / {::dropped-resource resource}. Should be run inside a lock local to the
  resource determined by unique-image."
  [{:as manager :keys [rm-id resources extant-counter]} unique-image user-id]
  (let [return (volatile! {})]
    (locking-vswap! resources
                    (fn [resources]
                      (if (contains? resources unique-image)
                        (let [resources' (update-in resources [unique-image :handles] disj user-id)]
                          (if-not (empty? (get-in resources' [unique-image :handles]))
                            resources'
                            (count-reliably (metrics/get-metric metrics/DEFAULT extant-counter) dec
                                            (vswap! return assoc ::dropped-resource (get-in resources' [unique-image :resource]))
                                            (dissoc resources' unique-image))))
                        (do (warnf "Attempted to release handle for nonextant resource from manager %s" rm-id)
                            resources))))
    @return))

(defn- drop-user-in-isolation
  [{:as manager :keys [locks rm-id]} unique-image user-id block-on-release?]
  (letfn [(drop-fn []
            (let [lock (acquire-in-flight-lock manager unique-image)
                  return (locking lock
                           (if (not= lock (get @locks unique-image))
                             ::recur
                             (let [result (drop-user manager unique-image user-id)]
                               (when-let [resource (::dropped-resource result)]
                                 (try
                                   (terminate-resource resource manager block-on-release?)
                                   (catch Throwable e
                                     (warnf e "Possible resource leak: an exception occurred while terminating a resource after release. Resource manager: %s" rm-id)))))))]
              (if (identical? return ::recur)
                (recur)
                true)))]
    (if block-on-release?
      (drop-fn)
      (future (drop-fn)))))

(def ^:dynamic *always-block-on-release* false)

(defrecord ResourceManager
           [rm-id ;; used for logging and debugging
            resources ;; volatile {k --> #ResourceTable[resource, #{ids}]}
            discriminator ;; var containing function of <user-id, resource-description> -> unique identifier for resource
            constructor  ;; var containing function of <resource-description, unique-identifier> -> resource
            terminator ;; var containing function always called on each resource terminated by the resource manager.
            locks ;; volatile {k --> Object} used for locking
            construction-timer
            termination-timer
            extant-counter]
  SharedResourceManager
  (handle-map [_]
    (into {}
          (for [[resource-key resource] @resources]
            [resource-key (:handles resource)])))
  (acquire [this user-id description] (acquire this user-id description identity))
  (acquire [manager user-id description on-acquisition]
    (let [unique-image (apply-discriminator manager user-id description 'acquire)]
      (if-let [{:keys [resource handles]} (fetch* manager unique-image)]
        ;; resource exists; handle may or may not exist:
        (let [add-user-result (add-new-user manager unique-image user-id description)
              trigger-on-acquisition? (identical? add-user-result ::handle-added)]
          (case add-user-result
            ::no-such-resource
            (recur user-id description on-acquisition)
            (ensured-reference manager
                               user-id
                               description
                               unique-image
                               resource
                               on-acquisition
                               trigger-on-acquisition?)))
        ;; resource does not exist:
        (let [resource (ensure-resource-handle-in-isolation manager description unique-image user-id)
              reference (create-reference manager user-id description resource on-acquisition)]
          (on-acquisition reference)
          reference))))
  (reacquire [this user-id description]
    (let [unique-image (apply-discriminator this user-id description 'reacquire)]
      (locking resources
        (if-let [{:keys [resource handles]} (fetch* this unique-image)]
          (if (contains? handles user-id)
            (:resource (ensure-still-good this user-id description unique-image resource))
            (raise-manager-error :no-such-handle "reacquire failed." {:rm-id rm-id}))
          (raise-manager-error :no-such-resource "reacquire failed." {:rm-id rm-id})))))
  (release* [this id description]
    (release* this id description false))
  (release* [this user-id description block-on-release?]
    (let [unique-image (apply-discriminator this user-id description 'release)
          block-on-release? (or *always-block-on-release* block-on-release?)]
      (drop-user-in-isolation this unique-image user-id block-on-release?)
      true))
  (release-all* [this user-id description res-id] (release-all* this user-id description res-id false))
  (release-all* [this user-id description res-id block-on-release?]
    (let [unique-image (apply-discriminator this user-id description 'release-all!)
          block-on-release (or *always-block-on-release* block-on-release?)]
      (do (locking-vswap!
           resources
           (fn [resources]
             (if-let [resource (get-in resources [unique-image :resource])]
               (count-reliably (metrics/get-metric metrics/DEFAULT extant-counter) dec
                               (infof "Attempting to release all handles on a resource from manager %s." rm-id)
                               (release-helper this resource block-on-release?)
                               (dissoc resources unique-image))
               (do (warnf "Attempted to release all handles from nonextant resource in manager %s." rm-id)
                   resources))))
          true)))
  (reinitiate* [this user-id description] (reinitiate* this user-id description nil))
  (reinitiate* [this user-id description res-id]
    (let [unique-image (apply-discriminator this user-id description 'reinitiate*)]
      (locking-vswap! resources (resource-swapper this user-id description unique-image res-id))
      (reacquire this user-id description))))

;; Defines an abstract resource manager.
(def all-resource-managers (atom {}))

(defn all-handle-maps
  "Use to get a not-quite-consistent snapshot of the handle maps from every
   resource manager."
  []
  (into {} (map-vals handle-map) @all-resource-managers))

(defn resource-manager
  "rm-id - used for logging and debugging
   discriminate - var containing function of <user-id, resource-description> -> unique identifier for resource
   constructor - var containing function of <unique-identifier, resource-description> -> new resource
   terminator - (optional) var containing function that the resource-manager will always call on the resource at termination.

  Defines and registers three metrics associated with this manager:
  <namespace>.<manager-name>.construction-timer
       Times the actual construction of resources. For SharedResources, this includes
       calling `initiate`.
  <namespace>.<manager-name>.termination-timer
       Times the actual termination of resources. For SharedResources, this includes
       calling `terminate`.
  <namespace>.<manager-name>.extant-counter
       Tracks how many unique resources exist at any point in time.

  defmanager is preferred outside of testing."
  ([rm-id discriminate constructor] (resource-manager rm-id discriminate constructor identity))
  ([rm-id discriminate constructor terminator]
   (let [mgr-metrics-id (join "." ((juxt namespace name) rm-id))
         con-timer-name (format "%s.%s" mgr-metrics-id "construction.timer")
         term-timer-name (format "%s.%s" mgr-metrics-id "termination.timer")
         counter-name (format "%s.%s" mgr-metrics-id "extant.counter")
         mgr (map->ResourceManager
              {:rm-id rm-id
               :resources (volatile! {})
               :locks (volatile! {})
               :discriminator discriminate
               :constructor constructor
               :terminator terminator
               :construction-timer con-timer-name
               :termination-timer term-timer-name
               :extant-counter counter-name})
         construction-timer (metrics/timer
                             (format "Timer for resource construction and initiation in %s."
                                     rm-id))
         termination-timer (metrics/timer
                            (format "Timer for resource destruction in %s."
                                    rm-id))
         extant-counter (metrics/counter
                         (format "Counts how many resources are extant in %s."
                                 rm-id))]
     (metrics/register-all metrics/DEFAULT
                           {con-timer-name construction-timer
                            term-timer-name termination-timer
                            counter-name extant-counter})
     (when (contains? @all-resource-managers rm-id) ;; Unlikely to be stale given use patterns
       (warnf "Registering resource manager with duplicate rm-id: %s. Overwriting." rm-id))
     (swap! all-resource-managers assoc rm-id mgr)
     mgr)))

(def ^:private auto-releaser-sym (gensym))
(defmacro auto-releaser
  "Can only be used within the context of a construction function passed to defmanager. When
  a resource is constructed, it is sometimes desirable that the resource know how to release
  itself from all handles. This macro constructs a function of one argument (the resource itself),
  with an optional second argument (block-on-release?) that will call release-all* on the
  resource-manager.

  If the discrimination function employs the user-id, then you MUST pass in the user-id here.
  If and only if the determinating function ignores the user-id, you can freely use the single-arity
  version."
  ([user-id resource-description]
   (when-not (contains? &env auto-releaser-sym)
     (throw (IllegalStateException. "auto-releaser cannot be called outside of the constructor function passed to defmanager.")))
   `(~auto-releaser-sym ~user-id ~resource-description))
  ([resource-description]
   (when-not (contains? &env auto-releaser-sym)
     (throw (IllegalStateException. "auto-releaser cannot be called outside of the constructor function passed to defmanager.")))
   `(~auto-releaser-sym nil ~resource-description)))

(def ^:private auto-reinitiater-sym (gensym))
(defmacro auto-reinitiater
  "Can only be used within the context of defmanager. When a resource is constructed, it is
  sometimes desirable that the resource know how to reinitiate itself. This macro constructs a
  function of one argument (the resource itself), that will ask the resource-manager to reinitiate
  that resource without removing any acquired handles.

  If the discrimination function employs the user-id, then you MUST pass in the user-id here.
  If and only if the determinating function ignores the user-id, you can freely use the single-arity
  version."
  ([user-id resource-description]
   (when-not (contains? &env auto-reinitiater-sym)
     (throw (IllegalStateException. "auto-reinitiater cannot be called outside of the constructor function passed to defmanager.")))
   `(~auto-reinitiater-sym ~user-id ~resource-description))
  ([resource-description]
   (when-not (contains? &env auto-reinitiater-sym)
     (throw (IllegalStateException. "auto-reinitiater cannot be called outside of the constructor function passed to defmanager.")))
   `(~auto-reinitiater-sym nil ~resource-description)))

(def ^:private supported-options #{:constructor :discriminator :terminator})

(defmacro defmanager
  "Creates a new resource manager defined by the supplied :discriminator, :constructor,
  and (optionally) :terminator.

  Defines and registers three metrics associated with this manager:
  <namespace>.<manager-name>.construction-timer
       Times the actual construction of resources. For SharedResources, this includes
       calling `initiate`.
  <namespace>.<manager-name>.termination-timer
       Times the actual termination of resources. For SharedResources, this includes
       calling `terminate`.
  <namespace>.<manager-name>.extant-counter
       Tracks how many unique resources exist at any point in time."
  [mgr-name & {:as options}]
  (let [mgr-id (symbol (str *ns*) (str mgr-name))]
    (insist (contains? options :constructor)
            "defmanager requires :constructor.")
    (insist (contains? options :discriminator)
            "defmanager requires :discriminator")
    (doseq [option (keys options)]
      (when-not (contains? supported-options option)
        (throw (IllegalArgumentException. (format "Unknown option %s passed to defmanager." option)))))
    `(do (declare ~mgr-name)
         (let [constructor-fn#
               (letfn [(~auto-reinitiater-sym [user-id# input#]
                         (fn [resource#]
                           (reinitiate* ~mgr-name user-id# input# (resource-id resource#))))
                       (~auto-releaser-sym [user-id# input#]
                         (fn
                           ([resource# block?#] (release-all* ~mgr-name user-id# input# (resource-id resource#) block?#))
                           ([resource#] (release-all* ~mgr-name user-id# input# (resource-id resource#)))))]
                 ;; We evaluate the constructor definition within a scope that provides bindings for the
                 ;; auto-reinitiate var and the auto-release var. See the macros auto-release &
                 ;; auto-reinitiater for the details.
                 ~(:constructor options))]
           (let [discriminator# (.setDynamic (Var/create ~(:discriminator options)))
                 constructor# (.setDynamic (Var/create constructor-fn#))
                 terminator# (.setDynamic (Var/create ~(or (:terminator options) `identity)))]
             (def ~mgr-name
               (resource-manager '~mgr-id discriminator# constructor# terminator#)))))))

;;;;;;;;;;;;;;;;;;;;;;;
;; UTILITY FUNCTIONS ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defmacro ensure-initiated!
  "Raises a :shared-resource/terminated error if the resource is terminated!"
  ([resource message] `(ensure-initiated! ~resource ~message {}))
  ([resource message data]
   `(when (terminated? ~resource)
      (raise-terminated ~message ~data))))

(defmacro identify-resource-leaks
  [& body]
  `(binding [*always-block-on-release* true]
     (let [before# (all-handle-maps)]
       ~@body
       (let [after# (all-handle-maps)
             no-resources-leaked?# (fn [old-handles# handles#]
                                     (every? #(and (contains? old-handles# (key %))
                                                   (superset? (get old-handles# (key %))
                                                              (val %)))
                                             handles#))]
         (for [[manager-id# handles#] after#
               :let [old-handles# (get before# manager-id#)]
               :when (not (no-resources-leaked?# old-handles# handles#))]
           manager-id#)))))

(defmacro testing-for-resource-leaks
  "For use in clojure.test tests. Tests that any resources acquired in the
  body are also released in the body. Binds *always-block-on-release* to true
  during the execution of body."
  [& body]
  `(let [leaked# (identify-resource-leaks ~@body)]
     (is (empty? leaked#))))

(defn- parse-overrides
  [& {:as manager->overrides}]
  (for [[manager overrides] manager->overrides
        [fn-type override] overrides]
    (do (insist (contains? supported-options fn-type)
                (format "Unknown option: %s" fn-type))
        [(list fn-type manager) override])))

(defmacro overriding
  "Allows temporary redefinition of one or more resource-manager's parameters
   (:discriminator, :constructor, :terminator). Especially useful in tests.

  (overriding [manager {& options}
               manager {& options}
               ...]
      & body)"
  [manager->overrides & body]
  (let [manager->overrides (apply parse-overrides manager->overrides)]
    `(with-bindings ~(into {} manager->overrides)
       ~@body)))

(defmacro acquiring
  "A guarded-let (utils.control) that releases symbols whose inits resolve
  to a ResourceHandle, but only if an exception is thrown in the bindings
  block."
  [bindings & body]
  `(guarded-let #(when (satisfies? ResourceHandle %) (release % true))
                ~bindings
                ~@body))
