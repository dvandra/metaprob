(ns metaprob.inference-test
  (:refer-clojure :exclude [map replicate apply])
  (:require [clojure.test :refer :all]
            [metaprob.trace :refer :all]
            [metaprob.generative-functions :refer :all]
            [metaprob.distributions :refer :all]
            [metaprob.inference :refer :all]
            ;[metaprob.examples.gaussian :refer :all]
            ;[metaprob.examples.inference-on-gaussian :refer :all]
            [metaprob.prelude :refer :all]))

;; These tests are smoke tests, not real tests of the methods - we don't expect
;; to get meaningful results with only 16 samples. The real tests take too long
;; for `clojure -Atest` which I would like to be fast (so it can be run
;; frequently).
;;
;; For actual method tests, we use a longer-running procedure (see long_test.clj).

;; IMPORTANCE SAMPLING TESTS:
;
(def normal-normal
  (gen []
    (let-traced [x (gaussian 0 1)
                 y (gaussian x 1)]
      y)))



(def small-nsamples 24)
(def small-nbins 4)
(def weak-threshold 0.5)

;; This is to see whether the test harness itself is basically working:




(deftest check-check
  (testing "check check"
    (let [sampler (fn [i] (uniform 0 1))
          pdf (fn [x] 1)]
      (is (assay "0" sampler small-nsamples pdf small-nbins weak-threshold)))))

;; Compare sampling from Gaussian prior to exact PDF of prior:

(deftest check-prior
  (testing "check sampling from gaussian prior"
    (let [sampler (fn [i] (gaussian 0 1))
          pdf (fn [x] (exp (score-gaussian x [0 1])))]
      (is (assay "p" sampler small-nsamples pdf small-nbins weak-threshold)))))


;;; Inference methods

(defn target-density
  [x]
  (exp (score-gaussian x [1.5 (/ 1.0 (sqrt 2.0))])))

(deftest check-rejection
  (testing "check rejection sampling"
    (let [sampler (fn [i]
                    (trace-value
                      (rejection-sampling :model normal-normal
                                          :observation-trace {"y" {:value 3}}
                                          :log-bound 0.5)
                      "x"))
          pdf target-density]
      (is (assay "r" sampler small-nsamples pdf small-nbins weak-threshold)))))


;(deftest check-rejection
;  (testing "check rejection sampling"
;    (let [sampler (fn [i]
;                    (gaussian-sample-value
;                     (rejection-sampling two-variable-gaussian-model  ; :model-procedure
;                                         []  ; :inputs
;                                         target-trace  ; :target-trace
;                                         0.5)))
;          pdf target-density]
;      (is (assay "r" sampler small-nsamples pdf small-nbins weak-threshold)))))
;
(deftest check-importance
  (testing "check importance sampling"
    (let [n-particles 50
          sampler (fn [i]
                    (trace-value
                     (importance-resampling :model normal-normal
                                            :observation-trace {"y" {:value 3}}
                                            :n-particles n-particles)
                     "x"))
          pdf target-density]
      (is (assay "i" sampler small-nsamples pdf small-nbins weak-threshold)))))
;
;(deftest check-MH
;  (testing "check M-H sampling"
;    (let [steps-per-sample 50
;          sampler (fn [i]
;                    (gaussian-sample-value
;                     (lightweight-single-site-MH-sampling two-variable-gaussian-model
;                                                          []
;                                                          target-trace
;                                                          steps-per-sample)))
;          pdf target-density]
;      (is (assay "m" sampler small-nsamples pdf small-nbins weak-threshold)))))


