(ns clojure.core.matrix.impl.ndarray
  (:require [clojure.walk :as w])
  (:use clojure.core.matrix.utils)
  (:use clojure.core.matrix.impl.ndarray-macro)
  (:require [clojure.core.matrix.impl.ndarray-magic :as magic])
  (:require [clojure.core.matrix.protocols :as mp])
  (:require [clojure.core.matrix.implementations :as imp])
  (:require [clojure.core.matrix.impl.mathsops :as mops])
  (:require [clojure.core.matrix.multimethods :as mm])
  (:refer-clojure :exclude [vector?]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;; **NOTE**: this file was generated as follows:
;;
;;     lein marg -d docs -f ndarray.html -n "NDArray for core.matrix" \
;;         src/main/clojure/clojure/core/matrix/impl/ndarray.clj \
;;         src/main/clojure/clojure/core/matrix/impl/ndarray_magic.clj \
;;         src/main/clojure/clojure/core/matrix/impl/ndarray_macro.clj
;;
;; [py1]: http://docs.scipy.org/doc/numpy/reference/arrays.ndarray.html
;; [py2]: http://scipy-lectures.github.io/advanced/advanced_numpy/
;; [mult]: http://penguin.ewu.edu/~trolfe/MatMult/MatOpt.html
;; [lu]: https://github.com/vitaut/gsl/blob/master/linalg/lu.c "GNU Linear Algebra LU-decomposition routines"


;; ## Intro
;;
;; This is an implementation of strided N-Dimensional array (or NDArray
;; for short). The underlying structure is similar to NumPy's [[py1]],
;; [[py2]].
;;
;; Default striding scheme is row-major, as in C. It's efficient on most
;; modern processors. However, some functions (like `main-diagonal`) return
;; "views" into original array with different striding scheme and this can
;; affect performance of expensive functions like matrix multiplication.
;; To avoid this penalty please use `clone` before the application, because
;; `clone` always packs data in memory.
;;
;; ## Default striding schemes
;;
;; When we are using C-like striding (row-major ordering), the formula for
;; strides is as follows (see [[py2]]):
;;
;; $$shape = (d\_1, d\_2, \dots, d\_N)$$
;; $$strides = (s\_1, s\_2, \dots, s\_N)$$
;; $$s_j = d\_{j+1} d\_{j+2} \dots d\_N$$

(defn c-strides [shape]
  (let [shape-ints (int-array shape)
        n (alength shape-ints)]
    (if-not (> n 0)
      (int-array [1])
      (let [strides (int-array n)]
        (aset strides (dec n) (int 1))
        (c-for [i (int (- n 2)) (>= i 0) (dec i)]
          (aset strides i (* (aget strides (inc i))
                             (aget shape-ints (inc i)))))
        strides))))

;; We can easily check for correctness here using NumPy:
;;
;;     np.empty([4, 3, 2], dtype=np.int8, order="c").strides
;;     # (6, 2, 1)
;;
;; An actual test can be found in namespace test-ndarray-implementation.

(magic/init
 {:object {:regname :ndarray
           :fn-suffix nil
           :typename 'NDArray
           :array-tag 'objects
           :array-cast 'object-array
           :type-cast 'identity
           :type-object java.lang.Object}
  :long {:regname :ndarray-long
         :fn-suffix 'long
         :typename 'NDArrayLong
         :array-tag 'longs
         :array-cast 'long-array
         :type-cast 'long
         :type-object Long/TYPE}
  :float {:regname :ndarray-float
          :fn-suffix 'float
          :typename 'NDArrayFloat
          :array-tag 'floats
          :array-cast 'float-array
          :type-cast 'float
          :type-object Float/TYPE}
  :double {:regname :ndarray-double
           :fn-suffix 'double
           :typename 'NDArrayDouble
           :array-tag 'doubles
           :array-cast 'double-array
           :type-cast 'double
           :type-object Double/TYPE}})

;; ## The structure
;;
;; The structure is identical to NumPy's. Strides are stored explicitly;
;; this allows to perform some operations like transposition or
;; broadcasting to be done on "strides" field alone, avoiding touching
;; the data itself.

(magic/with-magic
  [:long :float :double :object]
  (deftype typename#
      [^array-tag# data
       ^int ndims
       ^ints shape ;; this is compiled to type Object, so ints should be casted
       ^ints strides
       ^int offset]))

;; TODO: describe auto-declaring

;; ## Constructors
;;
;; Constructing of an empty NDArray with given shape is fairly easy.
;; Here, we provide an optional argument so user can choose to use
;; Fortran-like data layout.

(magic/with-magic
  [:long :float :double :object]
  (defn empty-ndarray
    "Returns an empty NDArray of given shape"
    [shape & {:keys [order] :or {order :c}}]
    (let [shape (int-array shape)
          ndims (count shape)
          strides (c-strides shape)
          len (reduce * shape)
          data (array-cast# len)
          offset 0]
      (new typename# data ndims shape strides offset))))

(magic/with-magic
  [:long :float :double]
  (defn empty-ndarray-zeroed
    "Returns an empty NDArray of given shape, guaranteed to be zeroed"
    [shape]
    (let [shape (int-array shape)
          ndims (count shape)
          strides (c-strides shape)
          len (reduce * shape)
          data (array-cast# len)
          offset 0]
      (new typename# data ndims shape strides offset))))

(magic/with-magic
  [:object]
  (defn empty-ndarray-zeroed
    "Returns an empty NDArray of given shape, guaranteed to be zeroed"
    [shape & {:keys [order] :or {order :c}}]
    (let [shape (int-array shape)
          ndims (count shape)
          strides (c-strides shape)
          len (reduce * shape)
          data (array-cast# len)
          offset 0
          m (new typename# data ndims shape strides offset)]
      (java.util.Arrays/fill ^objects data
                             (cast java.lang.Object 0.0))
      m)))

;; Here we construct NDArray with given data. The caveat here is that we
;; can't really use this definition until we define an implementation for
;; protocol PIndexedSettingMutable because of mp/assign! use.

(magic/with-magic
  [:long :float :double :object]
  (defn ndarray
    "Returns NDArray with given data, preserving shape of the data"
    [data]
    (let [mtx (empty-ndarray#t (mp/get-shape data))]
      (mp/assign! mtx data)
      mtx)))

(magic/with-magic
  [:long :float :double :object]
  (defn arbitrary-slice
    [^typename# m dim idx]
    (iae-when-not (> (.ndims m) 0)
      (str "can't get slices on [" (.ndims m) "]-dimensional object"))
    (let [^array-tag# data (.data m)
          ndims (.ndims m)
          ^ints shape (.shape m)
          ^ints strides (.strides m)
          offset (.offset m)
          new-ndims (dec ndims)
          new-shape (abutnth dim shape)
          new-strides (abutnth dim strides)
          new-offset (+ offset (* idx (aget strides dim)))]
      (iae-when-not (< new-offset (alength data))
        "new offset is larger than the array itself")
      (new typename# data new-ndims new-shape new-strides new-offset))))

(magic/with-magic
  [:long :float :double :object]
  (defn row-major-slice
    [^typename# m idx]
    (arbitrary-slice#t m 0 idx)))

;; TODO: this should be a macro, transpose takes way too much time because
;;       of this function (64ns just for restriding)
(magic/with-magic
  [:long :float :double :object]
  (defn reshape-restride
    [^typename# m new-ndims ^ints new-shape ^ints new-strides new-offset]
    (let [^array-tag# data (.data m)
          new-ndims (int new-ndims)
          new-offset (int new-offset)]
      (new typename# data new-ndims new-shape new-strides new-offset))))

;; ## Seqable
;;
;; In general there is huge chunk of default-ish stuff that can be used here
;; (see Seqable implementation in wrappers that use PSliceSeq and
;; get-major-slice-seq, that in turn uses get-major-slice iteratively),
;; but it looks horribly inefficient, so let's build a lazy seq here.
;; NOTE: an actual implementations is now in the deftype itself, because
;; Seqable is not a real protocol

(magic/with-magic
  [:long :float :double :object]
  (defn row-major-seq [^typename# m]
    (iae-when-not (> (.ndims m) 0)
      (str "can't get slices on [" (.ndims m) "]-dimensional object"))
    (let [^ints shape (.shape m)]
      (map (partial row-major-slice#t m) (range (aget shape 0))))))

(magic/with-magic
  [:long :float :double :object]
  (defn row-major-seq-no0d
    "like row-major-seq but drops NDArray's wrapping on 0d-slices"
    [^typename# m]
    (if (== (.ndims m) 1)
      (map mp/get-0d (row-major-seq#t m))
      (row-major-seq#t m))))

;; NOTE: to extend this to :object, clojure.math.numeric-tower is needed
(magic/with-magic
  [:double]
  (defn lu-decompose!
    "LU-decomposition of a matrix into P A = L U. Saves L and U into
     the input matrix as follows: L is a lower triangular part of it,
     with diagonal omitted (they are all equal to 1); U is an upper
     triangular part. P returned as a primitive int permutation array.
     Returns a vector of two values: first is integer (-1)^n, where n is
     a number of permutations, and second is a primitive int permutations
     array.
     This function is translated from GNU linear algebra library, namely
     `gsl_linalg_LU_decomp` (see [lu] for example)"
    [^typename# m]
    (expose-ndarrays [m]
      (iae-when-not (== m-ndims 2)
        "lu-decompose! can operate only on matrices")
      (iae-when-not (== (aget m-shape 0) (aget m-shape 1))
        "lu-decompose! can operate only on square matrices")
      (let [n (aget m-shape 0)
            ;; permutations array
            permutations (int-array (range n))
            ;; sign of determinant
            sign (int-array [1])]
        ;; for all columns
        (c-for [j (int 0) (< j (dec n)) (inc j)]
          (let [i-pivot
                (loop [i (inc j)
                       max-i j
                       max (Math/abs (aget-2d* m j j))]
                  (if (< i n)
                    (let [current (Math/abs (aget-2d* m i j))]
                      (if (< max current)
                        (recur (inc i) i current)
                        (recur (inc i) max-i max)))
                    (do (iae-when-not (not (== max 0))
                          "lu-decompose can't decompose singular matrix")
                        max-i)))
                pivot (aget-2d* m i-pivot j)]
            ;; when maximum element is not on diagonal, swap rows, update
            ;; permutations and permutation counter
            (when (not (== i-pivot j))
              (c-for [k (int 0) (< k n) (inc k)]
                (let [swap (aget-2d* m i-pivot k)]
                  (aset-2d* m i-pivot k (aget-2d* m j k))
                  (aset-2d* m j k swap)))
              (let [swap (aget permutations i-pivot)]
                (aset permutations i-pivot j)
                (aset permutations j swap))
              (aset sign 0 (* -1 (aget sign 0))))
            (c-for [i (inc j) (< i n) (inc i)]
              (let [scaled (/ (aget-2d* m i j) pivot)]
                (aset-2d* m i j scaled)
                (c-for [k (inc j) (< k n) (inc k)]
                  (aset-2d* m i k (- (aget-2d* m i k)
                                     (* (aget-2d* m j k)
                                        scaled))))))))
        [(aget sign 0) permutations]))))

(magic/with-magic
  [:double]
  (defn lu-solve!
    "Solves a system of linear equations Ax = b using LU-decomposition.
     lu should be a decomposition of A in a form produced by lu-decompose!,
     permutations should be a primitive int vector of permutations (as from
     lu-decompose!), x should be a primitive vector of right hand sides. After
     an execution of this function x will be replaced with solution vector."
    [^typename# lu ^ints permutations ^array-tag# x]
    (expose-ndarrays [lu]
      (iae-when-not (== lu-ndims 2)
        "lu-solve! can operate only on matrices")
      (iae-when-not (== (aget lu-shape 0) (aget lu-shape 1))
        "lu-solve! can operate only on square matrices")
      (let [n (aget lu-shape 0)]
        ;; Solving Ly = b using forward substitution
        (c-for [i (int 0) (< i n) (inc i)]
          (loop [j (int 0)
                 s (aget x i)]
            (if (< j i)
              (recur (inc j) (- s (* (aget-2d* lu i j)
                                     (aget x j))))
              (aset x i s))))
        ;; Solving Ux = y using backward substitution
        (aset x (dec n) (/ (aget x (dec n))
                               (aget-2d* lu (dec n) (dec n))))
        (c-for [i (- n 2) (>= i 0) (dec i)]
          (loop [j (inc i)
                 s (aget x i)]
            (if (< j n)
              (recur (inc j) (- s (* (aget-2d* lu i j)
                                     (aget x j))))
              (aset x i (/ s (aget-2d* lu i i))))))
        nil))))

(magic/with-magic
  [:double]
  (defn invert
    "Inverts given matrix. Returns new one"
    [^typename# m]
    (expose-ndarrays [m]
      (iae-when-not (== m-ndims 2)
        "invert can operate only on matrices")
      (iae-when-not (== (aget m-shape 0) (aget m-shape 1))
        "invert can operate only on square matrices")
      (let [n (aget m-shape 0)
            ^array-tag# x (array-cast# n)
            ^typename# lu (mp/clone m)
            ^typename# m-inverted (empty-ndarray#t [n n])
            lu-output (lu-decompose!#t lu) ; lu-decompose! mutates lu
            ^ints permutations (second lu-output)]
        (expose-ndarrays [m-inverted]
          (c-for [i (int 0) (< i n) (inc i)]
            (c-for [j (int 0) (< j n) (inc j)]
              (if (== (aget permutations j) i)
                (aset x j (type-cast# 1))
                (aset x j (type-cast# 0))))
            (lu-solve!#t lu permutations x)
            (c-for [j (int 0) (< j n) (inc j)]
              (aset-2d* m-inverted j i (aget x j)))))
        m-inverted))))

(magic/with-magic
  [:double]
  (defn determinant
    "Inverts given matrix. Returns new one"
    [^typename# m]
    (expose-ndarrays [m]
      (iae-when-not (== m-ndims 2)
        "invert can operate only on matrices")
      (iae-when-not (== (aget m-shape 0) (aget m-shape 1))
        "invert can operate only on square matrices")
      (let [n (aget m-shape 0)
            ^typename# lu (mp/clone m)
            lu-output (lu-decompose!#t lu) ; lu-decompose! mutates lu
            sign (first lu-output)]
        (expose-ndarrays [lu]
          (loop [i (int 0)
                 det (type-cast# sign)]
            (if (< i n)
              (recur (inc i) (* det (aget-2d* lu i i)))
              det)))))))

(magic/extend-types
  [:long :float :double :object]
  java.lang.Object
    (toString [m]
       (str (mp/persistent-vector-coerce m)))

  clojure.lang.Seqable
    (seq [m]
      (row-major-seq-no0d#t m))

  clojure.lang.Sequential

;; ## Mandatory protocols for all matrix implementations
;;
;; This bunch of protocols is mandatory for all core.matrix implementations.
;;
;; PImplementation protocol contains methods for providing implementation
;; metadata and matrix construction.

  mp/PImplementation
    (implementation-key [m] regname#)
    (meta-info [m]
      {:doc "An implementation of strided N-Dimensional array"})
    (new-vector [m length]
      (empty-ndarray-zeroed#t [length]))
    (new-matrix [m rows columns]
      (empty-ndarray-zeroed#t [rows columns]))
    (new-matrix-nd [m shape]
      (empty-ndarray#t shape))
    (construct-matrix [m data]
      (ndarray#t data))
    (supports-dimensionality? [m dims]
      true)

;; PDimensionInfo is for letting know core.matrix about dimensionality
;; of the matrix.

  mp/PDimensionInfo
    (get-shape [m] (vec shape))
    (is-vector? [m] (= 1 ndims))
    (is-scalar? [m] false)
    (dimensionality [m] ndims)
    (dimension-count [m x] (aget shape x))

;; PIndexedAccess protocol defines a bunch of functions to allow
;; (surprisingly) indexed access into matrix.

  mp/PIndexedAccess
    (get-1d [m x]
    ;; TODO: check if this check is really needed
      (iae-when-not (= 1 (.ndims m))
        "can't use get-1d on non-vector")
      (aget data (+ offset (* (aget strides 0) x))))
    (get-2d [m x y]
      (iae-when-not (= 2 (.ndims m))
        "can't use get-2d on non-matrix")
      (let [idx (+ offset
                   (+ (* (aget strides 0) (int x))
                      (* (aget strides 1) (int y))))]
        (aget data idx)))
    (get-nd [m indexes]
      (iae-when-not (= (count indexes) ndims)
        "index count should match dimensionality")
      (let [idxs (int-array indexes)]
        (aget-nd data strides offset idxs)))

;; PIndexedSetting is for non-mutative update of a matrix. Here we emulate
;; "non-mutative" setting by making a mutable copy and mutating it.

  mp/PIndexedSetting
    (set-1d [m row v]
      (let [m-new (mp/clone m)]
        (mp/set-1d! m-new row v)
        m-new))
    (set-2d [m row column v]
      (let [m-new (mp/clone m)]
        (mp/set-2d! m-new row column v)
        m-new))
    (set-nd [m indexes v]
      (let [m-new (mp/clone m)]
        (mp/set-nd! m-new indexes v)
        m-new))
    (is-mutable? [m] true)

;; ## Mandatory protocols for mutable matrix implementations
;;
;; In this section, protocols that help to work with mutable matrices are
;; defined. It is worth noting that in the previous section, namely
;; PIndexedSetting protocol implementation, we used mutative operations,
;; therefore this section is required for previous section to work.
;;
;; PIndexedSettingMutable defines operations for matrix mutation at given
;; index.

  mp/PIndexedSettingMutable
    (set-1d! [m x v]
      (when-not (== 1 ndims)
        (throw (IllegalArgumentException. "can't use set-1d! on non-vector")))
      (aset data (+ offset x) (type-cast# v)))
    (set-2d! [m x y v]
      (when-not (== 2 ndims)
        (throw (IllegalArgumentException. "can't use set-2d! on non-matrix")))
      (let [idx (+ (* (aget strides 0) (int x))
                   (* (aget strides 1) (int y))
                   offset)]
        (aset data idx (type-cast# v))))
    (set-nd! [m indexes v]
      (when-not (= (count indexes) ndims)
        (throw (IllegalArgumentException.
                "index count should match dimensionality")))
      (let [idxs (int-array indexes)]
        (aset-nd data strides offset idxs (type-cast# v))))

;; TODO: document normalizing

  mp/PMatrixCloning
    (clone [m]
      (let [a (empty-ndarray#t (.shape m))]
        (loop-over [m a]
          (aset a-data a-idx (aget m-data m-idx)))
        a))

;; ## Optional protocols
;;
;; Following protocols are implemented for performance or better behaviour.

  mp/PConversion
    (convert-to-nested-vectors [m]
      (case ndims
        0 (aget data offset)
        1 (let [n (aget shape 0)
                stride (aget strides 0)]
            (loop [idx (int offset)
                   cnt (int 0)
                   res []]
              (if (< cnt n)
                (recur (+ idx stride) (inc cnt) (conj res (aget data idx)))
                res)))
        ;; TODO: this can be done more efficiently
       (mapv mp/convert-to-nested-vectors
             (mp/get-major-slice-seq m))))

  mp/PTypeInfo
    (element-type [m] type-object#)

  mp/PMutableMatrixConstruction
    (mutable-matrix [m] (mp/clone m))

  mp/PZeroDimensionAccess
    (get-0d [m] (aget data offset))
    (set-0d! [m v] (aset data offset (type-cast# v)))

  mp/PSpecialisedConstructors
    (identity-matrix [m n]
      (let [^typename# new-m (empty-ndarray#t [n n])
            ^array-tag# new-m-data (.data new-m)]
        (when (= type-object# java.lang.Object)
          (c-for [i (int 0) (< i (* n n)) (inc i)]
            (aset new-m-data i (type-cast# 0))))
        (c-for [i (int 0) (< i n) (inc i)]
          (aset new-m-data (+ i (* i n)) (type-cast# 1)))
        new-m))
    (diagonal-matrix [m diag]
      (let [prim-diag (array-cast# diag)
            n (alength prim-diag)
            ^typename# new-m (empty-ndarray#t [n n])
            ^array-tag# new-m-data (.data new-m)]
        (when (= type-object# java.lang.Object)
          (c-for [i (int 0) (< i (* n n)) (inc i)]
            (aset new-m-data i (type-cast# 0))))
        (c-for [i (int 0) (< i n) (inc i)]
          (aset new-m-data (int (+ i (* i n)))
                (type-cast# (aget prim-diag i))))
        new-m))

  ;; mp/PCoercion
  ;;   (coerce-param [m param])
  ;; mp/PBroadcast
  ;;   (broadcast [m target-shape])
  ;; mp/PBroadcastLike
  ;;   (broadcast-like [m a])
  ;; mp/PReshaping
  ;;   (reshape [m shape])

  mp/PMatrixSlices
    (get-row [m i]
      (iae-when-not (== ndims 2)
        "get-row is applicable only for matrices")
      (row-major-slice#t m i))
    (get-column [m i]
      (iae-when-not (== ndims 2)
        "get-column is applicable only for matrices")
      (arbitrary-slice#t m 1 i))
    (get-major-slice [m i]
      (row-major-slice#t m i))
    (get-slice [m dimension i]
      (arbitrary-slice#t m dimension i))

  mp/PSubVector
    (subvector [m start length]
      (iae-when-not (== ndims 1)
        "subvector is applicable only for vectors")
      (let [new-shape (int-array 1 (int length))
            new-offset (+ offset (* (aget strides 0) start))]
        (reshape-restride#t m ndims new-shape strides new-offset)))

  mp/PSliceView
   (get-major-slice-view [m i] (row-major-slice#t m i))

  mp/PSliceSeq
    (get-major-slice-seq [m] (seq m))

  ;; mp/PSliceJoin
  ;;   (join [m a])

  ;; TODO: generalize for higher dimensions (think tensor trace)
  ;; TODO: make it work for rectangular matrices
  ;; TODO: clarify docstring about rectangulra matrices
  ;; TODO: clarify docstring about higher dimensions
  mp/PMatrixSubComponents
    (main-diagonal [m]
      (iae-when-not (and (== ndims 2) (== (aget shape 0)
                                          (aget shape 1)))
        "main-diagonal is applicable only for square matrices")
      (let [new-ndims (int 1)
            new-shape (int-array 1 (aget shape 0))
            new-strides (int-array 1 (* (inc (aget shape 0))
                                        (aget strides 1)))]
        (reshape-restride#t m new-ndims new-shape new-strides offset)))

  ;; mp/PAssignment

  ;; TODO: will not work for stride != 1
  mp/PMutableFill
    (fill! [m v]
      (let [end (+ offset (areduce shape i s (int 1)
                                   (* s (aget shape i))))]
        (c-for [i offset (< i end) (inc i)]
          (aset data i (type-cast# v)))))

  ;; may be not applicable to non-double?
  ;; mp/PDoubleArrayOutput

  ;; mp/PMatrixEquality
  ;;   (matrix-equals [a b]
  ;;     (if (identical? a b)
  ;;       true
  ;;       (if-not (instance? typename# b)
  ;;         ;; Coerce second argument to first one
  ;;         (mp/matrix-equals a (mp/coerce-param a b))
  ;;         ;; Fast path, types are same
  ;;         (loop-over-2d [a b] true
  ;;           (if (== (aget a-data a-idx)
  ;;                   (aget b-data b-idx))
  ;;             (continue true)
  ;;             (break false))))))

  mp/PMatrixEquality
    (matrix-equals [a b]
      (if (identical? a b)
        true
        (if-not (instance? typename# b)
          ;; Coerce second argument to first one
          (mp/matrix-equals a (mp/coerce-param a b))
          ;; Fast path, types are same
          (let [^typename# b b
                ^ints shape-b (.shape b)
                ^array-tag# data-b (.data b)
                ^ints strides-b (.strides b)
                offset-b (.offset b)]
            (if (not (java.util.Arrays/equals shape shape-b))
              false
              (case ndims
                0 (== (aget data 0) (aget data-b 0))
                1 (let [step-a (aget strides 0)
                        step-b (aget strides-b 0)
                        end (+ offset (* (aget shape 0) step-a))]
                    (loop [i-a offset
                           i-b offset-b]
                      (if (< i-a end)
                        (if (== (aget data i-a) (aget data-b i-b))
                          (recur (+ i-a step-a) (+ i-b step-b))
                          false)
                        true)))
                2 (let [nrows (aget shape 0)
                        ncols (aget shape 1)
                        step-col-a (aget strides 1)
                        step-row-a (- (aget strides 0)
                                      (* step-col-a ncols))
                        step-col-b (aget strides-b 1)
                        step-row-b (- (aget strides 0)
                                      (* step-col-b ncols))
                        end (+ offset (+ (* nrows step-row-a)
                                         (* ncols step-col-a)))]
                    (loop [i-a offset
                           i-b offset-b
                           row-a 0
                           col-a 0]
                      (if (< i-a end)
                        (if (== (aget data i-a) (aget data-b i-b))
                          (if (< col-a ncols)
                            (recur (+ i-a step-col-a) (+ i-b step-col-b)
                                   row-a (inc col-a))
                            (recur (+ i-a step-row-a) (+ i-b step-row-b)
                                   (inc row-a) 0))
                          false)
                        true)))
                ;; N-dimensional case
                (let [end (+ offset
                             (areduce shape i s (int 0)
                                      (+ s (* (aget shape i)
                                              (aget strides i)))))]
                  (loop [idxs (int-array ndims)]
                    (if (== (aget-nd data strides offset idxs)
                            (aget-nd data-b strides-b offset-b idxs))
                      (if (loop [dim (int (dec ndims))]
                            (if (>= dim 0)
                              (if (< (aget idxs dim) (dec (aget shape dim)))
                                (do (aset idxs dim (inc (aget idxs dim)))
                                    true)
                                (do (aset idxs dim (int 0))
                                    (recur (dec dim))))
                              false))
                        (recur idxs)
                        true)
                      false)
                    ))))))))

  ;; TODO: optimize on smaller arrays
  ;; TODO: replace stride multiplication with addition
  ;; (one can use explicit addition of stride instead of (inc i)
  ;; TODO: implement transposition of argument for faster access
  ;; TODO: be ready to normalize arguments if they are not in row-major
  ;; TODO: check bit.ly/16ECque for inspiration
  ;; For algorithms see [4]

  mp/PMatrixMultiply
   (matrix-multiply [a b]
     (if-not (instance? typename# b)
       ;; Coerce second argument to first one
       (mp/matrix-multiply a (mp/coerce-param a b))
       ;; Fast path, types are same
       (let [^typename# b b
             a-ndims ndims
             b-ndims (.ndims b)
             ^ints b-shape (.shape b)]
         (cond
          (== b-ndims 0) (mp/scale a b)
          (and (== a-ndims 1) (== b-ndims 2))
          (let [b-rows (aget b-shape (int 0))]
            (mp/reshape (mp/matrix-multiply (mp/reshape a [1 b-rows]) b)
                        [b-rows]))
          (and (== a-ndims 2) (== b-ndims 1))
          (let [a-cols (aget shape (int 1))]
            (mp/reshape (mp/matrix-multiply a (mp/reshape b [a-cols 1]))
                        [a-cols]))
          (and (== a-ndims 2) (== b-ndims 2))
          (let [^typename# c (empty-ndarray-zeroed#t
                              [(aget shape (int 0))
                               (aget (ints (.shape b)) (int 1))])]
            (expose-ndarrays [a b c]
              (let [a-rows (aget a-shape (int 0))
                    a-cols (aget a-shape (int 1))
                    b-rows (aget b-shape (int 0))
                    b-cols (aget b-shape (int 1))]
                (do (iae-when-not (== a-cols b-rows)
                      (str "dimension mismatch: "
                           [a-rows a-cols] "x" [b-rows b-cols]))
                    (c-for [i (int 0) (< i a-rows) (inc i)
                            k (int 0) (< k a-cols) (inc k)]
                      (let [t (aget-2d a-data a-strides a-offset i k)]
                        (c-for [j (int 0) (< j b-cols) (inc j)]
                          (aadd-2d c-data c-strides c-offset i j
                                   (* t (aget-2d b-data b-strides b-offset k j))))))
                    c))))))))
   (element-multiply [a b]
     (if-not (instance? typename# b)
       (if (number? b)
         (mp/scale a b)
         ;; Coerce second argument to first one
         (mp/element-multiply a (mp/coerce-param a b)))
       ;; Fast path, types are same
       (expose-ndarrays [a b]
         (if-not (java.util.Arrays/equals a-shape b-shape)
           (let [[a b] (mp/broadcast-compatible a b)]
                (mp/element-multiply a b))
           (let [c (mp/clone a)]
             (loop-over [b c]
               (aset c-data c-idx
                     (* (aget c-data c-idx)
                        (aget b-data b-idx))))
             c)))))

  ;; mp/PMatrixProducts

  mp/PAddProduct
    (add-product [m a b]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))
            ^typename# b (if (instance? typename# b) b
                             (mp/coerce-param m b))]
        (iae-when-not (and (java.util.Arrays/equals (ints (.shape m))
                                                    (ints (.shape a)))
                           (java.util.Arrays/equals (ints (.shape a))
                                                    (ints (.shape b))))
          "add-product operates on arrays of equal shape")
        (let [^typename# c (mp/clone m)]
          (expose-ndarrays [a b c]
            (let [a-rows (aget a-shape (int 0))
                  a-cols (aget a-shape (int 1))
                  b-rows (aget b-shape (int 0))
                  b-cols (aget b-shape (int 1))]
              (do (c-for [i (int 0) (< i a-rows) (inc i)
                          k (int 0) (< k a-cols) (inc k)]
                    (let [t (aget-2d a-data a-strides a-offset i k)]
                      (c-for [j (int 0) (< j b-cols) (inc j)]
                        (aadd-2d c-data c-strides c-offset i j
                                 (* t (aget-2d b-data b-strides b-offset k j))))))
                  c))))))

  mp/PAddProductMutable
    (add-product! [m a b]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))
            ^typename# b (if (instance? typename# b) b
                             (mp/coerce-param m b))]
        (iae-when-not (and (java.util.Arrays/equals (ints (.shape m))
                                                    (ints (.shape a)))
                           (java.util.Arrays/equals (ints (.shape a))
                                                    (ints (.shape b))))
          "add-product! operates on arrays of equal shape")
        (expose-ndarrays [a b m]
          (let [a-rows (aget a-shape (int 0))
                a-cols (aget a-shape (int 1))
                b-rows (aget b-shape (int 0))
                b-cols (aget b-shape (int 1))]
            (do (c-for [i (int 0) (< i a-rows) (inc i)
                        k (int 0) (< k a-cols) (inc k)]
                  (let [t (aget-2d a-data a-strides a-offset i k)]
                    (c-for [j (int 0) (< j b-cols) (inc j)]
                      (aadd-2d m-data m-strides m-offset i j
                               (* t (aget-2d b-data b-strides b-offset k j))))))
                m)))))

  mp/PAddScaledProduct
    (add-scaled-product [m a b factor]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))
            ^typename# b (if (instance? typename# b) b
                             (mp/coerce-param m b))]
        (iae-when-not (and (java.util.Arrays/equals (ints (.shape m))
                                                    (ints (.shape a)))
                           (java.util.Arrays/equals (ints (.shape a))
                                                    (ints (.shape b))))
          "add-scaled-product operates on arrays of equal shape")
        (let [^typename# c (mp/clone m)]
          (expose-ndarrays [a b c]
            (let [a-rows (aget a-shape (int 0))
                  a-cols (aget a-shape (int 1))
                  b-rows (aget b-shape (int 0))
                  b-cols (aget b-shape (int 1))
                  factor (type-cast# factor)]
              (do (c-for [i (int 0) (< i a-rows) (inc i)
                          k (int 0) (< k a-cols) (inc k)]
                    (let [t (* factor
                               (aget-2d a-data a-strides a-offset i k))]
                      (c-for [j (int 0) (< j b-cols) (inc j)]
                        (aadd-2d c-data c-strides (int 0) i j
                                 (* t (aget-2d b-data b-strides b-offset k j))))))
                  c))))))

  mp/PAddScaledProductMutable
    (add-scaled-product! [m a b factor]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))
            ^typename# b (if (instance? typename# b) b
                             (mp/coerce-param m b))]
        (iae-when-not (and (java.util.Arrays/equals (ints (.shape m))
                                                    (ints (.shape a)))
                           (java.util.Arrays/equals (ints (.shape a))
                                                    (ints (.shape b))))
          "add-scaled-product! operates on arrays of equal shape")
        (expose-ndarrays [a b m]
          (let [a-rows (aget a-shape (int 0))
                a-cols (aget a-shape (int 1))
                b-rows (aget b-shape (int 0))
                b-cols (aget b-shape (int 1))
                factor (type-cast# factor)]
            (do (c-for [i (int 0) (< i a-rows) (inc i)
                        k (int 0) (< k a-cols) (inc k)]
                  (let [t (* factor
                             (aget-2d a-data a-strides a-offset i k))]
                    (c-for [j (int 0) (< j b-cols) (inc j)]
                      (aadd-2d m-data m-strides (int 0) i j
                               (* t (aget-2d b-data b-strides b-offset k j))))))
                m)))))

  mp/PAddScaled
    (add-scaled [m a factor]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (iae-when-not (java.util.Arrays/equals (ints (.shape m))
                                               (ints (.shape a)))
          "add-scaled operates on arrays of equal shape")
        (let [^typename# b (mp/clone m)]
          (loop-over [a b]
            (aset b-data b-idx (* (type-cast# factor)
                                  (aget a-data a-idx))))
          b)))

  mp/PAddScaledMutable
    (add-scaled! [m a factor]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (iae-when-not (java.util.Arrays/equals (ints (.shape m))
                                               (ints (.shape a)))
          "add-scaled operates on arrays of equal shape")
        (loop-over [m a]
          (aset m-data m-idx (* (type-cast# factor)
                                (aget a-data a-idx))))
        m))

  mp/PMatrixDivide
    (element-divide [m factor]
       (let [a (mp/clone m)]
         (loop-over [a]
           (aset a-data a-idx (type-cast#
                               (/ (aget a-data a-idx)
                                  (type-cast# factor)))))
         a))
    (element-divide [m]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (type-cast#
                              (/ (type-cast# 1)
                                 (aget a-data a-idx)))))
       a))

  ;; PMatrixMultiplyMutable

  ;; PVectorTransform

  mp/PMatrixScaling
    (scale [m factor]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (type-cast#
                              (* (aget a-data a-idx)
                                 (type-cast# factor)))))
        a))
    (pre-scale [m factor]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (type-cast#
                              (* (type-cast# factor)
                                 (aget a-data a-idx)))))
        a))

  ;; TODO: waits for loop-over-nd
  mp/PMatrixMutableScaling
    (scale! [m factor]
      (loop-over [m]
        (aset m-data m-idx (type-cast#
                            (* (aget m-data m-idx)
                               (type-cast# factor)))))
      m)
    (pre-scale! [m factor]
      (loop-over [m]
        (aset m-data m-idx (type-cast#
                            (* (type-cast# factor)
                               (aget m-data m-idx)))))
      m)

  mp/PMatrixAdd
    (matrix-add [m a]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (if-not (java.util.Arrays/equals (ints (.shape m)) (ints (.shape a)))
          (let [[m a] (mp/broadcast-compatible m a)]
            (mp/matrix-add m a))
          (let [b (mp/clone m)]
            (loop-over [a b]
              (aset b-data b-idx (+ (aget b-data b-idx)
                                    (aget a-data a-idx))))
            b))))
    (matrix-sub [m a]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (if-not (java.util.Arrays/equals (ints (.shape m)) (ints (.shape a)))
          (let [[m a] (mp/broadcast-compatible m a)]
            (mp/matrix-add m a))
          (let [b (mp/clone m)]
            (loop-over [a b]
              (aset b-data b-idx (- (aget b-data b-idx)
                                    (aget a-data a-idx))))
            b))))

  mp/PMatrixAddMutable
    (matrix-add! [m a]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (if-not (java.util.Arrays/equals (ints (.shape m)) (ints (.shape a)))
          (let [[m a] (mp/broadcast-compatible m a)]
            (mp/matrix-add m a))
          (do
            (loop-over [a m]
              (aset m-data m-idx (+ (aget m-data m-idx)
                                    (aget a-data a-idx))))
            m))))
    (matrix-sub! [m a]
      (let [^typename# a (if (instance? typename# a) a
                             (mp/coerce-param m a))]
        (if-not (java.util.Arrays/equals (ints (.shape m)) (ints (.shape a)))
          (let [[m a] (mp/broadcast-compatible m a)]
            (mp/matrix-add m a))
          (do
            (loop-over [a m]
              (aset m-data m-idx (- (aget m-data m-idx)
                                    (aget a-data a-idx))))
            m))))

  ;; mp/PSubMatrix
  ;; mp/PComputeMatrix

  mp/PTranspose
    (transpose [m]
      (let [new-shape (areverse shape)
            new-strides (areverse strides)]
        (reshape-restride#t m ndims new-shape new-strides offset)))

  ;; mp/PNumerical ;; similar to matrix-equals, needs longjump
  ;; mp/PVectorOps ;; needs fold-over
  ;; mp/PVectorCross
  ;; mp/PVectorDistance
  ;; mp/PVectorView ;; needs "packed" flag to be efficient
  ;; mp/PVectorisable ;; similar to PVectorView
  ;; mp/PMutableVectorOps ;; needs fold-over

  mp/PNegation
    (negate [m]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (* -1 (aget a-data a-idx))))
        a))

  ;; mp/PMatrixRank

  mp/PSummable
    (element-sum [m]
      (fold-over [m] 0
                 (+ loop-acc (aget m-data m-idx))))

  mp/PExponent
    (element-pow [m exp]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (type-cast# (Math/pow (aget a-data a-idx)
                                                   exp))))
        a))

  mp/PSquare
    (square [m]
      (let [a (mp/clone m)]
        (loop-over [a]
          (aset a-data a-idx (* (aget a-data a-idx)
                                (aget a-data a-idx))))
        a))

  ;; mp/PRowOperations ;; use mutable views

  ;; PMathsFunctions/PMathsFunctionsMutable are evaled below

  mp/PElementCount
    (element-count [m]
      (areduce shape i s (int 1)
               (* s (aget shape i))))

  ;; PFunctionalOperators
  ;; PMatrixPredicates ;; needs long-jump
  ;; PGenericValues
  ;; PGenericOperations
  )

(eval
  `(magic/extend-types
    [:long :float :double :object]
    mp/PMathsFunctions
    ~@(map
       (fn [[name func]]
         `(~name [~'m]
             (let [~'a (mp/clone ~'m)]
               (loop-over [~'a]
                 (aset ~'a-data ~'a-idx
                       (~'type-cast# (~func (double (aget ~'a-data ~'a-idx))))))
               ~'a)))
       mops/maths-ops)

    mp/PMathsFunctionsMutable
    ~@(map
       (fn [[name func]]
         `(~(symbol (str name "!")) [~'m]
             (loop-over [~'m]
               (aset ~'m-data ~'m-idx
                     (~'type-cast# (~func (double (aget ~'m-data ~'m-idx))))))
             ~'m))
       mops/maths-ops)))

(magic/extend-types
  [:double]
  mp/PMatrixOps
    (trace [m]
      (iae-when-not (== ndims 2)
        "trace operates only on matrices")
      (iae-when-not (== (aget shape 0) (aget shape 1))
        "trace operates only on square matrices")
      (mp/element-sum (mp/main-diagonal m)))
    (determinant [m]
      (iae-when-not (== ndims 2)
        "determinant operates only on matrices")
      (iae-when-not (== (aget shape 0) (aget shape 1))
        "determinant operates only on square matrices")
      (determinant#t m))
    (inverse [m]
      (iae-when-not (== ndims 2)
        "inverse operates only on matrices")
      (iae-when-not (== (aget shape 0) (aget shape 1))
        "inverse operates only on square matrices")
      (invert#t m)))

(magic/spit-code)
