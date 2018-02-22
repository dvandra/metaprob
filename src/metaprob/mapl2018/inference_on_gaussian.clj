;; 5.

(ns metaprob.mapl2018.inference-on-gaussian
  (:refer-clojure :only [ns declare])
  (:require [metaprob.syntax :refer :all]
            [metaprob.builtin :refer :all]
            [metaprob.prelude :refer :all]
            [metaprob.mapl2018.gaussian :refer [gaussian two-variable-gaussian-model]]
            [metaprob.mapl2018.rejection :refer :all]
            [metaprob.mapl2018.importance :refer [importance-resampling]]
            [metaprob.mapl2018.interpreters :refer :all]))

(define number-of-runs 10)

(define prior-density
  (probprog [x]
    (exp ((trace-get gaussian
                     (addr "log-output-probability-density"))
          (tuple x 0 1))) ))

(define target-density
  (probprog [x]
    (exp ((trace-get gaussian
                     (addr "log-output-probability-density"))
          (tuple x 1.5 (div 1.0 (sqrt 2.0))))) ))    ;was /

(binned-histogram
  :name    "samples from the prior"
  :samples (replicate number-of-runs
  	   	      two-variable-gaussian-model)
  :overlay-densities (list (tuple "prior" prior-density)
  		     	   (tuple "target" target-density)))

(define target-trace (empty-trace))
(trace-set (lookup target-trace (addr 1 "y" "gaussian")) 3.0)

(binned-histogram
  :name    "samples from the target"
  :samples (replicate
  	     number-of-runs
  	     (probprog
	       []
	       (rejection-sampling
	         two-variable-gaussian-model  ; :model-probprog 
		 (tuple)  ; :inputs 
		 target-trace  ; :target-trace 
	         0.5)))  ; :log-bound 
  :overlay-densities (list (tuple "prior" prior-density)
  		     	   (tuple "target" target-density)))

(binned-histogram
  :name    "samples from importance sampling with 20 particles"
  :samples (replicate
  	     number-of-runs
  	     (probprog
	       []
	       (importance-resampling
	         two-variable-gaussian-model  ; :model-probprog 
		 (tuple)  ; :inputs 
		 target-trace  ; :target-trace 
		 20)))  ; :N 
  :overlay-densities (list (tuple "prior" prior-density)
  		     	   (tuple "target" target-density)))

(binned-histogram
  :name    "samples from lightweight single-site MH with 20 iterations"
  :samples (replicate
  	     number-of-runs
	     (lightweight-single-site-MH-sampling 20 target-trace))
  :overlay-densities (list (tuple "prior" prior-density)
  		     	   (tuple "target" target-density)))
