(ns lab1)

(defn extend-word
  [alphabet word]
  (let [last-ch (subs word (dec (count word)))]
    (map #(str word %) (remove #(= % last-ch) alphabet))))

(defn all-strings
  [alphabet n]
  (cond
    (neg? n)
    (throw (IllegalArgumentException. "n must be >= 0"))

    (zero? n)
    '("")                          

    (empty? alphabet)
    '()
    :else
    (reduce
        (fn [words _]
          (reduce concat
                  (map #(extend-word alphabet %) words))) alphabet                     
        (range 1 n))))           
