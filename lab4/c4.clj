(ns c4)

(declare supply-msg)
(declare notify-msg)

(defn storage
  [ware notify-step & consumers]
  (let [counter (atom 0 :validator #(>= % 0)),
        worker-state {:storage counter,
                    :ware ware,
                    :notify-step notify-step,
                    :consumers consumers}]
    {:storage counter,
     :ware ware,
     :worker (agent worker-state)}))

(defn factory
  [amount duration target-storage & ware-amounts]
  (let [bill (apply hash-map ware-amounts),
        buffer (reduce-kv (fn [acc k _] (assoc acc k 0)) 
                          {} bill),
        worker-state {:amount amount,
                      :duration duration,
                      :target-storage target-storage,
                      :bill bill,
                      :buffer buffer}]
    {:worker (agent worker-state)}))

(defn source
  [amount duration target-storage]
  (new Thread 
       (fn []
         (Thread/sleep duration)
         (send (target-storage :worker) supply-msg amount)
         (recur))))

(defn supply-msg
  [state amount]
  (swap! (state :storage) #(+ % amount))  
  (let [ware (state :ware),
        cnt @(state :storage),                
        notify-step (state :notify-step),
        consumers (state :consumers)]
    (when (and (> notify-step 0)
               (> (int (/ cnt notify-step))
                  (int (/ (- cnt amount) notify-step))))
      (println (.format (new java.text.SimpleDateFormat "hh.mm.ss.SSS") (new java.util.Date)) 
              "|" ware "amount: " cnt))
    (when consumers
      (doseq [consumer (shuffle consumers)]
        (send (consumer :worker) notify-msg ware (state :storage) amount))))
  state)
(defn notify-msg
  [state ware storage-atom amount]
  (let [bill   (:bill state)
        buffer (:buffer state)
        take-from-storage (fn [n]
                            (if (pos? n)
                              (try
                                (let [taken (atom 0)]
                                  (swap! storage-atom
                                         (fn [cnt]
                                           (let [t (min n cnt)]
                                             (reset! taken t)
                                             (- cnt t))))
                                  @taken)
                                (catch Throwable _ 0))
                              0))]
    (let [need        (get bill ware)
          have        (get buffer ware 0)
          missing     (max 0 (- (or need 0) have))
          to-take     (min (or amount 0) missing)
          taken       (take-from-storage to-take)
          buffer1     (if (pos? taken)
                        (update buffer ware (fnil + 0) taken)
                        buffer)]
      (loop [buf buffer1]
        (if (and (seq bill)
                 (every? (fn [[k req]] (>= (get buf k 0) req)) bill))
          (let [buf-next (reduce-kv (fn [b k req] (update b k - req)) buf bill)]
            (Thread/sleep (:duration state))
            (send (-> state :target-storage :worker) supply-msg (:amount state))
            (recur buf-next))
          (assoc state :buffer buf))))))

(def safe-storage (storage "Safe" 1))
(def safe-factory (factory 1 3000 safe-storage "Metal" 3))
(def cuckoo-clock-storage (storage "Cuckoo-clock" 1))
(def cuckoo-clock-factory (factory 1 2000 cuckoo-clock-storage "Lumber" 5 "Gears" 10))
(def gears-storage (storage "Gears" 20 cuckoo-clock-factory))
(def gears-factory (factory 4 1000 gears-storage "Ore" 4))
(def metal-storage (storage "Metal" 5 safe-factory))
(def metal-factory (factory 1 1000 metal-storage "Ore" 10))
(def lumber-storage (storage "Lumber" 20 cuckoo-clock-factory))
(def lumber-mill (source 5 4000 lumber-storage))
(def ore-storage (storage "Ore" 10 metal-factory gears-factory))
(def ore-mine (source 2 1000 ore-storage))

(defn start []
  (.start ore-mine)
  (.start lumber-mill))

(defn stop []
  (.stop ore-mine)
  (.stop lumber-mill))