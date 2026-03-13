(ns eacl.explorer.resource-macros
  (:require [clojure.java.io :as io]))

(defmacro inline-resource
  [path]
  (when-not (string? path)
    (throw (ex-info "inline-resource expects a string literal path"
                    {:path path})))
  (let [resource (io/resource path)]
    (when-not resource
      (throw (ex-info (str "Resource not found: " path)
                      {:path path})))
    (slurp resource)))
