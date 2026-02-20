(ns lab3)

(defn pfilter
  ([pred coll]
   (pfilter pred coll {}))
  ([pred coll {:keys [block-size max-futures]
               :or   {block-size  64
                      max-futures (.availableProcessors (Runtime/getRuntime))}}]
   (let [block-size  (max 1 block-size)
         max-futures (max 1 max-futures)
         blocks (partition-all block-size coll)
         make-fut (fn [block]
                    (future
                      (doall (filter pred block))))
         initial-blocks   (doall (take max-futures blocks))
         remaining-blocks (drop max-futures blocks)]
     (letfn [(step [futs blocks-left]
               (lazy-seq
                 (when (seq futs)
                   (let [head-fut  (first futs)
                         tail-futs (subvec futs 1)
                         [tail-futs blocks-left]
                         (if-let [b (first blocks-left)]
                           [(conj tail-futs (make-fut b))
                            (rest blocks-left)]
                           [tail-futs blocks-left])
                         result-block @head-fut]
                     (concat result-block
                             (step tail-futs blocks-left))))))]
       (step (vec (map make-fut initial-blocks))
             remaining-blocks)))))

(defn estimate []
  (let [f
   (fn [x] 
   (Thread/sleep 100) (even? x))]
      (time (doall (pfilter f (range 1 200))))))