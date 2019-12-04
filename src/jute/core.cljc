(ns jute.core
  (:refer-clojure :exclude [compile])
  (:require [clojure.set :as cset]
            [instaparse.core :as insta]
            [clojure.string :as str])
  #?(:cljs (:require-macros [jute.core :refer [defn-compile eval-fn if-cljs]]))
  #?(:clj (:gen-class))
  #?(:clj (:import java.time.YearMonth)))

(defn- to-string [v]
  (if (keyword? v) (name v) (str v)))

(defn drop-blanks [n]
  (let [res (cond
              (and (string? n) (str/blank? n)) nil
              (map? n)
              (let [nn (reduce (fn [acc [k v]]
                                 (let [nv (drop-blanks v)]
                                   (if (nil? nv)
                                     acc
                                     (assoc acc k nv))))
                               {} n)]
                (if (empty? nn) nil nn))

              (sequential? n)
              (let [nn (remove nil? (map drop-blanks n))]
                (if (empty? nn) nil nn))

              :else n)]

    res))

(defn- to-decimal [v]
  (if (string? v)
    (try
      #?(:clj (Float/parseFloat v)
         :cljs (js/parseFloat v))
      (catch #?(:clj Exception :cljs js/Object) e nil))
    v))

(defn raise [msg]
  (throw #?(:clj (RuntimeException. msg)
            :cljs (js/Error. msg))))

(defn- path-to-string [path]
  (str/join "." (map #(if (keyword? %) (name %) (str %)) path)))

(defn wrap-ex-with-path-and-rethrow [ex path msg-str]
  (if #?(:clj (and (instance? clojure.lang.ExceptionInfo ex)
                   (get (.getData ex) :path))
         :cljs (and (aget ex "path") (aget ex "pathStr")))
    (throw ex)

    (let [path-str (path-to-string path)
          new-msg (str #?(:clj (.getMessage ex) :cljs (aget ex "message")) msg-str path-str)
          new-ex #?(:clj (clojure.lang.ExceptionInfo. new-msg {:path path :path-str path-str} ex)
                    :cljs (js/Error. new-msg))]

      #?(:cljs (do
                 (aset new-ex "path" (clj->js path))
                 (aset new-ex "pathStr" path-str)))

      (throw new-ex))))

(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))

(defmacro if-cljs
  "Return then if we are generating cljs code and else for Clojure code.

  https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
  [then else]
  (if (cljs-env? &env) then else))

(defmacro defn-compile [fn-name [node options path :as args] & body]
  `(if-cljs
       (defn- ~fn-name ~args
         (try
           ~@body
           (catch js/Error e#
             (wrap-ex-with-path-and-rethrow e# ~path "\nWhile compiling JUTE template at "))))

     (defn- ~fn-name ~args
       (try
         ~@body
         (catch Exception e#
           (wrap-ex-with-path-and-rethrow e# ~path "\nWhile compiling JUTE template at "))))))

(defmacro eval-fn [path args & body]
  `(if-cljs
       (fn ~args
         (try
           ~@body
           (catch js/Error e#
             (wrap-ex-with-path-and-rethrow e# ~path "\nWhile evaluating JUTE template at "))))

     (fn ~args
       (try
         ~@body
         (catch Exception e#
           (wrap-ex-with-path-and-rethrow e# ~path "\nWhile evaluating JUTE template at "))))))

(defn get-days-in-month [year month]
  #?(:cljs (.getDate (js/Date. year month 0))
     :clj (.lengthOfMonth (java.time.YearMonth/of year month))))

(def standard-fns
  {:join str/join       ;; deprecated
   :joinStr str/join
   :splitStr (fn [s re & [limit]] (str/split s (re-pattern re) (or limit 0)))
   :substring subs      ;; deprecated
   :substr    subs
   :concat concat
   :merge merge
   :flatten flatten
   :assoc assoc
   :abs (fn [i] #?(:clj (java.lang.Math/abs i)
                   :cljs (Math/abs i)))
   :dropBlanks drop-blanks 
   :str to-string
   :range range
   :toString to-string
   :toKeyword keyword
   :hash hash
   :toInt (fn [v] (if (string? v)
                    (try
                      #?(:clj (java.lang.Long/parseLong v)
                         :cljs (js/parseInt v))
                      (catch #?(:clj Exception :cljs js/Error) e nil))
                    v))

   :toDecimal to-decimal           ;; deprecated
   :toDec to-decimal

   :now (fn [] #?(:clj (new java.util.Date)
                  :cljs (new js/Date)))
   :groupBy group-by
   :len count

   :daysInMonth get-days-in-month

   :println println})

;; operator precedence:
;; unary ops & not op
;; mult, div and reminder
;; plus / minus
;; comparison (le, ge, etc)
;; equal, not equal
;; logical and
;; logical or

(def expression-parser
  (insta/parser
   "
<root> = <('$' #'\\s+')?> expr

<expr>
  = or-expr
  | and-expr
  | equality-expr
  | comparison-expr
  | additive-expr
  | multiplicative-expr
  | unary-expr
  | terminal-expr

<parens-expr>
  = <'('> expr <')'>

comparison-expr
  = comparison-expr ('>' | '<' | '>=' | '<=') additive-expr | additive-expr

additive-expr
  = additive-expr  ('+' | '-') multiplicative-expr | multiplicative-expr

multiplicative-expr
  = multiplicative-expr ('*' | '/' | '%') unary-expr | unary-expr

and-expr
  = and-expr <'&&'> equality-expr | equality-expr

or-expr
  = or-expr <'||'> and-expr | and-expr

equality-expr
  = equality-expr ('=' | '!=') comparison-expr | comparison-expr

unary-expr
  = ('+' | '-' | '!') expr | terminal-expr

<terminal-expr>
  = bool-literal / num-literal / string-literal / null-literal / path / fn-call / parens-expr

fn-call
  = path <'('> fn-call-args <')'>

<fn-call-args>
  = (expr (<','> expr) *)?

(* PATHS *)

path
  = path-head (<'.'> path-component)*

<path-head>
  = #'[a-zA-Z_][a-zA-Z_0-9]*'
  | '@'
  | fn-call
  | parens-expr

<path-component>
  = #'[a-zA-Z_0-9]+'
  | path-predicate
  | path-deep-wildcard
  | path-wildcard
  | parens-expr

path-predicate
  = <'*'> parens-expr

path-deep-wildcard
  = <'**'>

path-wildcard
  = <'*'>

(* LITERALS *)

num-literal
  = #'[0-9]+' ('.' #'[0-9]'*)?

bool-literal
  = 'true' !path-head | 'false' !path-head

null-literal
  = 'null' !path-head

string-literal
  = <'\"'> #'[^\"]*'  <'\"'>
"
   :auto-whitespace :standard))

(declare compile*)

(defn- eval-node [n scope]
  (if (fn? n) (n scope) n))

(defn-compile compile-map-directive [node options path]
  (let [compiled-map (compile* (:$map node) options (conj path :$map))
        as (get node :$as "this")
        var-name (keyword (if (string? as) as (first as)))
        idx-name (if (sequential? as) (keyword (second as)) nil)
        compiled-body (compile* (:$body node) options (conj path :$body))
        update-scope (fn [scope v idx]
                       (let [scope (assoc scope var-name v)]
                         (if idx-name
                           (assoc scope idx-name idx)
                           scope)))]

    (eval-fn path [scope]
             (let [coll (eval-node compiled-map scope)]
               (vec (map-indexed (if (map? coll)
                                   (fn [idx [k v]]
                                     (eval-node compiled-body
                                                (update-scope scope {:key k :value v} idx)))

                                   (fn [idx item]
                                     (eval-node compiled-body
                                                (update-scope scope item idx))))
                                 coll))))))

(defn-compile compile-reduce-directive [node options path]
  (let [compiled-reduce (compile* (:$reduce node) options (conj path :$reduce))
        [acc-name alias] (:$as node)
        compiled-start (compile* (:$start node) options (conj path :$start))
        compiled-body (compile* (:$body node) options (conj path :$body))]

    (when (or (nil? acc-name) (nil? alias))
      (raise "Please provide $as attribute (array) for an $reduce directive"))

    (let [acc-name (keyword acc-name)
          alias (keyword alias)]
      (eval-fn path [scope]
               (let [coll (eval-node compiled-reduce scope)
                     start (eval-node compiled-start scope)]
                 (reduce (if (map? coll)
                           (fn [acc [k v]]
                             (eval-node compiled-body
                                        (assoc scope alias {:key k :value v}
                                               acc-name acc)))

                           (fn [acc item]
                             (eval-node compiled-body
                                        (assoc scope alias item acc-name acc))))
                         start coll))))))

(defn-compile compile-if-directive [node options path]
  (let [compiled-if (compile* (:$if node) options (conj path :$if))
        compiled-then (if (contains? node :$then)
                        (compile* (:$then node) options (conj path :$then))
                        (compile* (dissoc node :$if :$else) options path))
        compiled-else (compile* (:$else node) options (conj path :$else))]

    (if (fn? compiled-if)
      (eval-fn path [scope]
               (if (eval-node compiled-if scope)
                 (eval-node compiled-then scope)
                 (eval-node compiled-else scope)))

      (if compiled-if
        (eval-fn path [scope] (eval-node compiled-then scope))
        (eval-fn path [scope] (eval-node compiled-else scope))))))

(defn- compile-let-directive [node options path]
  (let [lets (:$let node)
        compiled-locals (if (sequential? lets)
                          (map-indexed (fn [idx item]
                                         (let [k (first (keys item))
                                               v (get item k)]
                                           [k (compile* v options (into path [:$let idx k]))]))
                                       lets)
                          (map (fn [[k v]]
                                 [k (compile* v options (into path [:$let k]))])
                               lets))

        compiled-body (compile* (:$body node) options (conj path :$body))]

    (eval-fn path [scope]
             (let [new-scope (reduce (fn [acc [n v]]
                                       (assoc acc n (eval-node v acc)))
                                     scope compiled-locals)]
               (eval-node compiled-body new-scope)))))

(defn-compile compile-fn-directive [node options path]
  (let [arg-names (map keyword (:$fn node))
        compiled-body (compile* (:$body node) options (conj path :$body))]

    (eval-fn path [scope]
             (eval-fn path [& args]
                      (let [new-scope (merge scope (zipmap arg-names args))]
                        (eval-node compiled-body new-scope))))))

(defn-compile compile-switch-directive [node options path]
  (let [compiled-node (reduce-kv (fn [m k v] (assoc m k (compile* v options (conj path k))))
                                 {} node)]

    (eval-fn path [scope]
             (let [v (eval-node (:$switch compiled-node) scope)
                   v-kw (keyword (str v))]
               (eval-node (get compiled-node v-kw (:$default compiled-node)) scope)))))

(defn-compile compile-call-directive [node options path]
  (let [fn-name (keyword (:$call node))
        args (compile* (:$args node) options (conj path :$call))]

    (eval-fn path [scope]
             (if-let [fun (or (and (fn? (get scope fn-name)) (get scope fn-name))
                              (get standard-fns (keyword fn-name)))]
               (let [arg-values (eval-node args scope)]
                 (apply fun arg-values))

               (raise (str "Cannot find function " fn-name " in the current scope"))))))

(def directives
  {:$if compile-if-directive
   :$let compile-let-directive
   :$map compile-map-directive
   :$reduce compile-reduce-directive
   :$call compile-call-directive
   :$switch compile-switch-directive
   :$fn compile-fn-directive})

(defn-compile compile-map [node options path]
  (let [directives (merge directives (:directives options))
        directive-keys (cset/intersection (set (keys node))
                                          (set (keys directives)))]
    (when (> (count directive-keys) 1)
      (raise (str "More than one directive found in the node "
                  (pr-str node)
                  ". Found following directive keys: "
                  (pr-str directive-keys))))

    (if (empty? directive-keys)
      (let [result (reduce (fn [acc [key val]]
                             (let [compiled-val (compile* val options (conj path key))]
                               (-> acc
                                   (assoc-in [:result key] compiled-val)
                                   (assoc :dynamic? (or (:dynamic? acc) (fn? compiled-val))))))
                           {:dynamic? false :result {}} node)]

        (if (:dynamic? result)
          (if (:discard-map-when-everything-evaluated-to-null options)
            (let [dynamic-keys (filter #(fn? (get (:result result) %))
                                       (keys (:result result)))]
              (eval-fn path [scope]
                       (let [res (reduce (fn [acc [key val]]
                                           (assoc acc key (eval-node val scope)))
                                         {} (:result result))]
                         (if (every? #(nil? (get res %)) dynamic-keys)
                           nil
                           res))))

            (eval-fn path [scope]
                     (reduce (fn [acc [key val]]
                               (assoc acc key (eval-node val scope)))
                             {} (:result result))))

          (:result result)))

      ((get directives (first directive-keys)) node options path))))

(defn-compile compile-vector [node options path]
  (let [result (map-indexed (fn [idx item] (compile* item options (conj path idx))) node)]
    (if (some fn? result)
      (eval-fn path [scope]
               (mapv #(eval-node % scope) result))

      result)))

(def operator-to-fn
  {"+" (fn [a b] (if (string? a) (str a b) (+ a b)))
   "-" clojure.core/-
   "*" clojure.core/*
   "%" clojure.core/rem
   "=" clojure.core/=
   "!=" clojure.core/not=
   "!" clojure.core/not
   ">" clojure.core/>
   "<" clojure.core/<
   ">=" clojure.core/>=
   "<=" clojure.core/<=
   "/" clojure.core//})

(declare compile-expression-ast)

(defn- compile-expr-expr [ast options path]
  (compile-expression-ast (second ast) options path))

(defn-compile compile-op-expr [[_ left op right] options path]
  (if right
    (if-let [f (operator-to-fn op)]
      (let [compiled-left (compile-expression-ast left options path)
            compiled-right (compile-expression-ast right options path)]

        (if (or (fn? compiled-left) (fn? compiled-right))
          (eval-fn path [scope]
                   (f (eval-node compiled-left scope)
                      (eval-node compiled-right scope)))
          (f compiled-left compiled-right)))

      (raise (str "Cannot guess operator for: " op)))

    (compile-expression-ast left options path)))

(declare compile-path)
(defn-compile compile-fn-call [[_ fn-path & args] options path]
  (let [compiled-args (mapv #(compile-expression-ast % options path) args)
        compiled-fn-path (compile-path fn-path options path)]
    (eval-fn path [scope]
             (let [f (or (compiled-fn-path standard-fns)
                         (compiled-fn-path scope))]
               (when (or (nil? f) (not (fn? f)))
                 (raise (str "Attempt to call nil or non-function: " (pr-str fn-path))))

               (apply f (mapv #(eval-node % scope) compiled-args))))))

(defn-compile compile-and-expr [[_ left right] options path]
  (if right
    (let [compiled-left (compile-expression-ast left options path)
          compiled-right (compile-expression-ast right options path)]
      (if (or (fn? compiled-left) (fn? compiled-right))
        (eval-fn path [scope]
                 (and (eval-node compiled-left scope)
                      (eval-node compiled-right scope)))
        (and compiled-left compiled-right)))

    (compile-expression-ast left options path)))

(defn-compile compile-or-expr [[_ left right] options path]
  (if right
    (let [compiled-left (compile-expression-ast left options path)
          compiled-right (compile-expression-ast right options path)]
      (if (or (fn? compiled-left) (fn? compiled-right))
        (eval-fn path [scope]
                 (or (eval-node compiled-left scope)
                     (eval-node compiled-right scope)))
        (or compiled-left compiled-right)))

    (compile-expression-ast left options path)))

(defn-compile compile-unary-expr [ast options path]
  (if (= 2 (count ast))
    (compile-expression-ast (last ast) options path)

    (let [f (operator-to-fn (second ast))
          operand (compile-expression-ast (last ast) options path)]
      (eval-fn path [scope]
               (f (eval-node operand scope))))))

(defn-compile compile-num-literal [ast options path]
  #?(:clj (read-string (apply str (rest ast)))
     :cljs (js/parseFloat (apply str (rest ast)))))

(defn-compile compile-null-literal [ast options path]
  nil)

(defn-compile compile-bool-literal [[_ v] options path]
  (= v "true"))

(defn-compile compile-string-literal [[_ v] options path]
  v)

(defn- expand-wildcard [v]
  (if (sequential? v)
    v
    (if (map? v)
      (vals v)
      nil)))

(defn- compile-path-component [cmp idx options path]
  (cond
    (string? cmp) (if (re-matches #"^\d+$" cmp)
                    #?(:clj (java.lang.Long/parseLong cmp)
                       :cljs (js/parseInt cmp))

                    (keyword cmp))
    (vector? cmp)
    (let [[t arg] cmp]
      (cond
        (= :path-wildcard t) [(fn [val scope is-multiple?]
                                (if is-multiple?
                                  (remove nil? (mapcat expand-wildcard val))
                                  (remove nil? (expand-wildcard val))))
                              true]

        (= :path-predicate t) (let [compiled-pred (compile-expression-ast arg options path)]
                                [(fn [val scope is-multiple?]
                                   (vec (filter #(compiled-pred (assoc scope :this %)) val)))
                                 true])

        :else

        (if (= 0 idx)
          [(let [compiled-expr (compile-expression-ast cmp options path)]
             (fn [val scope is-multiple?]
               (compiled-expr scope)))
           false]
          [(let [compiled-expr (compile-expression-ast cmp options path)]
             (fn [val scope is-multiple?]
               (let [result (compiled-expr scope)
                     result (if (string? result) (keyword result) result)]
                 (if is-multiple?
                   (remove nil? (map #(get % result) val))
                   (get val result)))))
           false])))))

(def path-root (keyword "@"))

(defn-compile compile-path [[_ & path-comps] options path]
  (let [compiled-comps (map-indexed (fn [idx itm] (compile-path-component itm idx options path))
                                    path-comps)]
    (eval-fn path [scope]
             (loop [[cmp & tail] compiled-comps
                    val scope
                    is-multiple? false
                    idx 0]
               (let [next-val
                     (if (= path-root cmp)
                       val

                       (if (vector? cmp)
                         ((first cmp) val scope is-multiple?)

                         (if is-multiple?
                           (remove nil? (map #(get % cmp) val))
                           (get val cmp))))]

                 (if (empty? tail)
                   next-val
                   (recur tail next-val (or is-multiple?
                                            (and (vector? cmp)
                                                 (second cmp)))
                          (inc idx))))))))

(def expressions-compile-fns
  {:expr compile-expr-expr
   :additive-expr compile-op-expr
   :multiplicative-expr compile-op-expr
   :and-expr compile-and-expr
   :or-expr compile-or-expr
   :equality-expr compile-op-expr
   :comparison-expr compile-op-expr
   :unary-expr compile-unary-expr
   :num-literal compile-num-literal
   :null-literal compile-null-literal
   :bool-literal compile-bool-literal
   :string-literal compile-string-literal
   :fn-call compile-fn-call
   :path compile-path})

(defn-compile compile-expression-ast [ast options path]
  (if-let [compile-fn (get expressions-compile-fns (first ast))]
    (compile-fn ast options path)
    (raise (str "Cannot find compile function for node type " (first ast)))))

(defn- check-for-parsing-failure? [x]
  (if (insta/failure? x)
    (raise (pr-str (insta/get-failure x)))
    x))

(defn-compile compile-string [node options path]
  (if (.startsWith node "$fp")
    (raise "Fhirpath expressions are not supported yet")

    (if (.startsWith node "$")
      (-> node
          (expression-parser)
          (check-for-parsing-failure?)
          (first)
          (compile-expression-ast options path))

      node)))

(defn compile* [node options path]
  (cond
    (nil? node)        nil
    (map? node)        (compile-map node options path)
    (string? node)     (compile-string node options path)
    (sequential? node) (compile-vector node options path)
    :else node))

(defn compile
  "Compiles JUTE template into invocabe function."
  [node & [options]]

  (let [result (compile* node (or options {}) [path-root])]
    (if (fn? result)
      result
      (constantly result))))

