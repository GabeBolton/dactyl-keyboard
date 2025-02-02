;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Dactyl-ManuForm Keyboard — Opposable Thumb Edition              ;;
;; Auxiliary Features                                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns dactyl-keyboard.cad.auxf
  (:require [scad-clj.model :as model]
            [scad-tarmi.core :refer [π]]
            [scad-tarmi.maybe :as maybe]
            [scad-tarmi.threaded :as threaded]
            [scad-tarmi.util :refer [loft]]
            [dactyl-keyboard.generics :as generics]
            [dactyl-keyboard.cad.misc :as misc]
            [dactyl-keyboard.cad.matrix :as matrix]
            [dactyl-keyboard.cad.key :as key]
            [dactyl-keyboard.cad.place :as place]
            [dactyl-keyboard.param.access :refer [get-key-alias most-specific]]))


;;;;;;;;;;;;;;;;;;;
;; Multiple Uses ;;
;;;;;;;;;;;;;;;;;;;


(def usb-a-female-dimensions
  "This assumes the flat orientation common in laptops.
  In a DMOTE, USB connector width would typically go on the z axis, etc."
  {:full {:width 10.0 :length 13.6 :height 6.5}
   :micro {:width 7.5 :length 5.9 :height 2.55}})


;;;;;;;;;;;;;;;;;;;;;
;; Microcontroller ;;
;;;;;;;;;;;;;;;;;;;;;


(defn derive-mcu-properties [getopt]
  (let [mcu-type (getopt :mcu :type)
        pcb-base {:thickness 1.57 :connector-overshoot 1.9}
        pcb (case mcu-type
              :promicro (merge pcb-base {:width 18 :length 33})
              :teensy (merge pcb-base {:width 17.78 :length 35.56})
              :teensy++ (merge pcb-base {:width 17.78 :length 53}))]
   {:pcb pcb
    :connector (:micro usb-a-female-dimensions)
    :support-height (* (getopt :mcu :support :height-factor) (:width pcb))}))

(defn mcu-position
  "Transform passed shape into the reference frame for an MCU holder."
  [getopt shape]
  (let [use-housing (and (getopt :case :rear-housing :include)
                         (getopt :mcu :position :prefer-rear-housing))
        corner (getopt :mcu :position :corner)
        z (getopt :mcu :derived :pcb :width)
        lateral-shim
          (place/lateral-offset getopt (second corner)
            (apply +
              (remove nil?
                [(when use-housing
                   1)  ; Compensate for housing wall segment 1 displacement.
                 (when use-housing
                   (/ (getopt :case :rear-housing :wall-thickness) -2))
                 (when use-housing
                   (/ (getopt :mcu :derived :pcb :thickness) -2))
                 (- (getopt :mcu :support :lateral-spacing))])))]
   (->>
     shape
     (model/translate
       (place/lateral-offset getopt (second corner)
         (- (getopt :mcu :derived :pcb :connector-overshoot))))
     ;; Face the corner’s main direction, plus arbitrary rotation.
     (maybe/rotate
       (mapv +
         (getopt :mcu :position :rotation)
         [0 0 (- (matrix/compass-radians (first corner)))]))
     (model/translate
       (mapv +
         ;; Move into the requested corner.
         (place/into-nook getopt :mcu)
         ;; Move away from a supporting wall, if any.
         lateral-shim
         ;; Raise above the floor.
         [0 0 (/ z 2)])))))

(defn mcu-model
  "A model of an MCU: PCB and integrated USB connector (if any).
  The orientation of the model is standing on the long edge, with the connector
  side of the PCB facing “north” and the middle of the short edge of the PCB
  centering at the origin of the local cordinate system. The connector itself
  is placed on the “east” (positive x) side."
  [getopt include-margin connector-elongation]
  (let [data (partial getopt :mcu :derived)
        {pcb-x :thickness pcb-y :length pcb-z :width overshoot :connector-overshoot} (data :pcb)
        {usb-x :height usb-y-base :length usb-z :width} (data :connector)
        usb-y (+ usb-y-base connector-elongation)
        margin (if include-margin (getopt :mcu :margin) 0)
        mcube (fn [& dimensions] (apply model/cube (map #(+ % margin) dimensions)))]
   (model/union
     (model/translate [0 (/ pcb-y -2) 0]
       (model/color (:pcb generics/colours)
         (mcube pcb-x pcb-y pcb-z)))
     (model/translate [(/ (+ pcb-x usb-x) 2)
                       (+ (/ usb-y -2) (/ connector-elongation 2) overshoot)
                       0]
       (model/color (:metal generics/colours)
         (mcube usb-x usb-y usb-z))))))

(defn mcu-visualization [getopt]
  (mcu-position getopt (mcu-model getopt false 0)))

(defn mcu-negative [getopt]
  (mcu-position getopt (mcu-model getopt true 10)))

(defn mcu-alcove
  "A block shape at the connector end of the MCU.
  For use as a complement to mcu-negative, primarily with mcu-stop.
  This is provided because a negative of the MCU model itself digging into the
  inside of a wall would create only a narrow notch, which would require
  high printing accuracy or difficult cleanup."
  [getopt]
  (let [prop (getopt :mcu :derived)
        {pcb-x :thickness pcb-z :width} (prop :pcb)
        {usb-x :height} (prop :connector)
        margin (getopt :mcu :margin)
        x (+ pcb-x usb-x margin)]
   (mcu-position getopt
     (model/translate [(/ x 2) (/ x -2) 0]
       (model/cube x x (+ pcb-z margin))))))

(defn- mcu-gripper
  "The shape of the gripper in a stop-style MCU holder.
  The notch in it will be created by its difference with the MCU model.
  Positioned in the same local coordinate system as the MCU model."
  [getopt depth-factor]
  (let [pcb (getopt :mcu :derived :pcb)
        {pcb-x :thickness pcb-y :length} pcb
        margin (getopt :mcu :margin)
        prop (partial getopt :mcu :support :stop :gripper)
        notch-base (prop :notch-depth)
        notch-y (- notch-base margin)
        grip-x (prop :grip-width)
        x (+ (* 2 grip-x) pcb-x margin)
        y-base (prop :total-depth)
        y (* depth-factor y-base)
        gripper-y-center (/ (- (* 2 y-base) y) 2)
        z (getopt :mcu :derived :support-height)]
   (model/translate [0 (- notch-y pcb-y gripper-y-center) 0]
     (model/cube x y z))))

(defn mcu-stop
  "The stop style of MCU support, in place."
  [getopt]
  (let [alias (getopt :mcu :support :stop :anchor)
        keyinfo (get-key-alias getopt alias)
        {cluster :cluster coordinates0 :coordinates} keyinfo
        direction (getopt :mcu :support :stop :direction)
        opposite (matrix/left (matrix/left direction))
        coordinates1 (matrix/walk coordinates0 direction)
        post (fn [coord corner]
               (let [key-style (most-specific getopt [:key-style] cluster coord)]
                 (place/cluster-place getopt cluster coord
                   (key/mount-corner-post getopt key-style corner))))]
    (model/union
      (mcu-position getopt (mcu-gripper getopt 1))
      (model/hull
        ;; Connect the back half of the gripper to two key mounts.
        (mcu-position getopt (mcu-gripper getopt 0.5))
        (post coordinates0 [direction (matrix/left direction)])
        (post coordinates0 [direction (matrix/right direction)])
        (post coordinates1 [opposite (matrix/left direction)])
        (post coordinates1 [opposite (matrix/right direction)])))))

(defn mcu-lock-fixture-positive
  "Parts of the lock-style MCU support that integrate with the case.
  These comprise a bed for the bare side of the PCB to lay against and a socket
  that encloses the USB connector on the MCU to stabilize it, since integrated
  USB connectors are usually surface-mounted and therefore fragile."
  [getopt]
  (let [prop (partial getopt :mcu :derived)
        {pcb-x :thickness pcb-y :length} (prop :pcb)
        {usb-x :height usb-y :length usb-z :width} (prop :connector)
        bed-x (getopt :mcu :support :lateral-spacing)
        bed-y (+ pcb-y (getopt :mcu :support :lock :bolt :mount-length))
        thickness (getopt :mcu :support :lock :socket :thickness)
        socket-x-thickness (+ (/ usb-x 2) thickness)
        socket-x-offset (+ (/ pcb-x 2) (* 3/4 usb-x) (/ thickness 2))
        socket-z (+ usb-z (* 2 thickness))]
   (mcu-position getopt
     (model/union
       (model/translate [(+ (/ bed-x -2) (/ pcb-x -2)) (/ bed-y -2) 0]
         (model/cube bed-x bed-y (getopt :mcu :derived :support-height)))
       (model/hull
         ;; Purposely ignore connector overshoot in placing the socket.
         ;; This has the advantages that the lock itself can also be stabilized
         ;; by the socket, while the socket does not protrude outside the case.
         (model/translate [socket-x-offset (/ usb-y -2) 0]
           (model/cube socket-x-thickness usb-y socket-z))
         ;; Stabilizers for the socket:
         (model/translate [10 0 0]
           (model/cube 1 1 socket-z))
         (model/translate [socket-x-offset 0 0]
           (model/cube 1 1 (+ socket-z 6))))))))

(defn mcu-lock-fasteners-model
  "Negative space for a bolt threading into an MCU lock."
  [getopt]
  (let [use-housing (and (getopt :case :rear-housing :include)
                         (getopt :mcu :position :prefer-rear-housing))
        head-type (getopt :mcu :support :lock :fastener :style)
        d (getopt :mcu :support :lock :fastener :diameter)
        l0 (threaded/head-height d head-type)
        l1 (if use-housing
             (getopt :case :rear-housing :wall-thickness)
             (getopt :case :web-thickness))
        {pcb-x :thickness pcb-y :length} (getopt :mcu :derived :pcb)
        l2 (getopt :mcu :support :lateral-spacing)
        y1 (getopt :mcu :support :lock :bolt :mount-length)]
    (->>
      (threaded/bolt
        :iso-size d
        :head-type head-type
        :unthreaded-length (max 0 (- (+ l1 l2) l0))
        :threaded-length (getopt :mcu :support :lock :bolt :mount-thickness)
        :negative true)
      (model/rotate [0 (/ π -2) 0])
      (model/translate [(- (+ (/ pcb-x 2) l1 l2))
                        (- (+ pcb-y (/ y1 2)))
                        0]))))

(defn mcu-lock-sink [getopt]
  (mcu-position getopt
    (mcu-lock-fasteners-model getopt)))

(defn mcu-lock-bolt
  "Parts of the lock-style MCU support that don’t integrate with the case.
  The bolt as such is supposed to clear PCB components and enter the socket to
  butt up against the USB connector. There are some margins here, intended for
  the user to file down the tip and finalize the fit."
  [getopt]
  (let [prop (partial getopt :mcu :derived)
        {pcb-x :thickness pcb-y :length usb-overshoot :connector-overshoot} (prop :pcb)
        {usb-x :height usb-y :length usb-z :width} (prop :connector)
        mount-x (getopt :mcu :support :lock :bolt :mount-thickness)
        mount-overshoot (getopt :mcu :support :lock :bolt :overshoot)
        mount-base (getopt :mcu :support :lock :bolt :mount-length)
        clearance (getopt :mcu :support :lock :bolt :clearance)
        shave (/ clearance 2)
        contact-x (- usb-x shave)
        bolt-x-mount (- mount-x clearance pcb-x)
        mount-z (getopt :mcu :derived :support-height)
        bolt-x0 (+ (/ pcb-x 2) clearance (/ bolt-x-mount 2))
        bolt-x1 (+ (/ pcb-x 2) shave (/ contact-x 2))]
   (mcu-position getopt
     (model/difference
       (model/union
         (model/translate [(+ (/ pcb-x -2) (/ mount-x 2))
                           (- (/ mount-overshoot 2) pcb-y (/ mount-base 2))
                           0]
           (model/cube mount-x (+ mount-overshoot mount-base) mount-z))
         (loft
           [(model/translate [bolt-x0 (- pcb-y) 0]
              (model/cube bolt-x-mount 10 mount-z))
            (model/translate [bolt-x0 (/ pcb-y -4) 0]
              (model/cube bolt-x-mount 1 usb-z))
            (model/translate [bolt-x1 (- usb-overshoot usb-y) 0]
              (model/cube contact-x 0.01 usb-z))]))
       (mcu-model getopt true 0)  ; Notch the mount.
       (mcu-lock-fasteners-model getopt)))))

(defn mcu-lock-fixture-composite [getopt]
  (model/difference
    (mcu-lock-fixture-positive getopt)
    (mcu-lock-bolt getopt)
    (mcu-negative getopt)
    (mcu-lock-sink getopt)))


;;;;;;;;;;;;;;;;
;; Back Plate ;;
;;;;;;;;;;;;;;;;

;; Plate for a connecting beam, rod etc.

(defn backplate-place
  [getopt shape]
  (->>
    shape
    (model/translate
      (place/offset-from-anchor getopt (getopt :case :back-plate :position) 3))
    (model/translate [0 0 (/ (getopt :case :back-plate :beam-height) -2)])))

(defn backplate-shape
  "A mounting plate for a connecting beam."
  [getopt]
  (let [height (getopt :case :back-plate :beam-height)
        width (+ (getopt :case :back-plate :fasteners :distance) height)
        depth 3
        interior-protrusion 8
        exterior-bevel 1
        interior-bevel 7]
   (model/hull
     (model/translate [0 (- interior-protrusion) 0]
       (model/cube (- width interior-bevel) depth (- height interior-bevel)))
     (model/cube width depth height)
     (model/translate [0 exterior-bevel 0]
       (model/cube (dec width) depth (dec height))))))

(defn backplate-fastener-holes
  "Two holes for screws through the back plate."
  [getopt]
  (let [d (getopt :case :back-plate :fasteners :diameter)
        D (getopt :case :back-plate :fasteners :distance)
        hole (fn [x-offset]
               (->>
                 (model/union
                   (model/cylinder (/ d 2) 25)
                   (if (getopt :case :back-plate :fasteners :bosses)
                     (model/translate [0 0 10]
                       (threaded/nut :iso-size d :height 10 :negative true))))
                 (model/rotate [(/ π 2) 0 0])
                 (model/translate [x-offset 0 0])
                 (backplate-place getopt)))]
   (model/union
     (hole (/ D 2))
     (hole (/ D -2)))))

(defn backplate-block [getopt]
  (misc/bottom-hull (backplate-place getopt (backplate-shape getopt))))


;;;;;;;;;;;;;;;
;; LED Strip ;;
;;;;;;;;;;;;;;;

(defn- west-wall-west-points [getopt]
  (let [cluster (getopt :case :leds :position :cluster)
        column 0
        rows (getopt :key-clusters :derived :by-cluster cluster
               :row-indices-by-column column)]
    (for [row rows, corner [generics/WSW generics/WNW]]
     (let [[x y _] (place/wall-corner-place
                     getopt cluster [column row] {:directions corner})]
      [(+ x (getopt :by-key :parameters :wall :thickness)) y]))))

(defn- west-wall-east-points [getopt]
  (map (fn [[x y]] [(+ x 10) y]) (west-wall-west-points getopt)))

(defn west-wall-led-channel [getopt]
  (let [west-points (west-wall-west-points getopt)
        east-points (west-wall-east-points getopt)]
    (model/extrude-linear {:height 50}
      (model/polygon (concat west-points (reverse east-points))))))

(defn led-hole-position [getopt ordinal]
  (let [cluster (getopt :case :leds :position :cluster)
        column 0
        rows (getopt :key-clusters :derived :by-cluster cluster
                 :row-indices-by-column column)
        row (first rows)
        [x0 y0 _] (place/wall-corner-place
                    getopt cluster [column row] {:directions generics/WNW})
        h (+ 5 (/ (getopt :case :leds :housing-size) 2))]
   [x0 (+ y0 (* (getopt :case :leds :interval) ordinal)) h]))

(defn led-emitter-channel [getopt ordinal]
  (->> (model/cylinder (/ (getopt :case :leds :emitter-diameter) 2) 20)
       (model/rotate [0 (/ π 2) 0])
       (model/translate (led-hole-position getopt ordinal))))

(defn led-housing-channel [getopt ordinal]
  (let [h (getopt :case :leds :housing-size)]
   (->> (model/cube 50 h h)
        (model/translate (led-hole-position getopt ordinal)))))

(defn led-holes [getopt]
  (let [holes (range (getopt :case :leds :amount))
        group (fn [function]
                (apply model/union (map (partial function getopt) holes)))]
   (model/union
     (model/intersection
       (west-wall-led-channel getopt)
       (group led-housing-channel))
     (group led-emitter-channel))))


;;;;;;;;;;;;;;;;
;; Signalling ;;
;;;;;;;;;;;;;;;;

(defn connection-position
  "Move the negative or positive of the connection metasocket into place."
  [getopt shape]
  (let [corner (getopt :connection :position :corner)
        use-housing (and (getopt :case :rear-housing :include)
                         (getopt :connection :position :prefer-rear-housing))
        socket-size (getopt :connection :socket-size)
        socket-depth-offset (/ (second socket-size) -2)
        socket-height-offset (/ (nth socket-size 2) 2)
        socket-thickness (getopt :connection :socket-thickness)
        vertical-alignment
          ;; Line up with a wall and a metasocket base plate.
          (if (and use-housing (getopt :connection :position :raise))
            ;; Raise socket to just below roof.
            (- (getopt :case :rear-housing :height)
               (max socket-thickness
                    (getopt :case :rear-housing :roof-thickness))
               socket-height-offset)
            ;; Raise socket to just above floor.
            (+ socket-thickness socket-height-offset))
        shim
          (if use-housing
            (place/lateral-offset getopt (second corner)
              (/ (first socket-size) -2))
            [0 0 0])]
   (->> shape
        ;; Bring the face of the socket to the origin, at the right height.
        (model/translate [0 socket-depth-offset vertical-alignment])
        ;; Rotate as specified and to face out from the anchor.
        (maybe/rotate
          (mapv +
            (getopt :connection :position :rotation)
            [0 0 (- (matrix/compass-radians (first corner)))]))
        ;; Bring snugly to the requested corner.
        (model/translate (mapv + shim (place/into-nook getopt :connection))))))

(defn connection-metasocket
  "The shape of a holder in the case to receive a signalling socket component.
  Here, the shape nominally faces north."
  [getopt]
  ;; TODO: Generalize this a bit to also provide a full-size USB socket
  ;; as a less fragile alternative or complement to a USB connector built into
  ;; the MCU.
  (let [socket-size (getopt :connection :socket-size)
        thickness (getopt :connection :socket-thickness)
        double (* thickness 2)]
   (model/translate [0 (/ thickness -2) 0]
     (apply model/cube (mapv + socket-size [double thickness double])))))

(defn connection-socket
  "Negative space for a port, with a hole for wires leading out of the port and
  into the interior of the keyboard. The hole is in the negative-y-side wall,
  based on the assumption that the socket is pointing “north” and the wires
  come out to the “south”. The hole is slightly thicker than the wall for
  cleaner rendering."
  [getopt]
  (let [socket-size (getopt :connection :socket-size)
        thickness (getopt :case :web-thickness)]
   (model/union
     (apply model/cube socket-size)
     (model/translate [0 (/ (+ (second socket-size) thickness) -2) 0]
       (model/cube (dec (first socket-size))
                   (inc thickness)
                   (dec (last socket-size)))))))

(defn connection-positive [getopt]
  (connection-position getopt (connection-metasocket getopt)))

(defn connection-negative [getopt]
  (connection-position getopt (connection-socket getopt)))


;;;;;;;;;;;;;;;;;;;;
;; Minor Features ;;
;;;;;;;;;;;;;;;;;;;;

(defn- foot-point
  [getopt point-spec]
  (place/offset-from-anchor getopt point-spec 2))

(defn- foot-plate
  [getopt polygon-spec]
  (model/extrude-linear
    {:height (getopt :case :foot-plates :height), :center false}
    (model/polygon (map (partial foot-point getopt) (:points polygon-spec)))))

(defn foot-plates
  "Model plates from polygons.
  Each vector specifying a point in a polygon must have an anchor (usually a
  key alias) and a corner thereof identified by a direction tuple. These can
  be followed by a two-dimensional offset for tweaking."
  [getopt]
  (apply maybe/union
    (map (partial foot-plate getopt) (getopt :case :foot-plates :polygons))))
