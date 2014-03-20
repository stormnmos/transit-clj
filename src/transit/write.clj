;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns transit.write
  (:import [com.fasterxml.jackson.core
            JsonGenerator JsonFactory JsonEncoding JsonParser JsonToken
            JsonParseException]
           org.msgpack.MessagePack
           org.msgpack.packer.Packer
           [org.apache.commons.codec.binary Base64]
           [java.io InputStream OutputStream EOFException OutputStreamWriter]))

(set! *warn-on-reflection* true)

(def ESC \~)
(def SUB \^)
(def MIN_SIZE_CACHEABLE 3)
(def MAX_CACHE_ENTRIES 94)
(def BASE_CHAR_IDX 33)

(defn cacheable?
  [^String str as-map-key]
  (and (> (.length str) MIN_SIZE_CACHEABLE)
       (or as-map-key
           (and (= ESC ^Character (.charAt str 0))
                (or (= \: ^Character (.charAt str 1))
                    (= \$ ^Character (.charAt str 1))
                    (= \# ^Character (.charAt str 1)))))))

(defn idx->code
  [i]
  (str SUB (char (+ i BASE_CHAR_IDX))))

(defprotocol WriteCache
  (cache-write [cache str as-map-key]))

(deftype WriteCacheImpl [^:unsynchronized-mutable idx
                         ^:unsynchronized-mutable cache]
  WriteCache
  (cache-write [_ str as-map-key]
    ;;(prn "cache write before" idx cache s)
    (let [res (if (and str (cacheable? str as-map-key))
                (if-let [val (get cache str)]
                  val
                  (do
                    (when (= idx (dec MAX_CACHE_ENTRIES))
                      (set! idx 0)
                      (set! cache {}))
                    (set! cache (assoc cache str (idx->code idx)))
                    (set! idx (inc idx))
                    str))
                str)]
      ;;(prn "cache write after" idx cache res)
      res)))

(defn write-cache [] (WriteCacheImpl. 0 {}))


(def ESC \~)
(def TAG \#)
(def RESERVED \`)
(def SUB \^) ;; Should not repeat this here
(def ESC_TAG "~#")

(defn escape
  [^String s]
  (if (> (.length s) 0)
    (let [c ^Character (.charAt s 0)]
      (if (or (= ESC c)
              (= SUB c)
              (= RESERVED c))
        (str ESC s)
        s))
    s))

(defn nsed-name
  [^clojure.lang.Named kw-or-sym]
  (if-let [ns (.getNamespace kw-or-sym)]
    (str ns "/" (.getName kw-or-sym))
    (.getName kw-or-sym)))

(defprotocol Emitter
  (emit-nil [em as-map-key cache])
  (emit-string [em prefix tag s as-map-key cache])
  (emit-boolean [em b as-map-key cache])
  (emit-integer [em i as-map-key cache])
  (emit-double [em d as-map-key cache])
  (emit-binary [em b as-map-key cache])
  (array-size [em a])
  (emit-array-start [em size])
  (emit-array-end [em])
  (map-size [em m])
  (emit-map-start [em size])
  (emit-map-end [em])
  (flush-writer [em]))

(def JSON_INT_MAX (Math/pow 2 53))
(def JSON_INT_MIN (- 0 JSON_INT_MAX))

(extend-protocol Emitter
  JsonGenerator
  (emit-nil [^JsonGenerator jg as-map-key cache]
    (if as-map-key
      (emit-string jg ESC \_ nil as-map-key cache)
      (.writeNull jg)))

  ;; could write string parts w/o combining them, but have to manage escaping,
  ;; see: https://github.com/FasterXML/jackson-docs/wiki/JacksonSampleQuoteChars
  (emit-string [^JsonGenerator jg prefix tag s as-map-key cache]
    (let [s ^String (cache-write cache (str prefix tag s) as-map-key)]
      (if as-map-key
        (.writeFieldName jg s)
        (.writeString jg s))))

  (emit-boolean [^JsonGenerator jg b as-map-key cache]
    (if as-map-key
      (emit-string jg ESC \? b as-map-key cache)
      (.writeBoolean jg b)))

  (emit-integer [^JsonGenerator jg i as-map-key cache]
    (if (or as-map-key (string? i) (> i JSON_INT_MAX) (< i JSON_INT_MIN))
      (emit-string jg ESC \i i as-map-key cache)
      (.writeNumber jg ^long i)))

  (emit-double [^JsonGeneragor jg d as-map-key cache]
    (if as-map-key
      (emit-string jg ESC \d d as-map-key cache)
      (.writeNumber jg ^double d)))

  (emit-binary [^JsonGeneragor jg b as-map-key cache]
    (emit-string jg ESC \b (Base64/encodeBase64String b) as-map-key cache))

  (array-size [^JsonGenerator jg _] nil)
  (emit-array-start [^JsonGenerator jg _] (.writeStartArray jg))
  (emit-array-end [^JsonGenerator jg] (.writeEndArray jg))
  (map-size [^JsonGenerator jg _] nil)
  (emit-map-start [^JsonGenerator jg _] (.writeStartObject jg))
  (emit-map-key [^JsonGenerator jg s] (.writeFieldName jg ^String s))
  (emit-map-end [^JsonGenerator jg] (.writeEndObject jg))
  (flush-writer [^JsonGenerator jg] (.flush jg)))

(def MSGPACK_INT_MAX (Math/pow 2 64))
(def MSGPACK_INT_MIN (- 0 MSGPACK_INT_MAX))

(extend-protocol Emitter
  Packer
  (emit-nil [^Packer p as-map-key cache] (.writeNil p))

  (emit-string [^Packer p prefix tag s as-map-key cache]
    (let [s ^String (cache-write cache (str prefix tag s) as-map-key)]
      (.write p s)))

  (emit-boolean [^Packer p b as-map-key cache] (.write p b))

  (emit-integer [^Packer p i as-map-key cache]
    ;; using Long MAX and MIN because i is passed to write as long
    ;; should be able to use MSGPACK_INT_MAX/MIN - ???
    (if (or (string? i) (> i Long/MAX_VALUE) (< i Long/MIN_VALUE))
      (emit-string p ESC \i i as-map-key cache)
      (.write p ^long i)))

  (emit-double [^Packer p d as-map-key cache] (.write p ^double d))

  (emit-binary [^Packer p b as-map-key cache]
    (emit-string p ESC \b (Base64/encodeBase64String b) as-map-key cache))

  (array-size [^Packer p iter] (count iter))
  (emit-array-start [^Packer p size] (.writeArrayBegin p size))
  (emit-array-end [^Packer p] (.writeArrayEnd p))
  (map-size [^Packer p iter] (count iter))
  (emit-map-start [^Packer p size] (.writeMapBegin p size))
  (emit-map-key [^Packer p s] (.write p s))
  (emit-map-end [^Packer p] (.writeMapEnd p))
  (flush-writer [_]))

(declare marshal)

(defn emit-ints
  [em ^ints src cache]
  (areduce src i res nil (emit-integer em (aget src i) false cache)))

(defn emit-shorts
  [em ^shorts src cache]
  (areduce src i res nil (emit-integer em (aget src i) false cache)))

(defn emit-longs
  [em ^longs src cache]
  (areduce src i res nil (emit-integer em (aget src i) false cache)))

(defn emit-floats
  [em ^floats src cache]
  (areduce src i res nil (emit-double em (aget src i) false cache)))

(defn emit-doubles
  [em ^doubles src cache]
  (areduce src i res nil (emit-double em (aget src i) false cache)))

(defn emit-chars
  [em ^chars src cache]
  (areduce src i res nil (marshal em (aget src i) false cache)))

(defn emit-booleans
  [em ^booleans src cache]
  (areduce src i res nil (emit-boolean em (aget src i) false cache)))

(defn emit-objs
  [em ^objects src cache]
  (areduce src i res nil (marshal em (aget src i) false cache)))

(defn emit-array
  [em iterable _ cache]
  (emit-array-start em (array-size em iterable))
  (if-not (.isArray ^Class (type iterable))
    (reduce (fn [_ i] (marshal em i false cache)) nil iterable)
    (condp = (type iterable)
      (type (short-array 0)) (emit-shorts em iterable cache)
      (type (int-array 0)) (emit-ints em iterable cache)
      (type (long-array 0)) (emit-longs em iterable cache)
      (type (float-array 0)) (emit-floats em iterable cache)
      (type (double-array 0)) (emit-doubles em iterable cache)
      (type (char-array 0)) (emit-chars em iterable cache)
      (type (boolean-array 0)) (emit-booleans em iterable cache)
      :else (emit-objs em iterable cache)))
  (emit-array-end em))

(defn emit-map
  [em iterable _ cache]
  (emit-map-start em (map-size em iterable))
  (reduce (fn [_ [k v]]
            (marshal em k true cache)
            (marshal em v false cache))
          nil iterable)
  (emit-map-end em))

(defprotocol Handler
  (tag [_])
  (rep [_])
  (str-rep [_]))

(deftype AsTag [tag rep str])

(defn as-tag [tag rep str] (AsTag. tag rep str))

(defn emit-tagged-map
  [em tag rep _ cache]
  (emit-map-start em 1)
  (emit-string em ESC_TAG tag nil true cache)
  (marshal em rep false cache)
  (emit-map-end em))

(defn emit-encoded
  [em tag o as-map-key cache]
  (if (char? tag)
    (let [rep (rep o)]
      (if (string? rep)
        (emit-string em ESC tag rep as-map-key cache)
        (if as-map-key
          (let [rep (str-rep o)]
            (if (string? rep)
              (emit-string em ESC tag rep as-map-key cache)
              (throw (ex-info "Cannot be encoded as string" {:tag tag :o o :type (type o)}))))
          (emit-tagged-map em tag rep as-map-key cache))))
    (if as-map-key
      (throw (ex-info "Cannot be used as map key" {:tag tag :o o :type (type o)}))
      (emit-tagged-map em tag (rep o) as-map-key cache))))

(defn marshal
  [em o as-map-key cache]
  (if-let [tag (tag o)]
    (let [rep (rep o)]
      ;;(prn "marshal" tag rep)
      (case tag
        \_ (emit-nil em as-map-key cache)
        \s (emit-string em nil nil (escape rep) as-map-key cache)
        \? (emit-boolean em (boolean rep) as-map-key cache)
        \i (emit-integer em rep as-map-key cache)
        \d (emit-double em rep as-map-key cache)
        \b (emit-binary em rep as-map-key cache)
        :array (emit-array em rep as-map-key cache)
        :map (emit-map em rep as-map-key cache)
        (emit-encoded em tag o as-map-key cache)))
    (throw (ex-info "Not supported" {:o o :type (type o)}))))

(extend-protocol Handler

  (type (byte-array 0))
  (tag [_] \b)
  (rep [bs] bs)
  (str-rep [_] nil)

  nil
  (tag [_] \_)
  (rep [_] nil)
  (str-rep [_] nil)

  java.lang.String
  (tag [_] \s)
  (rep [s] s)
  (str-rep [s] s)

  java.lang.Boolean
  (tag [_] \?)
  (rep [b] b)
  (str-rep [b] (str b))
  
  java.lang.Byte
  (tag [_] \i)
  (rep [b] b)
  (str-rep [b] (str b))

  java.lang.Short
  (tag [_] \i)
  (rep [s] s)
  (str-rep [s] (str s))

  java.lang.Integer
  (tag [_] \i)
  (rep [i] i)
  (str-rep [i] (str i))

  java.lang.Long
  (tag [_] \i)
  (rep [l] l)
  (str-rep [l] (str l))

  java.math.BigInteger
  (tag [_] \i)
  (rep [bi] bi)
  (str-rep [bi] (str bi))

  clojure.lang.BigInt
  (tag [_] \i)
  (rep [bi] bi)
  (str-rep [bi] (str bi))

  java.lang.Float
  (tag [_] \d)
  (rep [f] f)
  (str-rep [f] (str f))

  java.lang.Double
  (tag [_] \d)
  (rep [d] d)
  (str-rep [d] (str d))

  java.util.Map
  (tag [_] :map)
  (rep [m] (.entrySet m))
  (str-rep [_] nil)

  java.util.List
  (tag [l] (if (seq? l) "list" :array))
  (rep [l] (if (seq? l) (as-tag :array l nil) l))
  (str-rep [_] nil)

  AsTag
  (tag [e] (.tag e))
  (rep [e] (.rep e))
  (str-rep [e] (.str e))

  java.lang.Object
  (tag [o] (when (.isArray ^Class (type o)) :array))
  (rep [o] (when (.isArray ^Class (type o)) o))

  ;; extensions

  clojure.lang.Keyword
  (tag [kw] \:)
  (rep [kw] (nsed-name kw))
  (str-rep [kw] (rep kw))

  clojure.lang.Ratio
  (tag [r] \d)
  (rep [r] (.doubleValue r))
  (str-rep [r] (str (rep r)))

  java.math.BigDecimal
  (tag [bigdec] \m)
  (rep [bigdec] (str bigdec))
  (str-rep [bigdec] (rep bigdec))

  java.util.Date
  (tag [inst] \t)
  (rep [inst] (.getTime inst))
  (str-rep [inst] (str inst))

  java.util.UUID
  (tag [uuid] \u)
  (rep [uuid]
    [(.getMostSignificantBits uuid)
     (.getLeastSignificantBits uuid)])
  (str-rep [uuid] (str uuid))

  java.net.URI
  (tag [uri] \r)
  (rep [uri] (str uri))
  (str-rep [uri] (rep uri))

  clojure.lang.Symbol
  (tag [sym] \$)
  (rep [sym] (nsed-name sym))
  (str-rep [sym] (rep sym))

  java.lang.Character
  (tag [c] \c)
  (rep [c] (str c))
  (str-rep [c] (rep c))

  java.util.Set
  (tag [s] "set")
  (rep [s] (as-tag :array s nil))
  (str-rep [s] nil)
)

(deftype Writer [em])

(defn js-writer [^OutputStream out]
  (Writer. (.createJsonGenerator (JsonFactory.) out)))

(defn mp-writer [^OutputStream out]
  (Writer. (.createPacker (MessagePack.) out)))

(defn write [^Writer writer o]
  (marshal (.em writer) o false (write-cache))
  ;; can we configure JsonGenerator to automatically flush writes?
  (flush-writer (.em writer)))




