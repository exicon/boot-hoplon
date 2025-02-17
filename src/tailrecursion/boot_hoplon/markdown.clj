(ns tailrecursion.boot-hoplon.markdown
  (:require
    [net.cgrand.enlive-html :refer [html-snippet]]
    [camel-snake-kebab.core :refer [->kebab-case]])
  (:import
    [java.lang.reflect Modifier]
    [org.pegdown.ast AbstractNode]
    [org.pegdown PegDownProcessor Extensions]))

;; helpers

(defn iterable?   [x] (instance? Iterable x))
(defn enum?       [x] (and x (.isEnum (class x))))
(defn unlist      [x] (if-not (and (seq? x) (= 1 (count x))) x (first x)))
(defn enum->kw    [x] (if-not (enum? x) x (keyword (->kebab-case (.name x)))))
(defn escape      [x] (-> x (.replaceAll "<" "&lt;") (.replaceAll ">" "&gt;")))
(defn pegdown?    [x] (and x (= (.getPackage (class x)) (.getPackage AbstractNode))))
(defn concat-strs [x] (->> (partition-by string? x)
                           (mapcat #(if-not (string? (first %)) % [(apply str %)]))))

(declare to-clj)

;; pegdown parser

(defn make-parser []
  (PegDownProcessor. (int (bit-xor Extensions/ALL
                                   Extensions/HARDWRAPS))))

(let [cache (atom nil)]
  (defn cached-parser []
    (compare-and-set! cache nil (make-parser))
    @cache))

(defn make-tree
  ([x] (make-tree (cached-parser) x))
  ([parser x] (.parseMarkdown parser (char-array x))))

;; pegdown ast nodes --> clojure maps

(defn references [x]
  (let [ref (juxt (comp first :children)
                  #(select-keys % [:url :title]))]
    (update-in x [:references] #(into {} (map ref %)))))

(defn visit-vals [x]
  (into {} (->> x (map (fn [[k v]]
                         (let [[k v] [(->kebab-case k) (enum->kw (to-clj v))]]
                           (if-not (iterable? v)
                             [k v]
                             [k (flatten (map to-clj v))])))))))

(defn add-tag [x]
  (assoc x :tag (-> x :class .getSimpleName ->kebab-case keyword)))

(defn specials [x]
  (case (:tag x)
    (:text-node
     :special-text-node
     :html-block-node
     :inline-html-node) (-> x :text escape html-snippet first)
    :root-node          (references x)
    :super-node         (unlist (:children x))
    #_:else             x))

(defn concat-text [x]
  (if-not (map? x) x (update-in x [:children] concat-strs)))

(defn clean [x]
  (if-not (map? x) x (dissoc x :class :start-index :end-index)))

(defn beanify [x]
  (let [fs (->> (.getDeclaredFields (class x))
                (remove #(Modifier/isPrivate (.getModifiers %))))
        vs (map #(.get % x) fs)
        ks (map #(keyword (->kebab-case (.getName %))) fs)]
    (into {} (-> x bean (merge (zipmap ks vs))))))

(defn to-clj [x]
  (let [x (enum->kw x)]
    (if-not (pegdown? x)
      x
      (-> x beanify visit-vals add-tag specials concat-text clean))))

;; clojure maps --> hoplon s-expressions

(defn to-sexp [node-map x]
  (if-not (map? x)
    x
    (let [{:keys [tag children]} x
          op   (get node-map tag)
          attr (dissoc x :tag :children)]
      (assert op (format "no mapping for %s" tag))
      `(~op ~attr [~@(map (partial to-sexp node-map) children)]))))

(defn parse-string
  ([node-map x]
   (parse-string (cached-parser) node-map x))
  ([parser node-map x]
   (to-sexp node-map (to-clj (make-tree parser x)))))
