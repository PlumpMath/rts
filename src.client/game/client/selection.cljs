(ns ^:figwheel-always game.client.selection
  (:require
    [cljs.pprint :as pprint]
    [com.stuartsierra.component :as component]
    [jayq.core :as jayq :refer [$]]
    [promesa.core :as p]
    [cats.core :as m]
    [rum.core :as rum]
    [game.client.common :as common :refer [new-jsobj list-item data unique-id]]
    [game.client.controls :as controls]
    [game.client.engine :as engine]
    [game.client.scene :as scene]
    [game.client.math :as math :refer [pi]]
    [sablono.core :as sablono :refer-macros [html]]
    [clojure.string :as string :refer [join]]
    [game.shared.state :as state :refer [with-simple-cause]]
    )
  (:require-macros [game.shared.macros :as macros :refer [defcom]])
  )

(def LEFT_MOUSE_BUTTON 1)
(def MIDDLE_MOUSE_BUTTON 2)
(def RIGHT_MOUSE_BUTTON 3)

(defn mark
  [component mesh]
  (if-let
    [circle (aget mesh "mark")]
    (do
      (-> circle .-visible (set! true))
      circle)
    (let
      [radius (-> mesh .-geometry .-boundingSphere .-radius)
       geometry (new THREE.CircleGeometry radius 32)
       mat (-> (new THREE.Matrix4) (.makeRotationX (/ pi -2)))
       _ (-> geometry (.applyMatrix mat))
       material (new THREE.MeshLambertMaterial #js { :color 0x00FF00 :opacity 0.5 :transparent true })
       circle (new THREE.Mesh geometry material)]
      (-> mesh (.add circle))
      (aset mesh "mark" circle)
      circle)))

(defn unmark-all
  [component]
  (doseq
    [selected @(:selected component)]
    (let
      [mesh (:mesh selected)
       mark (:mark selected)]
      (-> mark .-visible (set! false))))
  (reset! (:selected component) []))

(defn get-bounding-box-geometry
  ([mesh]
   (get-bounding-box-geometry mesh true))
  ([mesh clone?]
   (let
     [clone #(if clone? (.clone %) %)
      geometry
      (if
        (and
          (-> mesh (.hasOwnProperty "rts-bbox-geometry"))
          (not (undefined? (aget mesh "rts-bbox-geometry"))))
        (-> (aget mesh "rts-bbox-geometry") clone)
        (let
          [bbox (-> mesh .-geometry .-boundingBox)
           bbox-geometry (new THREE.BoxGeometry
                         (- (-> bbox .-max .-x) (-> bbox .-min .-x))
                         (- (-> bbox .-max .-y) (-> bbox .-min .-y))
                         (- (-> bbox .-max .-z) (-> bbox .-min .-z)))
           geo-translation (-> (new THREE.Vector3)
                             (.add (-> bbox .-min))
                             (.add (-> bbox .-max))
                             (.divideScalar 2))
           ]
          (-> bbox-geometry (.translate
                         (-> geo-translation .-x)
                         (-> geo-translation .-y)
                         (-> geo-translation .-z)))
          (aset mesh "rts-bbox-geometry" bbox-geometry)
          (-> bbox-geometry clone)))
      ]
     (if clone?
       (-> geometry (.applyMatrix (-> mesh .-matrixWorld))))
     geometry)))

; http://stackoverflow.com/questions/17624021/determine-if-a-mesh-is-visible-on-the-viewport-according-to-current
(defn get-screen-boxes
  [component]
  (let
    [frustum (new THREE.Frustum)
     camera-view-projection-matrix (new THREE.Matrix4)
     camera (data (:camera component))
     width @(get-in component [:scene-properties :width])
     height @(get-in component [:scene-properties :height])
     screen-boxes #js []
     ]
    (-> camera .updateMatrixWorld)
    (-> camera .-matrixWorldInverse (.getInverse (-> camera .-matrixWorld)))
    (-> camera-view-projection-matrix
      (.multiplyMatrices (-> camera .-projectionMatrix) (-> camera .-matrixWorldInverse)))
    (-> frustum (.setFromMatrix camera-view-projection-matrix))
    (doseq
      [mesh (engine/get-unit-meshes (:units component))]
      (if
        (-> frustum (.intersectsObject mesh))
        (let
          [screen-box (new THREE.Box2)]
          (doseq [vertex (-> (get-bounding-box-geometry mesh) .-vertices)]
            (-> screen-box
              (.expandByPoint
                (scene/world-to-screen-fast width height camera-view-projection-matrix vertex))))
          (let
            [box #js [
                      (-> screen-box .-min .-x)
                      (-> screen-box .-min .-y)
                      (-> screen-box .-max .-x)
                      (-> screen-box .-max .-y)
                      ]]
            (aset box "mesh" mesh)
            (-> screen-boxes (.push box))))))
      screen-boxes))

(defn get-screen-boxes-from-last-overlay-render
  [component]
  (let
    [units (:units component)
     boxes #js []]
    (doseq
      [box (vals @(:mesh-to-screenbox-map units))]
      (-> boxes (.push box)))
    boxes))

(defn
  check-intersect-screen
  [component x1 y1 x2 y2]
;  (println "check-intersect-screen" x1 y1 x2 y2)
  (let
    [screen-boxes (get-screen-boxes-from-last-overlay-render component)
     flat-selection-box #js [ #js [x1 y1 x2 y2]]
     selected-indices (js/boxIntersect screen-boxes flat-selection-box)]
    (unmark-all component)
    (reset!
      (:selected component)
      (into
        []
        (for
          [[i j] selected-indices]
          (let
            [mesh (aget (nth screen-boxes i) "mesh")
             circle (mark component mesh)]
            {
             :unit (engine/get-unit-for-mesh (:units component) mesh)
             :mesh mesh
             :mark circle
             }))))))

(defn
  rectangle-select
  [component x2 y2 update]
  (reset! (:frame-queued? component) false)
  (if
    @(:selecting? component)
    (let
      [start-pos @(:start-pos component)
       x1 (:x start-pos)
       y1 (:y start-pos)]
      (if update
        (reset! (:end-pos component) { :x x2 :y y2 }))
      (let
        [[x1 x2] (if (< x1 x2) [x1 x2] [x2 x1])
         [y1 y2] (if (< y1 y2) [y1 y2] [y2 y1])
         ]
        (if update
          (jayq/css
            (:$selection-div component)
            {
             :left x1
             :top y1
             :width (- x2 x1)
             :height (- y2 y1)
             }
            ))
        (check-intersect-screen component x1 y1 x2 y2)))))

(defn
  on-mouse-down
  [component event-data]
  ;(-> event-data .preventDefault)
  (cond
    (= (-> event-data .-which) LEFT_MOUSE_BUTTON)
    (let
      [eps 1
       x (-> event-data .-offsetX)
       y (-> event-data .-offsetY)]
      (reset! (:start-pos component) { :x (- x eps) :y (- y eps) })
      (reset! (:end-pos component) { :x (+ x eps) :y (+ y eps) })
      (reset! (:selecting? component) true)
      (->
        (:$selection-div component)
        (.removeClass "invisible")
        (jayq/css
          {
           :position "absolute"
           :left x
           :top y
           :width eps
           :height eps
           }))
      (check-intersect-screen component (- x eps) (- y eps) (+ x eps) (+ y eps)))
    (= (-> event-data .-which) RIGHT_MOUSE_BUTTON)
    (do
      (println "TODO")
      (-> event-data .preventDefault))))

(defn
  on-mouse-move
  [component event-data]
  (let
    [x2 (-> event-data .-offsetX)
     y2 (-> event-data .-offsetY)]
    (if-not @(:frame-queued? component)
      (reset! (:frame-queued? component) true)
      (js/requestAnimationFrame #(rectangle-select component x2 y2 true)))))

(defn on-mouse-up
  [component event-data]
  (reset! (:selecting? component) false)
  (-> (:$selection-div component)
    (.addClass "invisible")))

(defcom
  new-selector
  [scene init-scene params $overlay renderer camera units scene-properties]
  [$selection-layer $selection-div start-pos end-pos selecting? selected frame-queued?]
  (fn [component]
    (let
      [$selection-div (or $selection-div ($ "<div/>"))
       $selection-layer (or $selection-layer ($ "<canvas/>"))
       start-pos (or start-pos (atom nil))
       selecting? (or selecting? (atom false))
       selected (or selected (atom []))
       end-pos (or end-pos (atom nil))
       frame-queued? (or frame-queued? (atom false))
       bindns (str "selector" (unique-id (aget (data $overlay) 0)))
       mousedownevt (str "mousedown." bindns)
       mousemoveevt (str "mousemove." bindns)
       mouseupevt (str "mouseup." bindns)
       mousedblclickevt (str "dblclick." bindns)
       contextevt (str "contextmenu." bindns)
       selection-element (scene/get-view-element renderer)
       $page (:$page params)
       component
       (->
         component
         (assoc :frame-queued? frame-queued?)
         (assoc :selected selected)
         (assoc :selecting? selecting?)
         (assoc :start-pos start-pos)
         (assoc :end-pos end-pos)
         (assoc :$selection-layer $selection-layer)
         (assoc :$selection-div $selection-div))
       ]
      (-> (data $overlay) (.after $selection-layer))
      (-> $selection-layer (.addClass scene/page-class))
      (-> $selection-layer (.addClass "autoresize"))
      (-> $selection-layer (.addClass "selection-layer"))
      (controls/rebind $selection-layer mousedownevt (partial on-mouse-down component))
      (controls/rebind $selection-layer mousemoveevt (partial on-mouse-move component))
      (controls/rebind $selection-layer mouseupevt (partial on-mouse-up component))
      (controls/rebind $selection-layer contextevt controls/prevent-default)
      (controls/rebind $selection-layer mousedblclickevt controls/prevent-default)
      (-> $page (.append $selection-div))
      (-> $selection-div (.addClass "invisible"))
      (-> $selection-div (.addClass "selection-rect"))
      component))
  (fn [component] component))
