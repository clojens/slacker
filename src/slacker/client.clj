(ns slacker.client
  (:use [slacker common serialization protocol])
  (:use [lamina core connections])
  (:use [aleph.tcp])
  (:import [slacker SlackerException]))

(defn- handle-response [content-type code data]
  (case code
   :success ((deserializer content-type) (first data))
   :not-found (throw (SlackerException. "function not found."))
   :exception (throw (SlackerException.
                      ((deserializer content-type) (first data))))
   :protocol-mismatch (throw (SlackerException.
                              "client-server version mismatch."))
   :else (throw (SlackerException. (str "invalid result code: " code)))))

(defn- make-request [content-type func-name params]
  (let [serialized-params ((serializer content-type) params)]
    [version :type-request content-type func-name serialized-params]))

(defprotocol SlackerClientProtocol
  (sync-call-remote [this func-name params])
  (async-call-remote [this func-name params cb])
  (close-slackerc [this]))

(deftype SlackerClient [host port conn content-type]
  SlackerClientProtocol
  (sync-call-remote [this func-name params]
    (let [request (make-request content-type func-name params)
          response (wait-for-result (conn request) *timeout*)]
      (when-let [[_ _ _ code data] response]
        (handle-response content-type code data))))
  (async-call-remote [this func-name params cb]
    (let [result-promise (promise)]
      (run-pipeline
       (conn (make-request content-type func-name params))
       #(if-let [[_ _ _ code data] %]
          (let [result (handle-response content-type code data)]
            (deliver result-promise result)
            (if-not (nil? cb) (cb result)))))
      result-promise))
  (close-slackerc [this]
    (close-connection conn)))

(defn slackerc
  "Create connection to a slacker server."
  [host port
   & {:keys [content-type]
      :or {content-type :carb}}]
  (let [conn (client #(tcp-client {:host host
                                   :port port
                                   :encoder slacker-request-codec
                                   :decoder slacker-response-codec}))]
    (SlackerClient. host port conn content-type)))

(defn with-slackerc
  "Invoke remote function with given slacker connection.
  A call-info tuple should be passed in. Usually you don't use this
  function directly. You should define remote call facade with defremote"
  [sc remote-call-info
   & {:keys [async callback]
      :or {async false callback nil}}]
  (let [[fname args] remote-call-info]
    (if (or async (not (nil? callback)))
      (async-call-remote sc fname args callback)
      (sync-call-remote sc fname args))))

(defmacro defremote
  "Define a facade for remote function. You have to provide the
  connection and the function name. (Argument list is not required here.)"
  [sc fname & {:keys [remote-name async callback]
               :or {remote-name nil async false callback nil}}]
  `(defn ~fname [& args#]
     (with-slackerc ~sc
       [(or ~remote-name (name '~fname))
        (into [] args#)]
       :async ~async
       :callback ~callback)))

