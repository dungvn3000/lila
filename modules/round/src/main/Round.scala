package lila.round

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.{ ask, pipe }

import actorApi._, round._
import lila.game.{ Game, GameRepo, PgnRepo, Pov, PovRef, PlayerRef, Event, Progress }
import lila.hub.actorApi.map._
import lila.hub.SequentialActor
import lila.i18n.I18nKey.{ Select ⇒ SelectI18nKey }
import makeTimeout.large

private[round] final class Round(
    gameId: String,
    messenger: Messenger,
    takebacker: Takebacker,
    finisher: Finisher,
    rematcher: Rematcher,
    player: Player,
    drawer: Drawer,
    socketHub: ActorRef,
    moretimeDuration: Duration) extends SequentialActor {

  context setReceiveTimeout 30.seconds

  def process = {

    case ReceiveTimeout ⇒ fuccess {
      self ! SequentialActor.Terminate
    }

    case p: HumanPlay ⇒ handle(p.playerId) { pov ⇒
      pov.game.outoftimePlayer.fold(player.human(p)(pov))(outOfTime(pov.game))
    }

    case AiPlay ⇒ publish(GameRepo game gameId) { game ⇒
      player ai game map (_.events)
    }

    case Abort(playerId) ⇒ handle(playerId) { pov ⇒
      pov.game.abortable ?? finisher(pov.game, _.Aborted)
    }

    case AbortForce ⇒ handle { game ⇒
      game.playable ?? finisher(game, _.Aborted)
    }

    case Resign(playerId) ⇒ handle(playerId) { pov ⇒
      pov.game.resignable ?? finisher(pov.game, _.Resign, Some(!pov.color))
    }

    case ResignColor(color) ⇒ handle(color) { pov ⇒
      pov.game.resignable ?? finisher(pov.game, _.Resign, Some(!pov.color))
    }

    case ResignForce(playerId) ⇒ handle(playerId) { pov ⇒
      (pov.game.resignable && !pov.game.hasAi) ?? {
        socketHub ? Ask(pov.gameId, IsGone(!pov.color)) flatMap {
          case true ⇒ finisher(pov.game, _.Timeout, Some(pov.color))
          case _    ⇒ fufail("[round] cannot force resign of " + pov)
        }
      }
    }

    case DrawForce(playerId) ⇒ handle(playerId) { pov ⇒
      (pov.game.drawable && !pov.game.hasAi) ?? {
        socketHub ? Ask(pov.gameId, IsGone(!pov.color)) flatMap {
          case true ⇒ finisher(pov.game, _.Timeout, None)
          case _    ⇒ fufail("[round] cannot force draw of " + pov)
        }
      }
    }

    case Outoftime ⇒ handle { game ⇒
      game.outoftimePlayer ?? outOfTime(game)
    }

    // exceptionally we don't block nor publish events
    // if the game is abandoned, then nobody is around to see them
    // we can also terminate this actor
    case Abandon ⇒ fuccess {
      GameRepo game gameId foreach { gameOption ⇒
        gameOption filter (_.abandoned) foreach { game ⇒
          if (game.abortable) finisher(game, _.Aborted)
          else finisher(game, _.Resign, Some(!game.player.color))
          self ! PoisonPill
        }
      }
    }

    case DrawYes(playerRef)  ⇒ handle(playerRef)(drawer.yes)
    case DrawNo(playerRef)   ⇒ handle(playerRef)(drawer.no)
    case DrawClaim(playerId) ⇒ handle(playerId)(drawer.claim)
    case DrawForce           ⇒ handle(drawer force _)
    case Cheat(color) ⇒ handle(color) { pov ⇒
      finisher(pov.game, _.Cheat, Some(!pov.color))
    }

    case RematchYes(playerRef)  ⇒ handle(playerRef)(rematcher.yes)
    case RematchNo(playerRef)   ⇒ handle(playerRef)(rematcher.no)

    case TakebackYes(playerRef) ⇒ handle(playerRef)(takebacker.yes)
    case TakebackNo(playerRef)  ⇒ handle(playerRef)(takebacker.no)

    case Moretime(playerRef) ⇒ handle(playerRef) { pov ⇒
      pov.game.clock.filter(_ ⇒ pov.game.moretimeable) ?? { clock ⇒
        val newClock = clock.giveTime(!pov.color, moretimeDuration.toSeconds)
        val progress = pov.game withClock newClock
        messenger.systemMessage(progress.game, (_.untranslated(
          "%s + %d seconds".format(!pov.color, moretimeDuration.toSeconds)
        ))) flatMap { events ⇒
          val progress2 = progress ++ (Event.Clock(newClock) :: events)
          GameRepo save progress2 inject progress2.events
        }
      }
    }
  }

  private def outOfTime(game: Game)(p: lila.game.Player) =
    finisher(game, _.Outoftime, Some(!p.color) filter {
      chess.InsufficientMatingMaterial(game.toChess.board, _)
    })

  protected def handle(playerId: String)(op: Pov ⇒ Fu[Events]) =
    publish(GameRepo pov PlayerRef(gameId, playerId))(op)

  protected def handle(color: chess.Color)(op: Pov ⇒ Fu[Events]) =
    publish(GameRepo pov PovRef(gameId, color))(op)

  protected def handle[A](op: Game ⇒ Fu[Events]) =
    publish(GameRepo game gameId)(op)

  private def publish[A](context: Fu[Option[A]])(op: A ⇒ Fu[Events]) = {
    context flatten "round not found" flatMap op addEffect {
      events ⇒ if (events.nonEmpty) socketHub ! Tell(gameId, events)
    } addFailureEffect {
      err ⇒ logwarn("[round publish] " + err)
    }
  }.void
}
