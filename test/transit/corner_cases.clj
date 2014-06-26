;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns transit.corner-cases)

(def forms
  [nil
   true
   false
   :a
   :foo
   'f
   'foo
   (java.util.Date.)
   1/3
   \t
   "f"
   "foo"
   "~foo"
   []
   '()
   #{}
   [1 24 3]
   `(7 23 5)
   {:foo :bar}
   #{:a :b :c}
   #{true false}
   0
   42
   [9223372036854775807N 9223372036854775808N 9223372036854775809N]
   8987676543234565432178765987645654323456554331234566789
   {false nil}
   {true nil}
   {false nil true nil}
   {"a" false}
   {"a" true}
   [\"]
   {\[ 1}
   {1 \[}
   {\] 1}
   {1 \]}
   [\{ 1]
   [\[]
   {\{ 1}
   {1 \{}
   [\` \~ \^ \#]
   ])

(def transit-json
  ["{\"~#point\":[1,2]}"
   "{\"foo\":\"~xfoo\"}"
   "{\"~/t\":null}"
   "{\"~/f\":null}"
   "{\"~#'\":\"~f-1.1E-1\"}"
   "{\"~#'\":\"~f-1.10E-1\"}"
   "{\"~#set\":[{\"~#ratio\":[\"~n4953778853208128465\",\"~n636801457410081246\"]},{\"^\\\"\":[\"~n-8516423834113052903\",\"~n5889347882583416451\"]}]}"
   ])
