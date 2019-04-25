# Quartermaster

[![Clojars Project](https://img.shields.io/clojars/v/com.workiva/quartermaster.svg)](https://clojars.org/com.workiva/quartermaster) [![CircleCI](https://circleci.com/gh/Workiva/quartermaster/tree/master.svg?style=svg)](https://circleci.com/gh/Workiva/quartermaster/tree/master)

<!-- toc -->

- [Overview](#overview)
- [Basic Functionality (Silly Example)](#basic-functionality-silly-example)
- [API Documentation](#api-documentation)
- [SharedResource](#sharedresource)
  * [`(initiate [r])`](#initiate-r)
  * [`(terminate [r])`](#terminate-r)
  * [`(force-terminate [r])`](#force-terminate-r)
  * [`(resource-id [r])`](#resource-id-r)
  * [`(initiated? [r])`](#initiated-r)
  * [`(status* [r])`](#status-r)
- [Managing Hierarchies: Resources All the Way Down](#managing-hierarchies-resources-all-the-way-down)
- [Resource Managers](#resource-managers)
  * [Construction](#construction)
    + [`(resource-manager [rm-id discriminate constructor] [rm-id discriminate constructor terminator])`](#resource-manager-rm-id-discriminate-constructor-rm-id-discriminate-constructor-terminator)
    + [`(defmanager [manager-name & {:as options}])`](#defmanager-manager-name--as-options)
    + [`(auto-releaser [resource-description] [user-id resource-description])`](#auto-releaser-resource-description-user-id-resource-description)
    + [`(auto-reinitiater [resource-description] [user-id resource-description])`](#auto-reinitiater-resource-description-user-id-resource-description)
  * [Protocol Methods](#protocol-methods)
    + [(acquire [rm user-id description] [rm user-id description on-acquisition])](#acquire-rm-user-id-description-rm-user-id-description-on-acquisition)
    + [(reacquire [rm user-id description])](#reacquire-rm-user-id-description)
    + [(handle-map [rm])](#handle-map-rm)
  * [Manager Metrics](#manager-metrics)
- [Resource References](#resource-references)
  * [`SharedResource` protocol](#sharedresource-protocol)
  * [`ResourceHandle` protocol](#resourcehandle-protocol)
    + [`(resource [handle])`](#resource-handle)
    + [`(resource* [handle])`](#resource-handle)
    + [`(reinitiate [handle] [handle target-resource-id])`](#reinitiate-handle-handle-target-resource-id)
    + [`(release [handle] [handle block-on-release?])`](#release-handle-handle-block-on-release)
    + [`(release-all [handle target-resource-id] [handle target-resource-id block-on-release?])`](#release-all-handle-target-resource-id-handle-target-resource-id-block-on-release)
- [Resource Leaks](#resource-leaks)
    + [`(acquiring [bindings & body])`](#acquiring-bindings--body)
- [Mocking & Testing](#mocking--testing)
    + [`(all-handle-maps)`](#all-handle-maps)
    + [`(testing-for-resource-leaks [& body])`](#testing-for-resource-leaks--body)
    + [`(overriding [manager->overrides & body])`](#overriding-manager-overrides--body)
- [Maintainers and Contributors](#maintainers-and-contributors)
  * [Active Maintainers](#active-maintainers)
  * [Previous Contributors](#previous-contributors)

<!-- tocstop -->

## Overview

**Quartermaster** is a small library for managing hierarchies of shared resources in a manner both simple and tolerant to failure.

This model is based on defining ResourceManagers that are *uniquely* responsible for serving requests for the resources they manage. Any process that requires a resource simply requests one from the appropriate resource manager. When the process is finished with the resource, it informs the resource manager that this is the case, so that the resource can be cleaned up, at least if there are no other users. 

To create a resource manager, you need only supply two pieces of information:

1. Constructor: how to construct a requested resource from a user's id and a resource description (e.g., a configuration map)
2. [Discriminator](http://planetmath.org/discriminatorfunction): how to determine [equivalence classes](https://en.wikipedia.org/wiki/Equivalence_relation); i.e., whether to construct a new resource or return one already extant.

When the resource manager services requests, rather than returning the resource directly, it returns a token that implements [ResourceHandle](), representing that user's handle on the resource. This token also implements `IDeref` for simple access to the underlying resource. 

If the underlying resource has been replaced in the resource manager by some user calling [`reinitiate`](), then calling [`resource`]()/[`deref`]() will automatically fetch the new one; if the resource has been [`terminated?`]() through some other means, the token will ask the resource manager to create a replacement. All this happens automatically, under the hood. The resource reference thus represents a stable means of acquiring the "latest" appropriate resource whatever the instability of the system, *without requiring any knowledge of how that resource was acquired in the first place.*

Moreover, this token holds only a [WeakReference](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/WeakReference.html) to the resource, so processes holding onto the token will not prevent the JVM from garbage-collecting resources otherwise expunged from their managers.

## Basic Functionality (Silly Example)

This is a silly example in which a `future` stands in for a heavier resource (such as a database connection or a shared cache):

```clojure
(def work-counter (atom 0))

;; There exists some very expensive work:
(defn some-difficult-work
  [& args]
  (swap! work-counter inc)
  (reduce + args))

;; We know that multiple processes will need identical computations at the "same" time,
;; so let's manage them:
(q/defmanager computation-manager
  :discriminator
  (fn [_ {:keys [f args]}] ;; [user-id config]
    [f args]) ;; if they use identical f and args, they are considered equivalent.
  :constructor
  (fn [[f args] _] ;; [unique-identifier config]
    (future (apply f args))))

;; Process-1 starts requesting work:
(def resource-1 (q/acquire computation-manager
                           :process-1 ;; user-id, should be globally unique to consuming entity.
                           {:f some-difficult-work, :args [1 2 3]}))
;; (= 1 @work-counter)

(def resource-2 (q/acquire computation-manager :process-1 {:f some-difficult-work, :args [2 3 4]}))
;; (= 2 @work-counter)

;; Process-2 requests work:
(def resource-3 (q/acquire computation-manager
                           :process-2 ;; different consuming entity
                           {:f some-difficult-work, :args [1 2 3]}))
;; (= 2 @work-counter)

;; resource-1 is a ResourceReference. deref to get the thing underneath:
(assert (future? @resource-1))
(assert (= 6 @@resource-1))

;; resource-3 is a ResourceReference as well, pointing to the same future:
(assert (= @resource-1 @resource-3))
(assert (= 6 @@resource-3))

;; Process-1 releases the future when it's done with the resource:
(q/release resource-1 true) ;; true is the optional argument to block on release.

(try @resource-1
  (catch Exception e
    (println (.getMessage e))))
;; "The specified user has no handle on this resource: reacquire failed."

;; But Process-2 still has a handle on the future:
(assert (= 6 @@resource-3))

;; Process-2 has a long-running subroutine that doesn't know anything about resources.
;; Process-2 hands it off:
(def subroutine-var resource-3)

;; Process-2 decides it's time to clean up its resources. 
(q/release resource-3 true)

;; Now the subroutine will fail on any attempt to use the resource, and you will know exactly why!
(try @subroutine-var
  (catch Exception e
    (println (.getMessage e))))
;; "The specified user has no handle on this resource: reacquire failed."

;; Moreover, the ResourceReference holds only a WeakReference to the future, so that the
;; future will be garbage-collected even in the face of Process-2's subroutine being a bad actor.

;; Even so, processes should remember to clean up after themselves. At this point the other computation
;; still exists and will not be garbage collected until Process-1 releases it:
(assert (= 9 @@resource-2))

;; And throughout all this only the two future 'resources' were created:
(assert (= 2 @work-counter))

```

## API Documentation

[Clojure API documentation can be found here.](/documentation/index.html)

## SharedResource

In the example above, there is no special initialization nor teardown required for the `future`. But in the likely event that resources in your application *do* have such requirements, this library has got your back, by means of the protocol [`SharedResource`](). Provide a constructor that yields an *uninitiated resource*, and implement this protocol on that resource, and the managers will take care of the rest.

Every `SharedResource` is considered to be in one of two states: initiated, or terminated.

### `(initiate [r])`

If not initiated, this puts the resource into a usable state by generating a unique resource-id and acquiring any other resources it requires (threads, channels, SharedResources, etc.). Returns an updated version of this resource. May raise an error of type :quartermaster/initiate-error. **The implementation should be idempotent.**

### `(terminate [r])`

Used to tell this resource to *release* all its own resources and to clean itself up, putting itself into an unusable state. Attempting to use a terminated resource may result in a `:quartermaster/resource-terminated` error being raised. **The implementation should be idempotent.**

### `(force-terminate [r])`

This method can be used to force a resource to tear itself down and all its own resources recursively.  That is, it should call `force-terminate` on all its own resources and then call `terminate` on itself. This is used by resource managers to enable "restart the stack" functionality without resource leaks. **The implementation should be idempotent.**

### `(resource-id [r])`

Returns this resource's unique resource-id if initiated; nil if terminated. (See [`new-resource-id`]()) This should probably be in an atom.

### `(initiated? [r])`

Is this resource in an initiated or terminated state?

### `(status* [r])`

Returns a status map, possibly empty. Provides a universal mechanism for resource-specific inspection. When calling `quartermaster.core/status` on a resource, this map is returned with the key `:quartermaster/initiated?` assoced in.

## Managing Hierarchies: Resources All the Way Down

Let's say your application has its own protocols that enable Cool Stuff:

```clojure
(defprotocol MagicalResource
  (do-something-magical [_] "This really amazing resource does something really amazing."))

(defn my-magic-sauce [& ss] (clojure.string/join " + " ss))
```

But to implement this protocol, your application requires the creation of certain resources that make demands on system memory, CPU, and/or network sockets. Let's implement a SharedResource.

```clojure
(defrecord TerminalResource [resource-id config]
  q/SharedResource
  (resource-id [_] (some-> resource-id deref)) ;; resource-id here doubles as mutable initiated? state.
  (initiated? [this] (-> this q/resource-id boolean))
  (status* [_] {})
  (initiate [this]
    (if (q/initiated? this)
      this ;; idempotency
      (assoc this
             :resource-id
             (atom (q/new-resource-id)))))
  (terminate [this]
    (if (q/terminated? this)
      this ;; idempotency
      (do (reset! resource-id nil) ;; records are immutable
          (assoc this :resource-id nil))))
  (force-terminate [this]
    (q/terminate this)) ;; there are no other SharedResources to clean up.
  MagicalResource
  (do-something-magical [this]
    (if (q/initiated? this)
      {:magic-for-the-win (get config :magic-value)}
      (q/raise-terminated "Magic ain't free." {}))))
```

This resource will require management:

```clojure
(q/defmanager terminal-resource-manager
  :discriminator ;; equivalence determined by :magic-value in config:
  (fn [user-id config] (select-keys config [:magic-value])
  :constructor
  (fn [_ config] (map->TerminalResource {:config config})))
;; Note that this constructor *only* creates, and *does not* initiate, the resource.
```

But we're not done. The awesome magic of your application is only truly accessible through a higher-level resource, which will itself acquire and use two of these terminal resources:

```clojure
(defrecord ParentResource [resource-id config terminal-a terminal-b]
  q/SharedResource
  (resource-id [_] (some-> resource-id deref))
  (initiated? [this] (-> this q/resource-id boolean))
  (status* [_] {})
  (initiate [this]
    (if (q/initiated? this)
      this ;; idempotency
           ;; `acquiring` is like `let`, but ensures that, if an exception is thrown in the middle of the
           ;; binding blocks, resources acquired so far are properly released.
      (q/acquiring [res-id (q/new-resource-id)
                    ;; Acquire the terminal resources I need, passing configuration maps for each:
                    terminal-a (q/acquire terminal-resource-manager res-id (:terminal-a config))
                    terminal-b (q/acquire terminal-resource-manager res-id (:terminal-b config))]
        (assoc this
               :resource-id (atom res-id) ;; again doubling as mutable initiated? state
               :terminal-a terminal-a
               :terminal-b terminal-b))))
  (terminate [this]
    (if (q/terminated? this)
      this ;; idempotency
      (do (reset! resource-id nil) ;; so all immutable records know the terminated state
         (q/release terminal-a true) ;; release my SharedResources
         (q/release terminal-b true)
         (assoc this
                :resource-id nil
                :terminal-a nil
                :terminal-b nil))))
  (force-terminate [this]
    (if (q/terminated? this)
      this
      (do (q/force-terminate terminal-a) ;; force-terminate recursively
          (q/force-terminate terminal-b)
          (q/terminate this)))) ;; then call terminate on myself
  MagicalResource
  (do-something-magical [this]
    (if (q/initiated? this)
      (merge-with my-magic-sauce
                 (do-something-magical @terminal-a)
                 (do-something-magical @terminal-b))
      (q/raise-terminated "No magic for you." {}))))
```

Let's manage this sucker:

```clojure
(q/defmanager parent-resource-manager
  :discriminator ;; equivalence determined by the terminal magic-values
                 ;; user-id is ignored, so users will share resources.
  (fn [user-id config] [(get-in config [:terminal-a :magic-value])
                        (get-in config [:terminal-b :magic-value])])
  :constructor
  (fn [parent-id config] (map->ParentResource {:config config})))
```

And now let's play with these, and see what we can do with quartermaster's tools:

```clojure
(def terminal-1-config {:magic-value "unicorns"})
(def terminal-2-config {:magic-value "dragons"})
(def terminal-3-config {:magic-value "whiskey"})

;; Presumably you'll use something like spec to define and validate your configuration maps. :)

(def parent-1-config {:terminal-a terminal-1-config,
                      :terminal-b terminal-2-config})

(def parent-2-config {:terminal-a terminal-2-config,
                      :terminal-b terminal-3-config})

(def reference-to-parent-1
     (q/acquire parent-resource-manager :service-startup parent-1-config))

(def reference-to-parent-2
     (q/acquire parent-resource-manager :service-startup parent-2-config))

;; These are ResourceReferences. For illustration purposes, we will also grab
;; their current values.
(def original-parent-1 @reference-to-parent-1)
(def original-parent-2 @reference-to-parent-2)

;; Likewise, let's get the terminals internal to parent-1:
(def parent-1-terminal-a @(:terminal-a original-parent-1))
(def parent-1-terminal-b @(:terminal-b original-parent-1))

;; And parent-2:
(def parent-2-terminal-a @(:terminal-a original-parent-2))
(def parent-2-terminal-b @(:terminal-b original-parent-2))

;; Parent-1's terminal-b should be the same as parent-2's terminal-a:
(assert (identical? parent-1-terminal-b parent-2-terminal-a))

;; But the references themselves are distinct:
(assert (not (identical? (:terminal-b original-parent-1)
                         (:terminal-a original-parent-2))))

;; Normally you'd pass around the ResourceReference, dereferencing as needed:
(assert (= "unicorns + dragons"
           (do-something-magical @reference-to-parent-1)))

(assert (= "dragons + whiskey"
           (do-something-magical @reference-to-parent-2)))

;; The reason is that systems are subject to failure. 
;; A COSMIC RAY FORCE-TERMINATES PARENT-1:
(q/force-terminate original-parent-1)

;; Dude, this resource is completely borked:
(try (do-something-magical original-parent-1)
     (catch Exception e
       (println (.getMessage e))))
;; "Terminated resource: No magic for you."

;; Because it was force-terminated, its terminals were also force-terminated:
(try (do-something-magical parent-1-terminal-b)
     (catch Exception e
       (println (.getMessage e))))
;; "Terminated resource: Magic ain't free."

;; And because a terminal was shared with parent-2, parent-2's terminal WILL ALSO FAIL:
(try (do-something-magical parent-2-terminal-a)
     (catch Exception e
       (println (.getMessage e))))
;; "Terminated resource: Magic ain't free."

;; And yet parent-2 still works!
(assert (= "dragons + whiskey"
           (do-something-magical @reference-to-parent-2)))

;; This is because the ResourceReference and ResourceManager automatically dumped the old
;; non-working terminal resource and created a new one:
(assert (not (identical? @(:terminal-a original-parent-2)
                         parent-2-terminal-a)))

;; And so along this theme, the *reference* to parent-1 itself still works!
(assert (= "unicorns + dragons"
           (do-something-magical @reference-to-parent-1)))

;; A new terminal-a had to be made for parent-1, but an equivalent replacement for its terminal-b
;; had already been made for parent-2, so it got re-used:
(assert (identical? @(:terminal-b @reference-to-parent-1)
                    @(:terminal-a original-parent-2)))

;; It should be noted that the references to the resources can be freely passed to "dumb"
;; processes without fear of resource leaks. Had we not def'ed the original resources for purposes
;; of illustration, no number of ResourceReferences would stop the JVM from garbage-collecting.

;; This recovery works so smoothly because doing this intentionally is one of the features of this library.

;; Suppose that, for some Reasons™, you want to "restart" a resource. You can just call `reinitiate` on 
;; your ResourceReference:
(q/reinitiate reference-to-parent-1)

;; This calls force-terminate on the underlying resource, and then, unlike the example above, it
;; immediately constructs and initiates a new one.
```

## Resource Managers

### Construction

There exists a functional constructor for resource manager that is **not** recommended in normal use of this library, although it is made available in the interests of allowing system-specific tools to be built. There is also a simple macro that that *is* recommended for normal use.

#### `(resource-manager [rm-id discriminate constructor] [rm-id discriminate constructor terminator])`

* `rm-id` - symbol used for logging and debugging
* `discriminate` - a **var** containing function of <user-id, resource-description> -> unique identifier for resource
* `constructor` - a **var** containing function of <unique-identifier, resource-description> -> new resource
* `terminator` - (optional) a **var** containing a function that the resource-manager will always call on the resource after termination. This is primarily useful for defining resource managers wrapping objects that do not implement `SharedResource`.

This method will also defines and register three metrics associated with this manager:

*  namespace.manager-name.construction-timer:
  - Times the actual construction of resources. For SharedResources, this includes calling `initiate`.
*  namespace.manager-name.termination-timer
  - Times the actual termination of resources. For SharedResources, this includes calling `terminate`.
*  namespace.manager-name.extant-counter
  - Tracks how many unique resources exist at any point in time.

This is considered a low-level method and `defmanager` is preferred in most cases.

#### `(defmanager [manager-name & {:as options}])`

Creates a new resource manager defined by the supplied `:discriminator`, `:constructor`, and (optionally) `:terminator`. Because this calls `resource-manager` under the hood, this creates and registers the same metrics as `resource-manager`.

#### `(auto-releaser [resource-description] [user-id resource-description])`

This macro can only be used within the context of a literal function passed to `defmanager` under the key `:constructor` (it throws an exception at compile-time otherwise). When a resource is constructed, it is sometimes desirable that the resource know how to release itself from all handles. This macro expands to the definition of a function that takes a single argument (the resource itself), with an optional second argument (block-on-release?). When called, this function will call `release-all*` on the resource-manager.

*If the discriminator employs the user-id, then you MUST pass in the user-id here. If and only if the determinating function ignores the user-id, you can freely use the single-arity version.*

#### `(auto-reinitiater [resource-description] [user-id resource-description])`

This macro can only be used within the context of a literal function passed to `defmanager` under the key `:constructor`. When a resource is constructed, it is sometimes desirable that the resource know how to `reinitiate` (destroy and replace) itself. This macro expands to a function that takes a single argument (the resource itself). When called, this function will request that the resource-manager reinitiate that resource without invalidating any acquired `ResourceHandle`.

*If the discriminator employs the user-id, then you MUST pass in the user-id here. If and only if the determinating function ignores the user-id, you can freely use the single-arity version.*


### Protocol Methods

The primary means of interacting with resource managers are through two methods on the ResourceManager protocol: `acquire` and `reacquire`. There are [other methods in that protocol](), primarily intended for use by `ResourceReference` internals.

#### (acquire [rm user-id description] [rm user-id description on-acquisition])
Attempts to acquire the resource uniquely determined by `description`. Returns a ResourceReference on success. May raise various `:quartermaster/*` errors correponding to the management/lifecycle stage in which exceptions were encountered.

The mapping from description to unique resource is determined by means of the equivalence class indicated by a discriminator passed to the resource manager's constructor.

If an appropriate resource does not exist already, it will be created by passing description to the construction function passed to the resource manager's constructor. If the constructed resource implements SharedResource, it will be initiated before being cached by the manager.

Either way, this method will ensure that `user-id` is registered as having a handle on the resource. The resource will be evicted from the cache when the last user-id has released the resource. If it is a SharedResource, it will be terminated as well.

Calling `acquire` a second time with the same arguments should merely ensure that the resource, if SharedResource, is initiated, then return a new handle object without any additional side effects.

Some users require that certain side-effects be executed the first time the resource is created and any time it is dynamically swapped out by the resource manager for any reason. If this is the case, an optional on-acquisition argument may be passed. It is expected to be a function of one argument (a ResourceReference) which executes the side effects. The returned ResourceReference will ensure that this function is called appropriately."

#### (reacquire [rm user-id description])

Attempts to fetch a resource already acquired. Useful if that resource instance was terminated for any reason. If `user-id` does not currently have a handle on it, then this throws an exception. Mainly used by ResourceReference. DOES NOT RETURN A ResourceReference. Returns the resource itself.

#### (handle-map [rm])

Returns a snapshot `{equivalence-class --> #{& user-ids}}` for this resource-manager. Useful for debugging, logging, metrics, etc.

### Manager Metrics

When a resource manager is created, it is given a name. If `defmanager` is used, this defaults to the symbol of the var in which it is interned. This name is used to create and register metrics in the [clj-metrics default registry]().

1. namespace.manager-name.construction-timer: Times the actual construction of resources. For SharedResources, this includes calling `initiate`.
2. namespace.manager-name.termination-timer: Times the actual termination of resources. For SharedResources, this includes calling `terminate`.
3. namespace.manager-name.extant-counter: Tracks how many unique resources exist at any point in time.


## Resource References

When you request a resource from a `ResourceManager`, what you get back is a [`ResourceReference`](), an immutable and stable reference to a potentially mutable and stateful resource. This object implements [`ResourceHandle`](), [`SharedResource`](), and [`IDeref`](). It thus captures and contains *all* the details of the consuming process acquiring and setting up the resource, and encapsulates it in an immutable object to be used by processes which know nothing about the resource's creation, state, or recovery model. Under the hood, this `ResourceReference` captures:

1. the configuration that the resource manager used to determine shared equivalency and to construct the resource;
2. the resource's own self-initialization process (*e.g.*, transitive acquisition of other resources);
3. and, when applicable, any user-directed initialization process (*e.g.*, reads, writes, callback registrations, etc.).

If for any reason the underlying resource enters a terminated state before the user's handle on the resource has been released, any call to `resource`/`deref` on the `ResourceReference` will automatically trigger a process that repeats all three of these steps and returns a re-initialized and functionally-equivalent resource ready for nominal use.

### `SharedResource` protocol

When the `ResourceManager` wraps a resource that implements `SharedResource`, then its own implementation of the protocol merely delegates to the underlying object. Otherwise, it tries to perform sensible no-ops:

* `initiate`: returns the object.
* `terminate`: returns the object.
* `force-terminate`: returns the object.
* `resource-id`: returns `nil`.
* `status`: returns `{:quartermaster/initiated? true}`.

### `ResourceHandle` protocol

#### `(resource [handle])`

Returns the resource. Potentially repairs the resource. Use this or `deref` for cases that don't involve registered `on-acquisition` functions. *This, and `deref`, should never be called inside a registerd `on-acquisition` function.*

#### `(resource* [handle])`

Returns the resource. Makes no attempt to check health or make repairs. *Use this inside registered `on-acquisition` functions.*

#### `(reinitiate [handle] [handle target-resource-id])`

If this resource handle is still valid, this attempts to force-terminate the resource and all its own resource, then reinitiate a new one, swapping the new resource for the old in the resource manager.

Any user-ids registered as having handlers on the old resource will still be registered as having handlers on the new resource, and the new resource will be accessible through all the old ResourceHandles.

If the resource implements SharedResource, then the `target-resource-id` is required; the reinitiation **only** takes effect if this matches the resource id of the resource known to the manager. `target-resource-id` should be left off if the resource does not implement SharedResource.

Returns this `ResourceHandle` upon completion. If an exception is encountered this may raise a `:quartermaster/reinitiate-error` error.

#### `(release [handle] [handle block-on-release?])`

Informs the manager that the user-id associated with this handle no longer wishes to maintain its handle on the resource, exactly as if the user called `SharedResourceManager/release*`. ResourceHandles are expected to hold only WeakReferences to the resource s.t. leaking a ResourceHandle does not prevent GC of the resource itself once the manager has dropped it.

Returns true. If an exception occurs during termination of a resource, a warning will be logged along with the exception.

#### `(release-all [handle target-resource-id] [handle target-resource-id block-on-release?])`
   
Informs the manager that the user-id associated with this handle wishes to release *all* handles to this resource by *all* users, then to terminate the resource. Only takes effect if `target-resource-id` matches the resource known to the manager. **Use with caution.**

If the resource does not implement SharedResource, nil may be passed as target-resource-id. Returns true upon completion. If an exception occurs during termination of a resource, a warning will be logged along with the exception.

## Resource Leaks

All the tools provided by Quartermaster are designed to work under the following assumptions:

1. Every user of a resource maintains a local cache of that resource, which it can update if that resource has been restarted via reinitiate. Even if doing this manually, detecting such a scenario could be as easy as catching a :shared-resource/terminated error. **This constraint is entirely satisfied by proper use of the ResourceReference object.**
2. Every non-leaf node in the hierarchy of managed resources implements `SharedResource` and only ever has a single resource-id from the beginning of its lifecycle to its inexorable end. This resource-id should be set at initiation and deleted at termination.
3. A resource is *constructed* with all the knowledge necessary to begin its lifecycle, but *is not yet initiated*. In particular, **no resource constructor should ever acquire other resources**; instead, this should be handled within the `initiate` method of `SharedResource`.
4. Resources must release transitively acquired resources within the `terminate` method of `SharedResource`.

Additionally, the following macro is available in quartermaster.core:

#### `(acquiring [bindings & body])`

If a resource is acquired within the binding block of a `let`, and then an exception is thrown within that same binding block, a resource leak will occur. `acquiring` is functionally equivalent to `let`, but uses [`guarded-let`]() to ensure that any `ResourceHandle` acquired in the binding block will be released *if an exception is thrown in the binding block*. Note that within the code body outside the binding block, it is still up to the user to employ proper try-catch-finally logic as appropriate.

## Mocking & Testing

Quartermaster has two primary goals: preventing resource leaks, and allowing smooth recovery from random component failiures. To that end, the library provides several tools for testing whether these goals have been met.

#### `(all-handle-maps)`

Yields a not-quite-consistent snapshot of the handle maps from every resource manager. Returns a map of `{manager-id handle-map}`.

#### `(testing-for-resource-leaks [& body])`

Tests that any resources acquired in the body are also released in the body. As a side effect, all `release` behavior executed within the body is modified to *always* block on release.

#### `(overriding [manager->overrides & body])`

Allows temporary redefinition of one or more resource-manager's parameters (`:discriminator`, `:constructor`, `:terminator`). Especially useful in tests for replacing components of your system with mocks, stubs, and/or chaotic fiends.

```clojure
  (overriding [manager {& options}
               manager {& options}
               ...]
      & body)
```
## Maintainers and Contributors

### Active Maintainers

-

### Previous Contributors

- Timothy Dean <galdre@gmail.com>
- Houston King <houston.king@workiva.com>
