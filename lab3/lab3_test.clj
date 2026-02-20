(ns lab3-test
  (:require [clojure.test :refer :all]))

(deftest pfilter-finite-basic
  (let [data (range 20)
        pred odd?
        res  (lab3/pfilter pred data)]
    (is (= (filter pred data)
           (doall res)))))

(deftest pfilter-finite-with-params
  (let [data (range 100)
        pred #(> % 50)
        res  (lab3/pfilter pred data
                           {:block-size 5
                            :max-futures 3})]
    (is (= (filter pred data)
           (doall res)))))

(deftest pfilter-single-thread
  (let [data (range 50)
        pred even?
        res  (lab3/pfilter pred data
                           {:block-size 1
                            :max-futures 1})]
    (is (= (filter pred data)
           (doall res)))))

(deftest pfilter-infinite-lazy
  (let [pred #(zero? (mod % 3))
        data (range)
        n    30
        res  (take n (lab3/pfilter pred data
                                   {:block-size 7
                                    :max-futures 4}))]
    (is (= (take n (filter pred (range)))
           res))))

(deftest pfilter-order
  (let [data [5 4 3 2 1 0]
        pred #(>= % 2)
        res  (lab3/pfilter pred data
                           {:block-size 2
                            :max-futures 2})]
    (is (= [5 4 3 2]
           (doall res)))))
                       