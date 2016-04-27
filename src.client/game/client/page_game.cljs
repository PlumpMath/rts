(ns ^:figwheel-always game.client.page-game
  (:require
    [cljs.pprint :as pprint]
    [com.stuartsierra.component :as component]
    [jayq.core :as jayq :refer [$]]
    [promesa.core :as p]
    [cats.core :as m]
    [rum.core :as rum]
    [game.client.common :as common :refer [new-jsobj list-item data]]
    [game.client.config :as config]
    [game.client.controls :as controls]
    [game.client.renderer :as renderer]
    [game.client.routing :as routing]
    [game.client.scene :as scene]
    [game.client.socket :as socket]
    [game.client.ground-local :as ground-local]
    [sablono.core :as sablono :refer-macros [html]]
    [clojure.string :as string :refer [join]]
    [game.shared.state :as state :refer [with-simple-cause]]
    )
  (:require-macros [game.shared.macros :as macros :refer [defcom]])
  )

(defn new-system
  [params]
  (let
    [system
     {
      :params params
      :config config/config
      :renderer (new-jsobj #(new js/THREE.WebGLRenderer #js { :antialias true }))
      :scene (new-jsobj #(new js/THREE.Scene))
      :$overlay (new-jsobj #($ "<canvas/>"))
      :raycaster (new-jsobj #(new js/THREE.Raycaster))
      :camera (new-jsobj scene/get-camera)
      :light1 (new-jsobj #(new js/THREE.DirectionalLight))
      :light2 (new-jsobj #(new js/THREE.DirectionalLight))
      :light3 (new-jsobj #(new js/THREE.DirectionalLight))
      :light4 (new-jsobj #(new js/THREE.DirectionalLight))
      :ground (ground-local/new-init-ground)
      :render-stats (new-jsobj #(new js/Stats))
      :physics-stats (new-jsobj #(new js/Stats))
      :init-scene (scene/new-init-scene)
      :init-light (scene/new-init-light)
      :init-stats (scene/new-init-stats)
      :init-renderer (renderer/new-init-renderer)
      :on-resize (scene/new-on-resize)
      :controls (controls/new-controls)
      }]
    system
    ))

(defn start
  [component]
  ; (println "starting game component")
  (let
    [params 
     {
      :$page (:$page component)
      :simplex (data (:simplex component))
      }
     subsystem 
      (with-simple-cause
        #(component/start-system
          (if-let
            [s (:subsystem component)]
            (assoc s :params params)
            (new-system params))))
     component (assoc component :subsystem subsystem)
     ]
    component))

(defn stop
  [component]
  (let
    [subsystem
      (if-let
        [s (:subsystem component)]
        (with-simple-cause
          #(component/stop-system s)))
     component (assoc component :subsystem subsystem)]
    component))

(defcom
  new-game
  [simplex routing]
  [subsystem]
  start
  stop
  )
