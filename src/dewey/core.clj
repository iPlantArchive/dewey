(ns dewey.core
  (:gen-class)
  (:require [clj-jargon.init :as r-init]
            [clj-jargon.lazy-listings :as r-sq]
            [dewey.amq :as amq]
            [dewey.indexing :as indexing]))

(defn- init-irods
  []
  (let [cfg (r-init/init "localhost" "1247" "rods" "rods" "/iplant/home/rods" "iplant" "demoResc")]
    (r-init/with-jargon cfg [irods]
      (r-sq/define-specific-queries irods))
    cfg))

(defn -main
  [& args]
  (let [irods-cfg (init-irods)]
    (amq/attach-to-exchange "indexing" (partial indexing/consume-msg irods-cfg))))