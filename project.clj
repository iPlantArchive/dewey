(defproject dewey "0.1-SNAPSHOT"
  :description "This is a RabbitMQ client responsible for keeping an elasticsearch index
                synchronized with an iRODS repository."
  :license {:url "file://LICENSE.txt"}
  :aot [dewey.core]
  :main dewey.core
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [cheshire "5.2.0"]
                 [clj-time "0.6.0"]
                 [clojurewerkz/elastisch "1.2.0"]
                 [com.novemberain/langohr "1.5.0"]
                 [org.iplantc/clj-jargon "0.3.1"]
                 [org.iplantc/clojure-commons "1.4.7"]]
  :repositories {"iplantCollaborative"
                 "http://projects.iplantcollaborative.org/archiva/repository/internal/"})