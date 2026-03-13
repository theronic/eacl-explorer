(ns eacl.explorer.support
  (:require [eacl.explorer.seed :as seed]))

(defn with-test-runtime*
  [profile f]
  (let [{:keys [conn client] :as runtime} (seed/create-runtime)]
    (seed/install-schema+fixtures! conn client {:seed/profile profile})
    (f runtime)))
