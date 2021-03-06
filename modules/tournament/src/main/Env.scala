package lila.tournament

import akka.actor._
import akka.pattern.ask
import com.typesafe.config.Config

import lila.common.PimpedConfig._
import lila.hub.actorApi.map.Ask
import lila.socket.actorApi.GetVersion
import lila.socket.History
import makeTimeout.short

final class Env(
    config: Config,
    system: ActorSystem,
    db: lila.db.Env,
    flood: lila.security.Flood,
    hub: lila.hub.Env,
    roundMap: ActorRef,
    getUsername: String ⇒ Fu[Option[String]],
    isDev: Boolean,
    scheduler: lila.common.Scheduler) {

  private val settings = new {
    val CollectionTournament = config getString "collection.tournament"
    val CollectionRoom = config getString "collection.room"
    val MessageTtl = config duration "message.ttl"
    val UidTimeout = config duration "uid.timeout"
    val SocketTimeout = config duration "socket.timeout"
    val SocketName = config getString "socket.name"
    val OrganizerName = config getString "organizer.name"
    val ReminderName = config getString "reminder.name"
    val NetDomain = config getString "net.domain"
  }
  import settings._

  lazy val forms = new DataForm(isDev)

  lazy val api = new TournamentApi(
    joiner = joiner,
    router = hub.actor.router,
    renderer = hub.actor.renderer,
    socketHub = socketHub,
    site = hub.socket.site,
    lobby = hub.socket.lobby,
    roundMap = roundMap)

  lazy val socketHandler = new SocketHandler(
    hub = hub,
    socketHub = socketHub,
    messenger = messenger,
    flood = flood)

  private lazy val history = () ⇒ new History(ttl = MessageTtl)

  private val socketHub = system.actorOf(
    Props(new lila.socket.SocketHubActor.Default[Socket] {
      def mkActor(tournamentId: String) = new Socket(
        tournamentId = tournamentId,
        history = history(),
        messenger = messenger,
        uidTimeout = UidTimeout,
        socketTimeout = SocketTimeout,
        getUsername = getUsername)
    }), name = SocketName)

  private val organizer = system.actorOf(Props(new Organizer(
    api = api,
    reminder = system.actorOf(Props(new Reminder(
      renderer = hub.actor.renderer
    )), name = ReminderName),
    socketHub = socketHub
  )), name = OrganizerName)

  def version(tourId: String): Fu[Int] =
    socketHub ? Ask(tourId, GetVersion) mapTo manifest[Int]

  def cli = new lila.common.Cli {
    import tube.tournamentTube
    def process = {
      case "tournament" :: "typecheck" :: Nil ⇒ lila.db.Typecheck.apply[Tournament]
    }
  }

  private lazy val joiner = new GameJoiner(
    roundMap = roundMap,
    timelinePush = hub.actor.gameTimeline,
    system = system)

  {
    import scala.concurrent.duration._

    scheduler.message(5 seconds) {
      organizer -> actorApi.CreatedTournaments
    }

    scheduler.message(3 seconds) {
      organizer -> actorApi.StartedTournaments
    }
  }

  lazy val messenger = new Messenger(getUsername, NetDomain)

  private[tournament] lazy val tournamentColl = db(CollectionTournament)
  private[tournament] lazy val roomColl = db(CollectionRoom)
}

object Env {

  private def hub = lila.hub.Env.current

  lazy val current = "[boot] tournament" describes new Env(
    config = lila.common.PlayApp loadConfig "tournament",
    system = lila.common.PlayApp.system,
    db = lila.db.Env.current,
    flood = lila.security.Env.current.flood,
    hub = lila.hub.Env.current,
    roundMap = lila.round.Env.current.roundMap,
    getUsername = lila.user.Env.current.usernameOption,
    isDev = lila.common.PlayApp.isDev,
    scheduler = lila.common.PlayApp.scheduler)
}
