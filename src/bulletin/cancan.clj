(ns bulletin.cancan
  (:require
   ;; db namespace is only for testing in this buffer repl.
   ;; should not actually be used in this namespace.
   [bulletin.db :as db]))


;;;;
;;;; This namespace will be the core authorization abstraction as I figure out
;;;; how I want to do it. As I make breakthroughs, I will try to clean it up.
;;;;
;;;; Once I'm happy enough with my abstraction/implementation, I'll add tests
;;;;

;; Only the admin can create categories and forums

(defn global-admin?
  "A user is a global admin if they have an admin role with a null community_id."
  [user]
  (-> (for [role (:roles user)
            :when (and (= "admin" (:title role))
                       (nil? (:community_id role)))]
        role)
      (not-empty)))

(defn community-admin? [community-roles]
  (not-empty (filter #(= "admin" (:title %)) community-roles)))

(defn community-banned?
  "Returns boolean. Whether or not user is banned in this community."
  [community-roles]
  (not-empty (filter #(= "banned" (:title %)) community-roles)))

;; User -> Bool
(defn globally-banned?
  "A user is globally banned if they have a banned role without a community_id"
  [user]
  (not-empty (filter #(and (= "banned" (:title %))
                           (nil? (:community_id %)))
                     (:roles user))))

(defn banned?
  "Whether user is banned globally or at the community-level"
 [user community-roles]
 (or (community-banned? community-roles)
     (globally-banned? user)))

(defn smod? [community-roles]
  (not-empty (filter #(= "smod" (:title %)) community-roles)))

(defn mod? [community-roles]
  (not-empty (filter #(= "mod" (:title %)) community-roles)))

(defn member?
  "A user is a member if they aren't any other role"
  [community-roles]
  (empty? community-roles))

(defn get-community-roles
  "Returns roles only for the given community"
  [user community]
  (for [role (:roles user)
        :when (= (:community_id role) (:id community))]
    role))
(def get-comm-roles get-community-roles)

;; TODO: Tests
(def users {:gadmin (db/find-user 1)
            :cadmin1 (db/find-user 2)
            :cadmin2 (db/find-user 3)
            :smod1 (db/find-user 4)
            :smod2 (db/find-user 5)
            :mod1-f1 (db/find-user 6)
            :mod1-f2 (db/find-user 7)
            :member (db/find-user 8)
            })

(defn guest?
  "User is not logged in"
  [user]
  (nil? (:id user)))

;; (let [com1 (db/find-community 1)]
;;   (assert (member? (get-comm-roles (:member users) com1)))
;;   (assert (false? (member? (get-community-roles (:cadmin1 users) com1))))
;;   )


;; target must at least have :communtiy key
;; target: {:community _, :forum _, maybe :topic, maybe :post _}
;; action: a keyword

(defn can? [user action target]
  (println "can?" action user target)
  (if (global-admin? user)
    true
    ;; Return early if user is global admin. They can do anything.
    (let [community (:community target)
          community-roles (get-community-roles user community)]
      ;; Community admin can do anything if this is their community
      ;; Except promote/demote other community staff. They can ban members though.
      (case action
        ;;;
        ;;; Create
        ;;;
        :create-topic (and (not (guest? user))
                           (not (banned? user community-roles)))
        :create-category (community-admin? community-roles)
        :create-forum (community-admin? community-roles)
        ;; TODO: Check if topic is closed/hidden
        :create-post (and (not (:is_closed (:topic target)))
                          (not (guest? user))
                          (not (community-banned? community-roles))
                          (not (globally-banned? user)))
        ;; Anyone can create a community unless user is globally banned
        :create-community (not (globally-banned? user))
        ;;;
        ;;; Read
        ;;;
        :read-forum true
        ;; TODO: Check if post is deleted(hidden)
        :read-post true
        ;; TODO: Check if topic is hidden
        :read-topic true
        ;;:
        ;;; Update
        ;;;
        ;; target includes :post
        :update-post (or
                      ;; Members can update a post if it's theirs
                      (and (= (:user_id (:post target)) (:id user))))
        ;;;
        ;;; Destroy
        ;;;
        :delete-category false
        :delete-forum false
        :delete-post false
        :delete-topic false
        :delete-user false
        ;;; Default to false since this is a whitelist
        (do (throw (ex-info "Unhandled can? action" {:user user
                                                     :action action
                                                     :target target}))
            false)))))

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
