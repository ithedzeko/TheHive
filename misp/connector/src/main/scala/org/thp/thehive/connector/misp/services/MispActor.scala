package org.thp.thehive.connector.misp.services

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import play.api.Logger

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable}
import akka.cluster.Cluster
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}
import javax.inject.{Inject, Named, Provider}
import org.thp.scalligraph.auth.UserSrv

object MispActor {
  case object Synchro
  case class EndOfSynchro(status: Try[Unit])
}

class MispActor @Inject()(
    connector: Connector,
    mispImportSrv: MispImportSrv,
    userSrv: UserSrv
) extends Actor {
  import MispActor._
  import context.dispatcher

  lazy val logger = Logger(getClass)
  logger.info(s"[$self] Initialising actor MISP")

  override def preStart(): Unit = {
    super.preStart()
    logger.info(s"[$self] Starting actor MISP")
    context.become(waiting(context.system.scheduler.scheduleOnce(connector.syncInitialDelay, self, Synchro)))
  }

  override def receive: Receive = {
    case other => logger.warn(s"Unknown message $other (${other.getClass})")
  }

  def running: Receive = {
    case Synchro => logger.info("MISP synchronisation is already in progress")
    case EndOfSynchro(Success(_)) =>
      logger.info(s"MISP synchronisation is complete")
      context.become(waiting(context.system.scheduler.scheduleOnce(connector.syncInterval, self, Synchro)))
    case EndOfSynchro(Failure(error)) =>
      logger.error(s"MISP synchronisation fails", error)
      context.become(waiting(context.system.scheduler.scheduleOnce(connector.syncInterval, self, Synchro)))
    case other => logger.warn(s"Unknown message $other (${other.getClass})")
  }

  def waiting(scheduledSynchronisation: Cancellable): Receive = {
    case Synchro =>
      scheduledSynchronisation.cancel()
      context.become(running)
      logger.info(s"Synchronising MISP events for ${connector.clients.map(_.name).mkString(",")}")
      Future
        .traverse(connector.clients)(mispImportSrv.syncMispEvents(_)(userSrv.getSystemAuthContext))
        .map(_ => ())
        .onComplete(status => self ! EndOfSynchro(status))
    case other => logger.warn(s"Unknown message $other (${other.getClass})")
  }
}

class MispActorProvider @Inject()(system: ActorSystem, @Named("misp-actor-singleton") mispActorSingleton: ActorRef) extends Provider[ActorRef] {
  lazy val logger = Logger(getClass)
  override def get(): ActorRef = {
    val cluster = Cluster(system)
    logger.info("Initialising cluster")
    cluster.join(mispActorSingleton.path.address)
    logger.info(s"cluster members are ${cluster.state.members}")
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = mispActorSingleton.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(system)
      )
    )
  }
}
