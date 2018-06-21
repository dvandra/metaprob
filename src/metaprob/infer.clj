;; The meta-circular Metaprob interpreter.

;; Although this file will almost always run as clojure code, it
;; is a goal to retain the ability to run it as metaprob
;; (i.e. to make it self-applicable!).  This feature has not yet been
;; tested.

(ns metaprob.infer
  (:refer-clojure :only [declare ns])
  (:require [metaprob.syntax :refer :all]
            [clojure.pprint :as pp]
            [metaprob.builtin :refer :all]
            [metaprob.prelude :refer :all]))

;; ----------------------------------------------------------------------------
;; Lexical environments, needed by gen macro.
;; TBD: Move to prelude?

(define frame?
  (gen [obj]
    (if (trace? obj)
      (trace-has-subtrace? obj "*parent*")
      false)))

(define frame-parent
  (gen [frame]
    (trace-get frame "*parent*")))

(define env-lookup
  (gen [env name]
    (if (frame? env)
      (if (trace-has? env name)
        (trace-get env name)
        (env-lookup (frame-parent env) name))
      ;; Top level environment
      (top-level-lookup env name))))

;; make-env - overrides original prelude

(define make-env
  (gen [parent]
    (mutable-trace "*parent*" parent)))

(define env-bind!
  (gen [env name val]
    (if (frame? env)
      (trace-set! env name val)
      (assert false "bad env-bind!"))))

;; match-bind - overrides original prelude.
;; Liberal in what it accepts: the input can be either a list or a
;; tuple, at any level.

(define match-bind
  ;; pattern is a parse-tree trace (variable or tuple expression) - not a tuple.
  ;; input is anything.
  (gen [pattern input env]
    (case (trace-get pattern)
      "variable"
      (env-bind! env (trace-get pattern "name") input)
      "tuple"
      (block (define count (trace-count pattern))

             (define loup
               (gen [i cursor]
                 (cond (eq i count)
                       ;; We've reached the end of the patterns
                       (assert (empty-trace? cursor)
                               ["too many inputs"
                                (length input)
                                count
                                (clojure.core/map make-immutable
                                                  (make-immutable input))
                                pattern
                                env])

                       ;; The pattern [& x] matches anything
                       (and (eq i (sub count 2))
                            (eq (trace-get pattern i) "&"))
                       (match-bind (trace-subtrace pattern (add i 1))
                                   cursor
                                   env)

                       ;; Ensure that an input remains to match remaining pattern
                       (empty-trace? cursor)
                       (assert false
                               ["too few inputs"
                                (length input)
                                count
                                (clojure.core/map make-immutable
                                                  (make-immutable input))
                                pattern
                                env])

                       ;; Bind pattern to input, and continue
                       true
                       (block (match-bind (trace-subtrace pattern i) (first cursor) env)
                              (loup (add i 1) (rest cursor))))))

             (loup 0 (to-list input)))
      true
      (assert false ["bad pattern" pattern input]))
    "return value of match-bind"))

;; -----------------------------------------------------------------------------
;; Utilities for interpreter

;; This is used to compute the key under which to store the value that
;; is the binding of a definition.

(define name-for-definiens
  (gen [pattern]
    (if (eq (trace-get pattern) "variable")
      (if (neq (trace-get pattern "name") "_")
        (addr (trace-get pattern "name"))
        (addr "definiens"))
      (addr "definiens"))))

;; The tag address business, needed for the implementation of 'this'
;; and 'with-address'

;; capture-tag-address - used in interpretation of `this`.
;; Combine the return value with an address to make a 'quasi-address'
;; to pass to resolve-tag-address, e.g.
;;   (pair (capture-tag-address ...) (addr ...))
;; This is rather hacky; there ought to be a better way to do this.

(define capture-tag-address
  (gen [intervene target output]
    ;; Cannot freeze, freezing is hereditary.
    ;; Ergo, these things can't go into addresses (addr)
    (immutable-trace "intervention-trace" intervene
                     "target-trace" target
                     "output-trace" output
                     :value "captured tag address")))

;; resolve-tag-address
;; Convert a quasi-address, whose first element was returned by 
;; capture-tag-address, into the appropriate trace

(define resolve-tag-address
  (gen [quasi-addr]
    (define captured (first quasi-addr))
    (define more (rest quasi-addr))
    (define intervene (trace-get captured "intervention-trace"))
    (define target (trace-get captured "target-trace"))
    (define output (trace-get captured "output-trace"))
    [(maybe-subtrace intervene more)
     (maybe-subtrace target more)
     (lookup output more)]))

;; Get the key to use for storing the result of a procedure call.

(define procedure-key
  (gen [exp]
    (define key (if (eq (trace-get exp) "variable")
                  (trace-get exp "name")
                  "call"))
    (assert (ok-key? key) key)
    key))

;; Extend an address.

(define extend-addr
  (gen [adr key]
    (add adr (addr key))))

;; ----------------------------------------------------------------------------

(declare infer-apply-native infer-eval)

;; Useful invariant: if output is non-nil, then on returning [value score],
;; we have value = (trace-ref output).

;; Main entry point: a version of `apply` that respects interventions
;; and constraints, records choices, and computes scores.

(define infer-apply
  (gen [proc inputs intervene target output]
    (assert (or (list? inputs) (tuple? inputs))
            ["inputs neither list nor tuple" inputs])
    (assert (or (eq output nil)
                (mutable-trace? output))
            output)
    (if (and (trace? proc) (trace-has? proc "infer-method"))
      ;; Proc is a special inference procedure returned by `inf`.
      ;; Return the value+score that the infer-method computes.
      ((trace-get proc "infer-method") inputs intervene target output)
      (if (and (foreign-procedure? proc)
               (not (or intervene target output)))
        [(generate-foreign proc inputs) 0]
        (block
         ;; Proc is a generative procedure, either 'foreign' (opaque, compiled)
         ;; or 'native' (interpreted).
         ;; First call the procedure.  We can't skip the call when there
         ;; is an intervention, because the call might have side effects.
         (define [value score]
           (if (and (trace? proc) (trace-has? proc "generative-source"))
             ;; 'Native' generative procedure
             (infer-apply-native proc inputs intervene target output)
             (if (foreign-procedure? proc)
               ;; 'Foreign' generative procedure
               [(generate-foreign proc inputs) 0]
               (block (pprint proc)
                      (error "infer-apply: not a procedure" proc)))))
         ;; Apply intervention trace to get modified value
         (define intervention? (and intervene (trace-has? intervene)))
         (define post-intervention-value
           (if intervention?
             (trace-get intervene)
             value))
         ;; Apply target trace to get modified value and score
         (define [post-target-value score2]
           (if (and target (trace-has? target))
             [(trace-get target)
              (if intervention?
                ;; Score goes infinitely bad if there is both an
                ;; intervention and a constraint, and they differ
                (if (same-trace-states? (trace-get target) post-intervention-value)
                  score
                  (do (print ["value mismatch!"
                              (trace-get target)
                              post-intervention-value])
                      negative-infinity))
                score)]
             [post-intervention-value score]))
         ;; Store value in output trace
         (if output
           (trace-set! output post-target-value))
         [post-target-value score2])))))

;; Invoke a 'native' generative procedure, i.e. one written in
;; metaprob, with inference mechanics (traces and scores).

(define infer-apply-native
  (gen [proc
        inputs
        intervene
        target
        output]
    (define source (trace-subtrace proc "generative-source"))
    (define environment (trace-get proc "environment"))
    (define new-env (make-env environment))
    ;; Extend the enclosing environment by binding formals to actuals
    (match-bind (trace-subtrace source "pattern")
                inputs
                new-env)
    (infer-eval (trace-subtrace source "body")
                new-env
                intervene
                target
                output)))

;; Evaluate the body of a 'native' procedure by recursive descent.

(define infer-eval
  (gen [exp env intervene target output]
    (assert (or (eq output nil)
                (mutable-trace? output))
            output)
    (define walk
      (gen [exp env address]
        (define [v score]
          ;; Dispatch on type of expression
          (case (trace-get exp)

            ;; Application of a procedure to arguments (call)
            "application"
            (block (define n (length (trace-keys exp)))
                   (define subscore (empty-trace))
                   (trace-set! subscore 0)
                   ;; Evaluate all subexpressions, including the procedure
                   ;; position
                   (define values
                     (map (gen [i]
                            (define [v s]
                              (walk (trace-subtrace exp i)
                                    env
                                    (extend-addr address i)))
                            (trace-set! subscore (add (trace-get subscore) s))
                            v)
                          (range n)))
                   (define new-addr
                     (extend-addr address (procedure-key (trace-subtrace exp 0))))
                   (define [val score]
                     (infer-apply (first values)
                                  (rest values)
                                  (maybe-subtrace intervene new-addr)
                                  (maybe-subtrace target new-addr)
                                  (lookup output new-addr)))
                   [val (add (trace-get subscore) score)])

            "variable"
            [(env-lookup env (trace-get exp "name")) 0]

            "literal"
            [(trace-get exp "value") 0]

            ;; Gen-expression yields a generative procedure
            "gen"
            [(mutable-trace :value "prob prog"
                            "name" (trace-name exp)
                            "generative-source" (** exp)
                            "environment" env)
             0]

            ;; Conditional
            "if"
            (block
             (define [pred pred-score]
               (walk
                (trace-subtrace exp "predicate") env
                (extend-addr address "predicate")))
             (if pred
               (block
                (define [val score]
                  (walk (trace-subtrace exp "then") env
                        (extend-addr address "then")))
                [val (add pred-score score)])
               (block
                (define [val score]
                  (walk (trace-subtrace exp "else") env
                        (extend-addr address "else")))
                [val (add pred-score score)])))

            ;; Sequence of expressions and definitions
            "block"
            (block (define n (length (trace-keys exp)))
                   (define new-env (make-env env))
                   (define subscore (empty-trace))
                   (trace-set! subscore 0)
                   (define values
                     (map          ;; How do we know map is left to right?
                      (gen [i]
                        (define [v s]
                          (walk (trace-subtrace exp i) new-env
                                (extend-addr address i)))
                        (trace-set!
                         subscore
                         (add (trace-get subscore) s))
                        v)
                      (range n)))
                   (if (gt (length values) 0)
                     [(last values) (trace-get subscore)]
                     [(empty-trace) (trace-get subscore)]))

            ;; Definition: bind a variable to some value
            "definition"
            (block (define subaddr
                     (name-for-definiens
                      (trace-subtrace exp "pattern")))
                   (define [val score]
                     (walk (trace-subtrace exp subaddr) env
                           (add address subaddr)))
                   [(match-bind
                     (trace-subtrace exp "pattern")
                     val
                     env)
                    score])

            ;; `(&this)` is the current location in the traces
            "this"
            [(capture-tag-address intervene target output)
             0]

            ;; `with-address` makes use of a location previously
            ;; captured by `&this`
            "with-address"
            (block (define [tag-addr tag-score]
                     (walk (trace-subtrace exp "tag") env
                           (extend-addr address "tag")))
                   ;; tag-addr is actually a quasi-address 
                   ;; (captured . address) - not an address.
                   ;; Must be constructed using (pair x (addr ...))
                   (define [new-intervene new-target new-output]
                     (resolve-tag-address tag-addr))
                   (define [val score]
                     (infer-eval (trace-subtrace exp "expression")
                                 env
                                 new-intervene
                                 new-target
                                 new-output))
                   [val (add tag-score score)])

            (block (pprint exp)
                   (error "Not a code expression"))))
        (if (and intervene (trace-has? intervene address))
          [(trace-get intervene address) score]
          [v score])))
    (walk exp env (addr))))

(define inf
  (gen [name infer-method]
    (trace-as-procedure (mutable-trace "name" (add "inf-" (procedure-name infer-method))
                                       "infer-method" infer-method)
                        ;; When called from Clojure:
                        (gen [& inputs]
                          (nth (infer-method inputs nil nil nil)
                               0)))))

(define apply
  (trace-as-procedure
   (inf "apply"
        (gen [inputs intervene target output]
          (infer-apply (first inputs) (rest inputs) intervene target output)))
   ;; Kludge
   (gen [proc inputs] (clojure.core/apply proc (to-immutable-list inputs)))))


;; map defined using inf (instead of with-address)

(define list-map
  (inf "map"
       (gen [[fun lst] intervene target output]
         (block (define re
                  (gen [l i]
                    (if (pair? l)
                      (block (define [valu subscore]
                               (infer-apply fun
                                            [(first l)]
                                            ;; advance traces by address i
                                            (maybe-subtrace intervene i)
                                            (maybe-subtrace target i)
                                            (lookup output i)))
                             (define [more-valu more-score]
                               (re (rest l) (add i 1)))
                             [(pair valu more-valu)
                              (add subscore more-score)])
                      [l 0])))
                (re lst 0)))))

(define map-issue-20
  (gen [f l]
    (if (tuple? l)
        (to-tuple (list-map f (to-list l)))
        (list-map f l))))
