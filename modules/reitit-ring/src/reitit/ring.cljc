(ns reitit.ring
  (:require [meta-merge.core :refer [meta-merge]]
            [reitit.middleware :as middleware]
            [reitit.ring.mime :as mime]
            [reitit.core :as r]
            [reitit.impl :as impl]
    #?(:clj
            [clojure.java.io :as io])))

(def http-methods #{:get :head :post :put :delete :connect :options :trace :patch})
(defrecord Methods [get head post put delete connect options trace patch])
(defrecord Endpoint [data handler path method middleware])

(defn- group-keys [data]
  (reduce-kv
    (fn [[top childs] k v]
      (if (http-methods k)
        [top (assoc childs k v)]
        [(assoc top k v) childs])) [{} {}] data))

(defn routes
  "Create a ring handler by combining several handlers into one."
  [& handlers]
  (let [single-arity (apply some-fn handlers)]
    (fn
      ([request]
       (single-arity request))
      ([request respond raise]
       (letfn [(f [handlers]
                 (if (seq handlers)
                   (let [handler (first handlers)
                         respond' #(if % (respond %) (f (rest handlers)))]
                     (handler request respond' raise))
                   (respond nil)))]
         (f handlers))))))

(defn create-default-handler
  "A default ring handler that can handle the following cases,
  configured via options:

  | key                    | description |
  | -----------------------|-------------|
  | `:not-found`           | 404, no routes matches
  | `:method-not-accepted` | 405, no method matches
  | `:not-acceptable`      | 406, handler returned `nil`"
  ([]
   (create-default-handler
     {:not-found (constantly {:status 404, :body ""})
      :method-not-allowed (constantly {:status 405, :body ""})
      :not-acceptable (constantly {:status 406, :body ""})}))
  ([{:keys [not-found method-not-allowed not-acceptable]}]
   (fn
     ([request]
      (if-let [match (::r/match request)]
        (let [method (:request-method request :any)
              result (:result match)
              handler? (or (-> result method :handler) (-> result :any :handler))
              error-handler (if handler? not-acceptable method-not-allowed)]
          (error-handler request))
        (not-found request)))
     ([request respond _]
      (if-let [match (::r/match request)]
        (let [method (:request-method request :any)
              result (:result match)
              handler? (or (-> result method :handler) (-> result :any :handler))
              error-handler (if handler? not-acceptable method-not-allowed)]
          (respond (error-handler request)))
        (respond (not-found request)))))))

#?(:clj
   (defn create-resource-handler
     "A ring handler for handling classpath resources,
      configured via options:

      | key          | description |
      | -------------|-------------|
      | :parameter   | optional name of the wildcard parameter, defaults to `:`
      | :root        | optional resource root, defaults to `public`
      | :mime-types  | optional extension->mime-type mapping, defaults to `reitit.ring.mime/default-types`
      | :path        | optional path to mount the handler to. Works only outside of a router
      "
     ([]
      (create-resource-handler nil))
     ([{:keys [parameter root mime-types path]
        :or {parameter (keyword "")
             root "public"
             mime-types mime/default-mime-types}}]
      (let [response (fn [file]
                       {:status 200
                        :body file
                        :headers {"Content-Type" (mime/ext-mime-type (.getName file) mime-types)}})]
        (if path
          (let [path-size (count path)]
            (fn
              ([req]
               (let [uri (:uri req)]
                 (if (and (>= (count uri) path-size))
                   (some->> (str root (subs uri path-size)) io/resource io/file response))))
              ([req respond _]
               (let [uri (:uri req)]
                 (if (and (>= (count uri) path-size))
                   (some->> (str root (subs uri path-size)) io/resource io/file response respond))))))
          (fn
            ([req]
             (or (some->> req :path-params parameter (str root "/") io/resource io/file response)
                 {:status 404}))
            ([req respond _]
             (respond
               (or (some->> req :path-params parameter (str root "/") io/resource io/file response)
                   {:status 404})))))))))

(defn ring-handler
  "Creates a ring-handler out of a ring-router.
  Supports both 1 (sync) and 3 (async) arities.
  Optionally takes a ring-handler which is called
  in no route matches."
  ([router]
   (ring-handler router nil))
  ([router default-handler]
   (let [default-handler (or default-handler (fn ([_]) ([_ respond _] (respond nil))))]
     (with-meta
       (fn
         ([request]
          (if-let [match (r/match-by-path router (:uri request))]
            (let [method (:request-method request :any)
                  path-params (:path-params match)
                  result (:result match)
                  handler (-> result method :handler (or default-handler))
                  request (-> request
                              (impl/fast-assoc :path-params path-params)
                              (impl/fast-assoc ::r/match match)
                              (impl/fast-assoc ::r/router router))]
              (or (handler request) (default-handler request)))
            (default-handler request)))
         ([request respond raise]
          (if-let [match (r/match-by-path router (:uri request))]
            (let [method (:request-method request :any)
                  path-params (:path-params match)
                  result (:result match)
                  handler (-> result method :handler (or default-handler))
                  request (-> request
                              (impl/fast-assoc :path-params path-params)
                              (impl/fast-assoc ::r/match match)
                              (impl/fast-assoc ::r/router router))]
              ((routes handler default-handler) request respond raise))
            (default-handler request respond raise))))
       {::r/router router}))))

(defn get-router [handler]
  (-> handler meta ::r/router))

(defn get-match [request]
  (::r/match request))

(defn coerce-handler [[path data] {:keys [expand] :as opts}]
  [path (reduce
          (fn [acc method]
            (if (contains? acc method)
              (update acc method expand opts)
              acc)) data http-methods)])

(defn compile-result [[path data] opts]
  (let [[top childs] (group-keys data)
        ->endpoint (fn [p d m s]
                     (-> (middleware/compile-result [p d] opts s)
                         (map->Endpoint)
                         (assoc :path p)
                         (assoc :method m)))
        ->methods (fn [any? data]
                    (reduce
                      (fn [acc method]
                        (cond-> acc
                                any? (assoc method (->endpoint path data method nil))))
                      (map->Methods {})
                      http-methods))]
    (if-not (seq childs)
      (->methods true top)
      (reduce-kv
        (fn [acc method data]
          (let [data (meta-merge top data)]
            (assoc acc method (->endpoint path data method method))))
        (->methods (:handler top) data)
        childs))))

(defn router
  "Creates a [[reitit.core/Router]] from raw route data and optionally an options map with
  support for http-methods and Middleware. See [docs](https://metosin.github.io/reitit/)
  for details.

  Example:

      (router
        [\"/api\" {:middleware [wrap-format wrap-oauth2]}
          [\"/users\" {:get get-user
                       :post update-user
                       :delete {:middleware [wrap-delete]
                               :handler delete-user}}]])

  See router options from [[reitit.core/router]] and [[reitit.middleware/router]]."
  ([data]
   (router data nil))
  ([data opts]
   (let [opts (meta-merge {:coerce coerce-handler, :compile compile-result} opts)]
     (r/router data opts))))
