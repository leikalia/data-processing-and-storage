(ns lab1-test
  (:require [clojure.test :refer :all]
            [lab1 :as sut]))

(deftest test-example
  (let [alphabet ["a" "b" "c"]
        res      (sut/all-strings alphabet 2)]
    (is (= 6 (count res)))
    (is (= (set ["ab" "ac" "ba" "bc" "ca" "cb"])
           (set res)))))

(deftest test-no-adjacent-equals
  (let [alphabet ["a" "b" "c" "d"]
        n        4
        res      (sut/all-strings alphabet n)]
    (let [a (count alphabet)
          expected (* a (long (Math/pow (dec a) (dec n))))]
      (is (= expected (count res))))
    (doseq [s res]
      (is (not (re-find #"(.)\1" s)) s))))

(deftest test-edge-cases
  (is (= '("") (sut/all-strings ["x" "y"] 0)))
  (is (= '()   (sut/all-strings [] 3)))
  )
