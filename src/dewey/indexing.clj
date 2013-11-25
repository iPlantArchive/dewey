(ns dewey.indexing
  (:require [clj-time.coerce :as t-conv]
            [clj-time.format :as t-fmt]
            [clojurewerkz.elastisch.rest.document :as es]
            [clj-jargon.init :as r-init]
            [clj-jargon.item-info :as r-info]
            [clj-jargon.metadata :as r-meta]
            [clj-jargon.permissions :as r-perm]
            [clojure-commons.file-utils :as file]))

(def ^{:private true :const true} index "data")
(def ^{:private true :const true} collection "folder")
(def ^{:private true :const true} data-object "file")

(defn- format-acl-entry
  [acl-entry]
  (letfn [(fmt-perm [perm] (case perm
                             r-perm/own-perm   :own
                             r-perm/write-perm :write
                             r-perm/read-perm  :read
                                               nil))]
    {:permission (fmt-perm (.getFilePermissionEnum acl-entry))
     :user       {:username (.getUserName acl-entry)
                  :zone     (.getUserZone acl-entry)}}))

(defn- get-data-object-acl
  [irods path]
  (let [acl (.listPermissionsForDataObject (:dataObjectAO irods) path)]
    (remove (comp nil? :permission) (map format-acl-entry acl))))

(defn- format-time
  [posix-time-str]
  (->> posix-time-str
    Long/parseLong
    t-conv/from-long
    (t-fmt/unparse (t-fmt/formatters :date-hour-minute-second-ms))))

(defn- get-date-created
 [irods entity-path]
 (format-time (r-info/created-date irods entity-path)))

(defn- get-date-modified
  [irods entity-path]
  (format-time (r-info/lastmod-date irods entity-path)))

(defn- get-metadata
  [irods entity-path]
  (letfn [(fmt-metadata [metadata] {:attribute (:attr metadata)
                                    :value     (:value metadata)
                                    :unit      (:unit metadata)})]
    (map fmt-metadata (r-meta/get-metadata irods entity-path))))

(defn- format-collection-doc
  [irods msg]
  (let [entity-path (:path msg)]
    {:id              entity-path
     :userPermissions (get-data-object-acl irods entity-path)
     :creator         (:creator msg)
     :dateCreated     (get-date-created irods entity-path)
     :dateModified    (get-date-modified irods entity-path)
     :label           (file/basename entity-path)
     :metadata        (get-metadata irods entity-path)}))

(defn- format-data-object-doc
  [irods msg]
  (let [entity-path (:path msg)]
    {:id              entity-path
     :userPermissions (get-data-object-acl irods entity-path)
     :creator         (:creator msg)
     :dateCreated     (get-date-created irods entity-path)
     :dateModified    (get-date-modified irods entity-path)
     :label           (file/basename entity-path)
     :fileSize        (:size msg)
     :fileType        (:type msg)
     :metadata        (get-metadata irods entity-path)}))

(defn- index-collection
  "Index the collection and if the collection isn't root, update the parent collection's
   dataModified field."
  [irods msg]
  (let [entry     (format-collection-doc irods msg)
        parent-id (file/rm-last-slash (file/dirname (:id entry)))]
    (es/create index collection entry :id (:entity msg))
    (when (es/present? index collection parent-id)
      (es/update-with-script index
                             collection
                             parent-id
                             "ctx._source.dateModified = dateModified"
                             {:dateModified (:dateCreated entry)}))))

(defn- index-data-object
  "Index the data object and reindex the parent collection with the dataModified field set to the
   data object's dataCreated field"
  [irods msg]
  (let [entry (format-data-object-doc irods msg)]
    (es/create index "file" entry :id (:entity msg))
    (es/update-with-script index
                           collection
                           (file/rm-last-slash (file/dirname (:id entry)))
                           "ctx._source.dateModified = dateModified"
                           {:dateModified (:dateCreated entry)})))

(defn- reindex-data-object
  [irods msg]
  (es/update-with-script index
                         data-object
                         (:entity msg)
                         "ctx._source.dateModified = dateModified;
                          ctx._source.fileSize = fileSize;"
                         {:dateModified (get-date-modified irods (:entity msg))
                          :fileSize     (:size msg)}))

(defn- resolve-consumer
  [routing-key]
  (case routing-key
    "collection.acl.mod"           nil
    "collection.add"               index-collection
    "collection.metadata.add"      nil
    "collection.metadata.adda"     nil
    "collection.metadata.cp"       nil
    "collection.metadata.mod"      nil
    "collection.metadata.rm"       nil
    "collection.metadata.rmw"      nil
    "collection.metadata.set"      nil
    "collection.mv"                nil
    "collection.rm"                nil
    "data-object.add"              index-data-object
    "data-object.cp"               index-data-object
    "data-object.metadata.add"     nil
    "data-object.metadata.adda"    nil
    "data-object.metadata.addw"    nil
    "data-object.metadata.cp"      nil
    "data-object.metadata.mod"     nil
    "data-object.metadata.rm"      nil
    "data-object.metadata.rmw"     nil
    "data-object.metadata.set"     nil
    "data-object.mod"              reindex-data-object
    "data-object.mv"               nil
    "data-object.rm"               nil
    "data-object.sys-metadata.mod" nil
    "zone.mv"                      nil
                                   nil))

(defn consume-msg
  [irods-cfg routing-key msg]
  (when-let [consume (resolve-consumer routing-key)]
    (r-init/with-jargon irods-cfg [irods]
      (consume irods msg))))