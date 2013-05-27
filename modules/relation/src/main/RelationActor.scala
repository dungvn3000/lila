package lila.relation

import akka.actor.Actor
import akka.pattern.{ ask, pipe }

import actorApi._
import lila.hub.actorApi.relation._
import lila.hub.actorApi.{ SendTo, SendTos }
import lila.hub.ActorLazyRef
import makeTimeout.short

private[relation] final class RelationActor(
    socketHub: ActorLazyRef,
    getOnlineUserIds: () ⇒ Set[String],
    getUsername: String ⇒ Fu[String],
    api: RelationApi) extends Actor {

  private type ID = String
  private type Username = String
  private type User = (ID, Username)

  def receive = {

    // triggers following reloading for this user id
    case ReloadFollowing(userId) ⇒ api.following(userId) flatMap { ids ⇒
      ((ids intersect onlineIds).toList map getUsername).sequenceFu
    } map { SendTo(userId, "following_onlines", _) } pipeTo socketHub.ref

    case NotifyMovement ⇒ {
      val prevIds = onlineIds
      val curIds = getOnlineUserIds()
      val leaveIds = (prevIds diff curIds).toList
      val enterIds = (curIds diff prevIds).toList

      val leaves: List[User] = leaveIds map { id ⇒
        onlines get id map { id -> _ }
      } flatten

      val enters: List[User] = {
        (enterIds map { id ⇒ getUsername(id) map { id -> _ } }).sequenceFu
      }.await

      onlines = onlines -- leaveIds ++ enters

      notifyFollowers(enters, "following_enters")
      notifyFollowers(leaves, "following_leaves")
    }
  }

  private var onlines = Map[ID, Username]()
  private def onlineIds: Set[ID] = onlines.keySet

  private def notifyFollowers(users: List[User], message: String) {
    users foreach {
      case (id, name) ⇒ api.followers(id) foreach { ids ⇒
        val notify = ids filter onlines.contains
        if (notify.nonEmpty) socketHub ! SendTos(notify.toSet, message, name)
      }
    }
  }
}
