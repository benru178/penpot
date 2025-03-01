;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.selection
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.focus :as cpf]
   [app.common.files.helpers :as cfh]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as cr]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as md]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.collapse :as dwc]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.main.data.workspace.specialized-panel :as-alias dwsp]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.refs :as refs]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [clojure.set :as set]
   [linked.set :as lks]
   [potok.core :as ptk]))

(defn interrupt? [e] (= e :interrupt))

;; --- Selection Rect

(declare select-shapes-by-current-selrect)
(declare deselect-all)

(defn update-selrect
  [selrect]
  (ptk/reify ::update-selrect
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] selrect))))

(defn handle-area-selection
  [preserve? ignore-groups?]
  (ptk/reify ::handle-area-selection
    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom   (dm/get-in state [:workspace-local :zoom] 1)
            stop?  (fn [event] (or (interrupt? event) (ms/mouse-up? event)))
            stoper (rx/filter stop? stream)

            init-position @ms/mouse-position

            init-selrect  (grc/make-rect
                           (dm/get-prop init-position :x)
                           (dm/get-prop init-position :y)
                           0 0)

            calculate-selrect
            (fn [selrect [delta space?]]
              (let [selrect (-> (cr/clone selrect)
                                (cr/update! :x2 + (:x delta))
                                (cr/update! :y2 + (:y delta)))
                    selrect (if ^boolean space?
                              (-> selrect
                                  (cr/update! :x1 + (:x delta))
                                  (cr/update! :y1 + (:y delta)))
                              selrect)]
                (grc/update-rect! selrect :corners)))

            selrect-stream
            (->> ms/mouse-position
                 (rx/buffer 2 1)
                 (rx/map (fn [[from to]] (when (and from to) (gpt/to-vec from to))))
                 (rx/filter some?)
                 (rx/with-latest-from ms/keyboard-space)
                 (rx/scan calculate-selrect init-selrect)
                 (rx/filter #(or (> (dm/get-prop % :width) (/ 10 zoom))
                                 (> (dm/get-prop % :height) (/ 10 zoom))))
                 (rx/take-until stoper))]

        (rx/concat
         (if preserve?
           (rx/empty)
           (rx/of (deselect-all)))

         (rx/merge
          (->> selrect-stream
               (rx/map update-selrect))

          (->> selrect-stream
               (rx/buffer-time 100)
               (rx/map #(last %))
               (rx/dedupe)
               (rx/map #(select-shapes-by-current-selrect preserve? ignore-groups?))))

         (->> (rx/of (update-selrect nil))
              ;; We need the async so the current event finishes before updating the selrect
              ;; otherwise the `on-click` event will trigger with a `nil` selrect
              (rx/observe-on :async)))))))

;; --- Toggle shape's selection status (selected or deselected)

(defn select-shape
  ([id]
   (select-shape id false))

  ([id toggle?]
   (dm/assert! (uuid? id))
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (update-in [:workspace-local :selected] d/toggle-selection id toggle?)
           (assoc-in [:workspace-local :last-selected] id)))

     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (:current-page-id state)
             objects (wsh/lookup-page-objects state page-id)]
         (rx/of
          (dwc/expand-all-parents [id] objects)
          ::dwsp/interrupt))))))

(defn select-prev-shape
  ([]
   (ptk/reify ::select-prev-shape
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected       (wsh/lookup-selected state)
             count-selected (count selected)
             first-selected (first selected)
             page-id        (:current-page-id state)
             objects        (wsh/lookup-page-objects state page-id)
             current        (get objects first-selected)
             parent         (get objects (:parent-id current))
             sibling-ids    (:shapes parent)
             current-index  (d/index-of sibling-ids first-selected)
             sibling        (if (= (dec (count sibling-ids)) current-index)
                              (first sibling-ids)
                              (nth sibling-ids (inc current-index)))]

         (cond
           (= 1 count-selected)
           (rx/of (select-shape sibling))

           (> count-selected 1)
           (rx/of (select-shape first-selected))))))))

(defn select-next-shape
  ([]
   (ptk/reify ::select-next-shape
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected       (wsh/lookup-selected state)
             count-selected (count selected)
             first-selected (first selected)
             page-id        (:current-page-id state)
             objects        (wsh/lookup-page-objects state page-id)
             current        (get objects first-selected)
             parent         (get objects (:parent-id current))
             sibling-ids    (:shapes parent)
             current-index  (d/index-of sibling-ids first-selected)
             sibling        (if (= 0 current-index)
                              (last sibling-ids)
                              (nth sibling-ids (dec current-index)))]
         (cond
           (= 1 count-selected)
           (rx/of (select-shape sibling))

           (> count-selected 1)
           (rx/of (select-shape first-selected))))))))

(defn deselect-shape
  [id]
  (dm/assert! (uuid? id))
  (ptk/reify ::deselect-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of ::dwsp/interrupt))
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-local :selected] disj id)
          (update :workspace-local dissoc :last-selected)))))

(defn shift-select-shapes
  ([id]
   (shift-select-shapes id nil))

  ([id objects]
   (ptk/reify ::shift-select-shapes
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of ::dwsp/interrupt))
     ptk/UpdateEvent
     (update [_ state]
       (let [objects (or objects (wsh/lookup-page-objects state))
             append-to-selection (cfh/expand-region-selection objects (into #{} [(get-in state [:workspace-local :last-selected]) id]))
             selection (-> state
                           wsh/lookup-selected
                           (conj id))]
         (-> state
             (assoc-in [:workspace-local :selected]
                       (set/union selection append-to-selection))
             (update :workspace-local assoc :last-selected id)))))))

(defn select-shapes
  [ids]
  (dm/assert!
   "expected valid coll of uuids"
   (and (every? uuid? ids)
        (d/ordered-set? ids)))

  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)
            focus (:workspace-focus-selected state)
            ids (if (d/not-empty? focus)
                  (cpf/filter-not-focus objects focus ids)
                  ids)]
        (assoc-in state [:workspace-local :selected] ids)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)]
        (rx/of
         (dwc/expand-all-parents ids objects)
         ::dwsp/interrupt)))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/WatchEvent
    (watch [_ state _]
      (let [;; Make the select-all aware of the focus mode; in this
            ;; case delimit the objects to the focused shapes if focus
            ;; mode is active
            focus    (:workspace-focus-selected state)
            objects  (-> (wsh/lookup-page-objects state)
                         (cpf/focus-objects focus))

            lookup   (d/getf objects)
            parents  (->> (wsh/lookup-selected state)
                          (into #{} (comp (keep lookup) (map :parent-id))))

            ;; If we have a only unique parent, then use it as main
            ;; anchor for the selection; if not, use the root frame as
            ;; parent
            parent   (if (= 1 (count parents))
                       (-> parents first lookup)
                       (lookup uuid/zero))

            toselect (->> (cfh/get-immediate-children objects (:id parent))
                          (into (d/ordered-set) (comp (remove :hidden) (remove :blocked) (map :id))))]

        (rx/of (select-shapes toselect))))))

(defn deselect-all
  "Clear all possible state of drawing, edition
  or any similar action taken by the user.
  When `check-modal` the method will check if a modal is opened
  and not deselect if it's true"
  ([] (deselect-all false))

  ([check-modal]
   (ptk/reify ::deselect-all
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of ::dwsp/interrupt))
     ptk/UpdateEvent
     (update [_ state]

       ;; Only deselect if there is no modal opened
       (cond-> state
         (or (not check-modal)
             (not (::md/modal state)))
         (update :workspace-local
                 #(-> %
                      (assoc :selected (d/ordered-set))
                      (dissoc :selected-frame))))))))

;; --- Select Shapes (By selrect)

(defn select-shapes-by-current-selrect
  [preserve? ignore-groups?]
  (ptk/reify ::select-shapes-by-current-selrect
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id     (:current-page-id state)
            objects     (wsh/lookup-page-objects state)
            selected    (wsh/lookup-selected state)
            initial-set (if preserve?
                          selected
                          lks/empty-linked-set)
            selrect     (dm/get-in state [:workspace-local :selrect])
            blocked?    (fn [id] (dm/get-in objects [id :blocked] false))]

        (when selrect
          (rx/empty)
          (->> (uw/ask-buffered!
                {:cmd :selection/query
                 :page-id page-id
                 :rect selrect
                 :include-frames? true
                 :ignore-groups? ignore-groups?
                 :full-frame? true
                 :using-selrect? true})
               (rx/map #(cfh/clean-loops objects %))
               (rx/map #(into initial-set (comp
                                           (filter (complement blocked?))
                                           (remove (partial cfh/hidden-parent? objects))) %))
               (rx/map select-shapes)))))))

(defn select-inside-group
  [group-id position]

  (ptk/reify ::select-inside-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            group    (get objects group-id)
            children (map #(get objects %) (:shapes group))

            ;; We need to reverse the children because if two children
            ;; overlap we want to select the one that's over (and it's
            ;; in the later vector position
            selected (->> (reverse children)
                          (d/seek #(gsh/has-point? % position)))]
        (when selected
          (rx/of (select-shape (:id selected))))))))

;; --- Duplicate Shapes
(declare prepare-duplicate-shape-change)
(declare prepare-duplicate-flows)
(declare prepare-duplicate-guides)

(defn prepare-duplicate-changes
  "Prepare objects to duplicate: generate new id, give them unique names,
  move to the desired position, and recalculate parents and frames as needed."
  ([all-objects page ids delta it libraries library-data file-id]
   (let [init-changes
         (-> (pcb/empty-changes it)
             (pcb/with-page page)
             (pcb/with-objects all-objects))]
  (prepare-duplicate-changes all-objects page ids delta it libraries library-data file-id init-changes)))

  ([all-objects page ids delta it libraries library-data file-id init-changes]
   (let [shapes         (map (d/getf all-objects) ids)
         unames         (volatile! (cfh/get-used-names (:objects page)))
         update-unames! (fn [new-name] (vswap! unames conj new-name))
         all-ids        (reduce #(into %1 (cons %2 (cfh/get-children-ids all-objects %2))) (d/ordered-set) ids)
         ids-map        (into {} (map #(vector % (uuid/next))) all-ids)

         changes
         (->> shapes
              (reduce #(prepare-duplicate-shape-change %1
                                                       all-objects
                                                       page
                                                       unames
                                                       update-unames!
                                                       ids-map
                                                       %2
                                                       delta
                                                       libraries
                                                       library-data
                                                       it
                                                       file-id)
                      init-changes))]

     (-> changes
         (prepare-duplicate-flows shapes page ids-map)
         (prepare-duplicate-guides shapes page ids-map delta)))))

(defn- prepare-duplicate-component-change
  [changes objects page component-root parent-id frame-id delta libraries library-data it]
  (let [component-id (:component-id component-root)
        file-id (:component-file component-root)
        main-component    (ctf/get-component libraries file-id component-id)
        moved-component   (gsh/move component-root delta)
        pos               (gpt/point (:x moved-component) (:y moved-component))

        instantiate-component
        #(dwlh/generate-instantiate-component changes
                                              objects
                                              file-id
                                              (:component-id component-root)
                                              pos
                                              page
                                              libraries
                                              (:id component-root)
                                              parent-id
                                              frame-id)

        restore-component
        #(let [restore (dwlh/prepare-restore-component changes library-data (:component-id component-root) it page delta (:id component-root) parent-id)]
           [(:shape restore) (:changes restore)])

        [_shape changes]
        (if (nil? main-component)
          (restore-component)
          (instantiate-component))]
    changes))

;; TODO: move to common.files.shape-helpers
(defn- prepare-duplicate-shape-change
  ([changes objects page unames update-unames! ids-map obj delta libraries library-data it file-id]
   (prepare-duplicate-shape-change changes objects page unames update-unames! ids-map obj delta libraries library-data it file-id (:frame-id obj) (:parent-id obj) false false))

  ([changes objects page unames update-unames! ids-map obj delta libraries library-data it file-id frame-id parent-id duplicating-component? child?]
   (cond
     (nil? obj)
     changes

     (ctf/is-known-component? obj libraries)
     (prepare-duplicate-component-change changes objects page obj parent-id frame-id delta libraries library-data it)

     :else
     (let [frame?      (cfh/frame-shape? obj)
           group?      (cfh/group-shape? obj)
           bool?       (cfh/bool-shape? obj)
           new-id      (ids-map (:id obj))
           parent-id   (or parent-id frame-id)
           parent      (get objects parent-id)
           name        (:name obj)

           is-component-root?     (or (:saved-component-root obj)
                                      ;; Backward compatibility
                                      (:saved-component-root? obj)
                                      (ctk/instance-root? obj))
           duplicating-component? (or duplicating-component? (ctk/instance-head? obj))
           is-component-main?     (ctk/main-instance? obj)
           into-component?        (and duplicating-component?
                                       (ctn/in-any-component? objects parent))

           regenerate-component
           (fn [changes shape]
             (let [components-v2 (dm/get-in library-data [:options :components-v2])
                   [_ changes] (cflh/generate-add-component-changes changes shape objects file-id (:id page) components-v2)]
               changes))

           new-obj
           (-> obj
               (assoc :id new-id
                      :name name
                      :parent-id parent-id
                      :frame-id frame-id)

               (dissoc :shapes
                       :main-instance
                       :use-for-thumbnail)

               (cond-> into-component?
                 (dissoc :component-root))

               (cond-> (and (ctk/instance-head? obj)
                            (not into-component?))
                 (assoc :component-root true))

               (cond-> (or frame? group? bool?)
                 (assoc :shapes []))

               (gsh/move delta)
               (d/update-when :interactions #(ctsi/remap-interactions % ids-map objects))

               (cond-> (ctl/grid-layout? obj)
                 (ctl/remap-grid-cells ids-map)))

           new-obj (cond-> new-obj
                     (not duplicating-component?)
                     (ctk/detach-shape))

           ; We want the first added object to touch it's parent, but not subsequent children
           changes (-> (pcb/add-object changes new-obj {:ignore-touched (and duplicating-component? child?)})
                       (pcb/amend-last-change #(assoc % :old-id (:id obj)))
                       (cond-> (ctl/grid-layout? objects (:parent-id obj))
                         (-> (pcb/update-shapes [(:parent-id obj)] (fn [shape] (-> shape ctl/assign-cells ctl/check-deassigned-cells)))
                             (pcb/reorder-grid-children [(:parent-id obj)]))))

           changes (cond-> changes
                     (and is-component-root? is-component-main?)
                     (regenerate-component new-obj))]

       (reduce (fn [changes child]
                 (prepare-duplicate-shape-change changes
                                                 objects
                                                 page
                                                 unames
                                                 update-unames!
                                                 ids-map
                                                 child
                                                 delta
                                                 libraries
                                                 library-data
                                                 it
                                                 file-id
                                                 (if frame? new-id frame-id)
                                                 new-id
                                                 duplicating-component?
                                                 true))
               changes
               (map (d/getf objects) (:shapes obj)))))))

(defn- prepare-duplicate-flows
  [changes shapes page ids-map]
  (let [flows            (-> page :options :flows)
        unames           (volatile! (into #{} (map :name flows)))
        frames-with-flow (->> shapes
                              (filter #(= (:type %) :frame))
                              (filter #(some? (ctp/get-frame-flow flows (:id %)))))]
    (if-not (empty? frames-with-flow)
      (let [update-flows (fn [flows]
                           (reduce
                             (fn [flows frame]
                               (let [name     (cfh/generate-unique-name @unames "Flow 1")
                                     _        (vswap! unames conj name)
                                     new-flow {:id (uuid/next)
                                               :name name
                                               :starting-frame (get ids-map (:id frame))}]
                                 (ctp/add-flow flows new-flow)))
                             flows
                             frames-with-flow))]
        (pcb/update-page-option changes :flows update-flows))
      changes)))

(defn- prepare-duplicate-guides
  [changes shapes page ids-map delta]
  (let [guides (get-in page [:options :guides])
        frames (->> shapes
                    (filter #(= (:type %) :frame)))
        new-guides (reduce
                    (fn [g frame]
                      (let [new-id     (ids-map (:id frame))
                            new-frame  (-> frame
                                           (gsh/move delta))
                            new-guides (->> guides
                                            (vals)
                                            (filter #(= (:frame-id %) (:id frame)))
                                            (map #(-> %
                                                      (assoc :id (uuid/next))
                                                      (assoc :frame-id new-id)
                                                      (assoc :position (if (= (:axis %) :x)
                                                                         (+ (:position %) (- (:x new-frame) (:x frame)))
                                                                         (+ (:position %) (- (:y new-frame) (:y frame))))))))]
                        (cond-> g
                          (not-empty new-guides)
                          (conj (into {} (map (juxt :id identity) new-guides))))))
                    guides
                    frames)]
    (-> (pcb/with-page changes page)
        (pcb/set-page-option :guides new-guides))))

(defn duplicate-changes-update-indices
  "Updates the changes to correctly set the indexes of the duplicated objects,
  depending on the index of the original object respect their parent."
  [objects ids changes]
  (let [;; index-map is a map that goes from parent-id => vector([id index-in-parent])
        index-map (reduce (fn [index-map id]
                            (let [parent-id    (get-in objects [id :parent-id])
                                  parent-index (cfh/get-position-on-parent objects id)]
                              (update index-map parent-id (fnil conj []) [id parent-index])))
                          {}
                          ids)

        inc-indices
        (fn [[offset result] [id index]]
          [(inc offset) (conj result [id (+ index offset)])])

        fix-indices
        (fn [_ entry]
          (->> entry
               (sort-by second)
               (reduce inc-indices [1 []])
               (second)
               (into {})))

        objects-indices (->> index-map (d/mapm fix-indices) (vals) (reduce merge))]

    (pcb/amend-changes
      changes
      (fn [change]
        (assoc change :index (get objects-indices (:old-id change)))))))

(defn clear-memorize-duplicated
  []
  (ptk/reify ::clear-memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-local :duplicated]))))

(defn memorize-duplicated
  "When duplicate an object, remember the operation during the following seconds.
  If the user moves the duplicated object, and then duplicates it again, check
  the displacement and apply it to the third copy. This is useful for doing
  grids or cascades of cloned objects."
  [id-original id-duplicated]
  (ptk/reify ::memorize-duplicated
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :duplicated] {:id-original id-original
                                                      :id-duplicated id-duplicated}))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper (rx/filter (ptk/type? ::memorize-duplicated) stream)]
        (->> (rx/timer 10000) ;; This time may be adjusted after some user testing.
             (rx/take-until stoper)
             (rx/map clear-memorize-duplicated))))))

(defn calc-duplicate-delta
  [obj state objects]
  (let [{:keys [id-original id-duplicated]}
        (get-in state [:workspace-local :duplicated])
        move? (and (cfh/frame-shape? obj)
                   (not (ctk/instance-head? obj)))]
    (if (or (and (not= id-original (:id obj))
                 (not= id-duplicated (:id obj)))
            ;; As we can remove duplicated elements may be we can still caching a deleted id
            (not (contains? objects id-original))
            (not (contains? objects id-duplicated)))

      ;; The default is leave normal shapes in place, but put
      ;; new frames to the right of the original.
      (if move?
        (gpt/point (+ (:width obj) 50) 0)
        (gpt/point 0 0))

      (let [pt-original   (-> (get objects id-original) :selrect gpt/point)
            pt-duplicated (-> (get objects id-duplicated) :selrect gpt/point)
            pt-obj        (-> obj :selrect gpt/point)
            distance       (gpt/subtract pt-duplicated pt-original)
            new-pos        (gpt/add pt-duplicated distance)]

        (gpt/subtract new-pos pt-obj)))))

(defn duplicate-selected
  ([move-delta?]
   (duplicate-selected move-delta? false))
  ([move-delta? alt-duplication?]
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [it state _]
      (when (or (not move-delta?) (nil? (get-in state [:workspace-local :transform])))
        (let [page     (wsh/lookup-page state)
              objects  (:objects page)
              selected (->> (wsh/lookup-selected state)
                            (map #(get objects %))
                            (remove #(ctk/in-component-copy-not-root? %)) ;; We don't want to change the structure of component copies
                            (map :id)
                            set)]
          (when (seq selected)
            (let [obj             (get objects (first selected))
                  delta           (if move-delta?
                                    (calc-duplicate-delta obj state objects)
                                    (gpt/point 0 0))

                  file-id         (:current-file-id state)
                  libraries       (wsh/get-libraries state)
                  library-data    (wsh/get-file state file-id)

                  changes         (->> (prepare-duplicate-changes objects page selected delta it libraries library-data file-id)
                                       (duplicate-changes-update-indices objects selected))

                  tags            (or (:tags changes) #{})

                  changes         (cond-> changes alt-duplication? (assoc :tags (conj tags :alt-duplication)))

                  id-original     (first selected)

                  new-selected    (->> changes
                                       :redo-changes
                                       (filter #(= (:type %) :add-obj))
                                       (filter #(selected (:old-id %)))
                                       (map #(get-in % [:obj :id]))
                                       (into (d/ordered-set)))

                  id-duplicated   (first new-selected)

                  frames (into #{}
                               (map #(get-in objects [% :frame-id]))
                               selected)
                  undo-id (js/Symbol)]

              ;; Warning: This order is important for the focus mode.
              (rx/of
                (dwu/start-undo-transaction undo-id)
                (dch/commit-changes changes)
                (select-shapes new-selected)
                (ptk/data-event :layout/update frames)
                (memorize-duplicated id-original id-duplicated)
                (dwu/commit-undo-transaction undo-id))))))))))

(defn change-hover-state
  [id value]
  (ptk/reify ::change-hover-state
    ptk/UpdateEvent
    (update [_ state]
      (let [hover-value (if value #{id} #{})]
        (assoc-in state [:workspace-local :hover] hover-value)))))

(defn update-focus-shapes
  [added removed]
  (ptk/reify ::update-focus-shapes
    ptk/UpdateEvent
    (update [_ state]

      (let [objects (wsh/lookup-page-objects state)

            focus (-> (:workspace-focus-selected state)
                      (set/union added)
                      (set/difference removed))
            focus (cfh/clean-loops objects focus)]

        (-> state
            (assoc :workspace-focus-selected focus))))))

(defn toggle-focus-mode
  []
  (ptk/reify ::toggle-focus-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (wsh/lookup-selected state)]
        (cond-> state
          (and (empty? (:workspace-focus-selected state))
               (d/not-empty? selected))
          (assoc :workspace-focus-selected selected)

          (d/not-empty? (:workspace-focus-selected state))
          (dissoc :workspace-focus-selected))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(or (= ::toggle-focus-mode (ptk/type %))
                                    (= :app.main.data.workspace/finalize-page (ptk/type %))) stream)]
        (when (d/not-empty? (:workspace-focus-selected state))
          (rx/merge
           (rx/of dwz/zoom-to-selected-shape
                  (deselect-all))
           (->> (rx/from-atom refs/workspace-page-objects {:emit-current-value? true})
                (rx/take-until stopper)
                (rx/map (comp set keys))
                (rx/buffer 2 1)
                (rx/merge-map
                 (fn [[old-keys new-keys]]
                   (let [removed (set/difference old-keys new-keys)
                         added (set/difference new-keys old-keys)]

                     (if (or (d/not-empty? added) (d/not-empty? removed))
                       (rx/of (update-focus-shapes added removed))
                       (rx/empty))))))))))))
