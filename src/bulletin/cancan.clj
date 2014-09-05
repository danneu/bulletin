(ns bulletin.cancan
  (:require
   ;; db namespace is only for testing in this buffer repl.
   ;; should not actually be used in this namespace.
   [bulletin.db :as db]))


;;
;; This namespace will be the core authorization abstraction as I figure out
;; how I want to do it. As I make breakthroughs, I will try to clean it up.
;;

;; Only the admin can create categories and forums

(defn global-admin?
  "A user is a global admin if they have an admin role with a null community_id."
  [user]
  (-> (for [role (:roles user)
            :when (and (= "admin" (:title role))
                       (nil? (:community_id role)))]
        role)
      (not-empty)))

(defn community-admin? [user community]
  (-> (for [role (:roles user)
            :when (and (= "admin" (:title role))
                       (= (:id community) (:community_id role)))]
        role)
      (not-empty)))

(defn get-community-roles
  "Returns roles only for the given community"
  [user community]
  (for [role (:roles user)
        :when (= (:community_id role) (:id community))]
    role))

;; target: {:community _, :forum _}
;; action: a keyword
(defn can? [user action target]
  (if (global-admin? user)
    true
    ;; Return early if user is global admin. They can do anything.
    (let [community (:community target)
          community-roles (get-community-roles user community)]
      ;; Community admin can do anything if this is their community
      ;; Except promote/demote other community staff. They can ban members though.
      (if (community-admin? user community)
        true
        false
        )
      ))
  )

(def cannot? (complement can?))

;; (let [community1 {:id 1}
;;       community2 {:id 2}
;;       global-admin {:roles [{:title "admin" :community_id nil}]}
;;       community1-admin {:roles [{:title "admin" :community_id 1}]}
;;       supermod {:roles [{:title "supermod" :community_id 1}]}]
;;   (assert (can? global-admin :anything {}))
;;   (assert (can? community1-admin :anything {:community community1}))
;;   (assert (cannot? supermod :create-category {:community community1}))

;;   )
