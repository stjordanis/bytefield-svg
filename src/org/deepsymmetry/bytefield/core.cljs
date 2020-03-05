(ns org.deepsymmetry.bytefield.core
  "Provides a safe yet robust subset of the Clojure programming
  language, tailored to the task of building SVG diagrams in the style
  of the LaTeX `bytefield` package."
  (:require [clojure.string :as str]
            [cljs.pprint :refer [cl-format]]
            [analemma.svg :as svg]
            [analemma.xml :as xml]
            [sci.core :as sci])
  (:require-macros [org.deepsymmetry.bytefield.macros :refer [self-bind-symbols]]))


;; The global symbol table used when evaluating diagram source.

(def ^:dynamic *globals*
  "Holds the globals during the building of a diagram. Dynamically bound
  to a new atom for each thread that calls `generate`, so that can be
  thread-safe. In retrospect, that was over-engineering, but hey... it
  might be used in a web context someday?"
  nil)



;; Implement our domain-specific language for building up attribute
;; maps concisely and by composing named starting maps, as well as
;; constructing text with arbitrarily enclosed (and nested) tspan
;; nodes.

(defn eval-attribute-spec
  "Expands an attribute specification into a single map of attribute
  keywords to values. A map is simply returned unchanged. A keyword is
  looked up in the map definitions (there are some predefined ones,
  and they can be augmented and replaced by calling `defattrs`) and
  the corresponding map is returned. A vector or list is used to merge
  multiple attribute specifications together. Each element is
  evaluated recursively in turn, and the results are merged together
  with later definitions winning if the same keyword appears more than
  once."
  [spec]
  (cond
    (nil? spec)  ; Explicitly represent `nil` as an empty map.
    {}

    (map? spec)  ; A map is already what it needs to be, just return it.
    spec

    (keyword? spec)  ; A keyword gets looked up as a named attribute map.
    (let [defs @('named-attributes @*globals*)]
      (if-let [m (spec defs)]
        m
        (throw (js/Error. (str "Could not resolve attribute spec: " spec)))))

    (sequential? spec)  ; Lists or vectors evaluate and merge elements.
    (reduce (fn [acc v] (merge acc (eval-attribute-spec v)))
            {}
            spec)

    :else (throw (js/Error. (str "Invalid attribute spec: " spec)))))

(declare expand-nested-tspans)

(defn tspan
  "Builds an SVG tspan object from a vector or list that was found in
  the content of a `text` or `tspan` invocation. Any lists or vectors
  found within its own content will be recursively converted into
  tspan objects of their own."
  [attr-spec content]
  (concat [:tspan (eval-attribute-spec attr-spec)] (expand-nested-tspans content)))

(defn expand-nested-tspans
  "Converts any vectors or lists found in the content of a `text` or
  `tspan` invocation into nested `tspan` objects with new attributes
  defined by evaluating their first element as a spec."
  [content]
  (map (fn [element]
         (if (sequential? element)
           (let [[attr-spec & content] element]
             (tspan attr-spec content))
           (str element)))
       content))

(defn text
  "Builds a text object that begins with the specified `label` string.
  Its attributes can be configured through `attr-spec`, which defaults
  to `:plain`, the predefined style for plain text. Any additional
  content will be appended to the initial label, with with special
  support for creating styled children: any lists or vectors found in
  `content` will be expanded into tspan objects (their first element
  will be evaluated as an attribute spec for the tspan itself, and the
  remaining nested content will be expanded the same way as `text`
  content."
  ([label]
   (text label :plain))
  ([label attr-spec & content]
   (concat [:text (eval-attribute-spec attr-spec)] [(str label)] (expand-nested-tspans content))))

;; The diagram-drawing functions we make available for conveniently
;; creating byte field diagrams.

(defn defattrs
  "Registers an attribute map for later use under a keyword."
  [k m]
  (when-not (keyword? k) (throw (js/Error. (str "first argument to defattrs must be a keyword, received: " k))))
  (when-not (map? m) (throw (js/Error. (str "second argument to defattrs must be a map, received: " m))))
  (sci/alter-var-root ('named-attributes @*globals*) assoc k m))

(defn append-svg
  "Adds another svg element to the body being built up."
  [element]
  (sci/alter-var-root ('svg-body @*globals*) concat [element]))


(defn draw-column-headers
  "Generates the header row that identifies each byte/bit box. By
  default uses the lower-case hex digits in increasing order, but you
  can specify your own list of `:labels` as an attribute. Normally
  consumes 14 vertical pixels, but you can specify a different
  `:height`. Defaults to a `:font-size` of 7 and `:font-family` of
  \"Courier New\" but these can be overridden as well. Other SVG text
  attributes can be supplied, and they will be passed along."
  [attr-spec]
  (let [{:keys [labels height font-size font-family]
         :or   {labels      (str/split "0,1,2,3,4,5,6,7,8,9,a,b,c,d,e,f" #",")
                height      14
                font-size   11
                font-family "Courier New, monospace"}
         :as   options} (eval-attribute-spec attr-spec)
        y               (+ @('diagram-y @*globals*) (* 0.5 height))
        body            (for [i (range @('boxes-per-row @*globals*))]
                          (let [x (+ @('left-margin @*globals*) (* (+ i 0.5) @('box-width @*globals*)))]
                            (svg/text (merge (dissoc options :labels :height)
                                             {:x                 x
                                              :y                 y
                                              :font-family       font-family
                                              :font-size         font-size
                                              :dominant-baseline "middle"
                                              :text-anchor       "middle"})
                                      (nth labels i))))]
    (sci/alter-var-root ('diagram-y @*globals*) + height)
    (sci/alter-var-root ('svg-body @*globals*) concat body)))

(defn draw-row-header
  "Generates the label in the left margin which identifies the starting
  byte of a row. Defaults to a `:font-size` of 11 and `:font-family`
  of \"Courier New\" but these can be overridden as well. Other SVG
  text attributes can be supplied via `attr-spec`, and they will be
  passed along.

  In the most common case, `label` is a string and the SVG text object
  is constructed as described above. If you need to draw a more
  complex structure, you can pass in your own SVG text object (with
  potentially nested tspan objects), and it will simply be
  positioned."
  ([label]
   (draw-row-header label nil))
  ([label attr-spec]
   (let [{:keys [font-size font-family dominant-baseline]
          :or   {font-size         11
                 font-family       "Courier New, monospace"
                 dominant-baseline "middle"}
          :as   options} (eval-attribute-spec attr-spec)
         x               (- @('left-margin @*globals*) 5)
         y               (+ @('diagram-y @*globals*) (* 0.5 @('row-height @*globals*)))]
     (if (sequential? label)
       ;; The caller has already generated the SVG for the label, just position it.
       (append-svg (xml/merge-attrs label (select-keys options [:x :y :text-anchor :dominant-baseline])))
       ;; We are both building and positioning the SVG text object.
       (append-svg (svg/text (merge options
                                    {:x                 x
                                     :y                 y
                                     :font-family       font-family
                                     :font-size         font-size
                                     :dominant-baseline dominant-baseline
                                     :text-anchor       "end"})
                             (str label)))))))

(defn draw-line
  "Adds a line to the SVG being built up. `:stroke` defaults to black,
  and `:stroke-width` to 1, but these can be overridden through
  `attr-spec`, and other SVG line attributes can be supplied that way
  as well."
  ([x1 y1 x2 y2]
   (draw-line x1 y1 x2 y2 nil))
  ([x1 y1 x2 y2 attr-spec]
   (let [{:keys [stroke stroke-width]
          :or   {stroke       "#000000"
                 stroke-width 1}
          :as   options} (eval-attribute-spec attr-spec)]
     (append-svg [:line (merge options
                               {:x1           x1
                                :y1           y1
                                :x2           x2
                                :y2           y2
                                :stroke       stroke
                                :stroke-width stroke-width})]))))

(defn next-row
  "Advances drawing to the next row of boxes, reseting the index to 0.
  The height of the row defaults to `row-height` but can be overridden
  by passing a different value."
  ([]
   (next-row @('row-height @*globals*)))
  ([height]
   (sci/alter-var-root ('diagram-y @*globals*) + height)
   (sci/alter-var-root ('box-index @*globals*) (constantly 0))))

(defn hex-text
  "Formats a number as an SVG text object containing a hexadecimal
  string with the specified number of digits (defaults to 2 if no
  length is specified), styled using the `:hex` predefined attributes.
  This styling can be overridden by passing `attr-spec`."
  ([n]
   (hex-text n 2))
  ([n length]
   (hex-text n length :hex))
  ([n length attr-spec]
   (let [fmt (str "~" length ",'0x")]
     (text (cl-format nil fmt n) [:hex attr-spec]))))

(defn format-box-label
  "Builds an appropriate SVG text object to label a box spanning the
  specified number of byte cells. If `label` is a number the content
  will be a hexadecimal string with two digits for each byte cell the
  box spans styled using the `:hex` predefined attributes. If it is a
  list or a vector, it is assumed to represent an already-formatted
  SVG text object, and returned unchanged. If it is a string, it is
  used as the content of a text object that is styled using the
  `:plain` predefined attributes."
  [label span]
  (cond
    (number? label) ; A number, format it as hexadecimal.
    (hex-text label (* span 2))

    (sequential? label) ; A list or vector, assume it is pre-rendered.
    label

    (string? label) ; A string, format it as plain text.
    (text label)

    :else
    (throw (js/Error. (str "Don't know how to format box label: " label)))))

(defn- center-baseline
  "Recursively ensures that the a tag and any content tags it contains
  have their dominant baseline set to center them vertically, so
  complex box labels align properly."
  [tag]
  (let [centered-content (map (fn [element]
                                (if (sequential? element)
                                  (center-baseline element)
                                  element))
                              (xml/get-content tag))
        centered-tag (xml/add-attrs tag :dominant-baseline "middle")]
    (apply xml/set-content (cons centered-tag centered-content))))

(defn draw-box
  "Draws a single byte or bit box in the current row at the current
  index. `label` can either be a number (which will be converted to a
  hex-styled hexadecimal string with two digits for each cell the box
  spans), a pre-constructed SVG text object (which will be rendered
  as-is), or a string (which will be converted to a plain-styled SVG
  text object), or `nil`, to have no label at all.

  The default size box is that of a single byte but this can be
  overridden with the `:span` attribute. Normally draws all borders,
  but you can supply the set you want drawn in `:borders`. The
  background can be filled with a color passed with `:fill`. Box
  height defaults to `row-height`, but that can be changed with
  `:height` (you will need to supply the same height override when
  calling `next-row`)."
  [label attr-spec]
  (let [{:keys [span borders fill height]
         :or   {span    1
                borders #{:left :right :top :bottom}
                height  @('row-height @*globals*)}} (eval-attribute-spec attr-spec)

        left   (+ @('left-margin @*globals*) (* @('box-index @*globals*) @('box-width @*globals*)))
        width  (* span @('box-width @*globals*))
        right  (+ left width)
        top    @('diagram-y @*globals*)
        bottom (+ top height)]
    (when fill (append-svg (svg/rect left top height width :fill fill)))
    (when (borders :top) (draw-line left top right top))
    (when (borders :bottom) (draw-line left bottom right bottom))
    (when (borders :right) (draw-line right top right bottom))
    (when (borders :left) (draw-line left top left bottom))
    (when label
      (let [label (xml/merge-attrs (format-box-label label span)
                                   {:x                 (/ (+ left right) 2.0)
                                    :y                 (+ top 1 (/ height 2.0))
                                    :text-anchor       "middle"})]
        (append-svg (center-baseline label))))
    (sci/alter-var-root ('box-index @*globals*) + span)))

(defn draw-group-label-header
  "Creates a small borderless box used to draw the textual label headers
  used below the byte labels for `remotedb` message diagrams.
  Arguments are the number of colums to span and the text of the
  label."
  [span label]
  (draw-box (text label [:math {:font-size 12}]) {:span span
                                                  :borders      #{}
                                                  :height       14}))

(defn draw-gap
  "Draws an indication of discontinuity. Takes a full row, the default
  height is 50 and the default gap is 10, and the default edge on
  either side of the gap is 5, but all can be overridden with keyword
  arguments."
  [& {:keys [height gap edge]
      :or   {height 70
             gap    10
             edge   15}}]
  (let [y      @('diagram-y @*globals*)
        top    (+ y edge)
        left   @('left-margin @*globals*)
        right  (+ left (* @('box-width @*globals*) @('boxes-per-row @*globals*)))
        bottom (+ y (- height edge))]
    (draw-line left y left top)
    (draw-line right y right top)
    (draw-line left top right (- bottom gap) :dotted)
    (draw-line right y right (- bottom gap))
    (draw-line left (+ top gap) right bottom :dotted)
    (draw-line left (+ top gap) left bottom)
    (draw-line left bottom left (+ y height))
    (draw-line right bottom right (+ y height)))
  (sci/alter-var-root ('diagram-y @*globals*) + height))

(defn draw-bottom
  "Ends the diagram by drawing a line across the box area. Needed if the
  preceding action was drawing a gap, to avoid having to draw an empty
  row of boxes, which would extend the height of the diagram without
  adding useful information."
  []
  (let [y    @('diagram-y @*globals*)
        left @('left-margin @*globals*)]
    (draw-line left y (+ left (* @('box-width @*globals*) @('boxes-per-row @*globals*))) y)))



;; Set up the context for the parser/evaluator for our domain-specific
;; language, a subset of Clojure which requires no compilation at
;; runtime, and which cannot do dangerous things like Java interop,
;; arbitrary I/O, or infinite iteration.

(def xml-bindings
  "The Analemma XML-manipulation functions we make available for
  building diagrams."
  (self-bind-symbols [xml/add-attrs
                      xml/add-content
                      xml/emit
                      xml/emit-attrs
                      xml/emit-tag
                      xml/get-attrs
                      xml/get-content
                      xml/get-name
                      xml/has-attrs?
                      xml/has-content?
                      xml/merge-attrs
                      xml/set-attrs
                      xml/set-content
                      xml/update-attrs]))

(def svg-bindings
  "The Analemma SVG-creation functions we make available for building
  diagrams."
  (self-bind-symbols [svg/add-style
                      svg/animate
                      svg/animate-color
                      svg/animate-motion
                      svg/animate-transform
                      svg/circle
                      svg/defs
                      svg/draw
                      svg/ellipse
                      svg/group
                      svg/image
                      svg/line
                      svg/parse-inline-css
                      svg/path
                      svg/polygon
                      svg/rect
                      svg/rgb
                      svg/rotate
                      svg/style
                      svg/style-map
                      svg/svg
                      svg/text
                      svg/text-path
                      svg/transform
                      svg/translate
                      svg/translate-value
                      svg/tref
                      svg/tspan]))

(def diagram-bindings
  "Our own functions which we want to make available for building
  diagrams."
  (self-bind-symbols [append-svg
                      defattrs
                      draw-bottom
                      draw-box
                      draw-column-headers
                      draw-gap
                      draw-group-label-header
                      draw-line
                      draw-row-header
                      hex-text
                      next-row
                      text
                      tspan]))

(def initial-named-attributes
  "The initial contents of the lookup table for shorthand
  attribute map specifications."
  {:hex   {:font-size   18 ; Default style in which hex values are drawn.
           :font-family "Courier New, monospace"}
   :plain {:font-size   18 ; Default style in which ordinary text labels are drawn.
           :font-family "Palatino, Georgia, Times New Roman, serif"}
   :math  {:font-size   18 ; Default style in which variables and equations are drawn.
           :font-family "Palatino, Georgia, Times New Roman, serif"
           :font-style  "italic"}
   :sub   {:font-size      "70%" ; Style for subscripted nested tspan objects
           :baseline-shift "sub"}
   :bold  {:font-weight "bold"} ; Adds bolding to the font style.

   :dotted {:stroke-dasharray "1,1"} ; Style for dotted lines.

   :box-first   {:borders #{:left :top :bottom}}   ; Style for first of a group of related boxes.
   :box-related {:borders #{:top :bottom}}         ; Style for internal box in a related group.
   :box-last    {:borders #{:right :top :bottom}}  ; Style for last of a group of related boxes.
   :box-above   {:borders #{:left :right :top}}    ; Style for open box at end of line before a gap.
   :box-below   {:borders #{:left :right :bottom}} ; Style for open box at start of line after a gap.
   })

(def initial-globals
  "The contents of the global symbol table that will be established at
  the start of reading a diagram definition."
  (merge
   {'left-margin   40 ; Space for row offsets and other leading marginalia.
    'right-margin  1  ; Space at the right, currently just enough to avoid clipping the rightmost box edges.
    'bottom-margin 1  ; Space at bottom, currently just enough to avoid clipping bottom box edges.
    'box-width     40 ; How much room each byte (or bit) box takes up.

    'boxes-per-row 16 ; How many individual byte/bit boxes fit on each row.
    'row-height    30 ; The height of a standard row of boxes.

    'named-attributes initial-named-attributes ; The lookup table for shorthand attribute map specifications.

    ;; Values used to track the current state of the diagram being created:
    'box-index 0 ; Row offset of the next box to be drawn.
    'diagram-y 5 ; The y coordinate of the top of the next row to be drawn.
    'svg-body  '()}))

(defn- build-vars
  "Creates the sci vars to populate the symbol table for the
  interpreter."
  []
  (reduce  (fn [acc [k v]]
             (assoc acc k (sci/new-var k v)))
          {}
          initial-globals))

(defn- emit-svg
  "Outputs the finished SVG."
  []
  (let [result @*globals*]
    (xml/emit (apply svg/svg {:width (+ @('left-margin result) @('right-margin result)
                                         (* @('box-width result) @('boxes-per-row result)))
                              :height (+ @('diagram-y result) @('bottom-margin result))}
                     @('svg-body result)))))

(defn generate
  "Accepts Clojure-based diagram specification string and returns the
  corresponding SVG string."
  [source]
  (binding [*globals* (atom (build-vars))]
    (let [env  (atom {})
          opts {:preset     :termination-safe
                :env        env
                :namespaces {'user         (merge diagram-bindings @*globals*)
                             'analemma.svg svg-bindings
                             'analemma.xml xml-bindings}}]
      (sci/eval-string "(require '[analemma.xml :as xml])" opts)
      (sci/eval-string "(require '[analemma.svg :as svg])" opts)
      (sci/eval-string source opts)
      (emit-svg))))
