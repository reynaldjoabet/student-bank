package sbank.db

import sbank.domain.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import cats.effect.{Concurrent, Resource}
import cats.syntax.all.toFlatMapOps
import cats.syntax.all.toFunctorOps
//import cats.syntax.functor.toFunctorOps
trait Notifications[F[_]] {
  def upsertPref(p: NotificationPref): F[Unit]
  def prefsFor(userId: UserId): F[List[NotificationPref]]
  def insertEvent(e: NotificationEvent): F[NotificationEvent]
  def listFor(userId: UserId, limit: Int): F[List[NotificationEvent]]
  def markRead(id: NotificationId, at: java.time.Instant): F[Unit]
}

object Notifications {

  import Codecs.{
    notificationPref as prefC,
    notificationEvent as eventC,
    notificationId as eventIdC,
    userId as userIdC,
    notificationKind,
    notificationChannel
  }

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Notifications[F] =
    new Notifications[F] {

      def upsertPref(p: NotificationPref): F[Unit] =
        pool.use(_.prepare(Q.upsertPref).flatMap(_.execute(p))).void

      def prefsFor(userId: UserId): F[List[NotificationPref]] =
        pool.use(
          _.prepare(Q.prefsFor).flatMap(_.stream(userId, 32).compile.toList)
        )

      def insertEvent(e: NotificationEvent): F[NotificationEvent] =
        pool.use(_.prepare(Q.insertEvent).flatMap(_.unique(e)))

      def listFor(userId: UserId, limit: Int): F[List[NotificationEvent]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(
            _.stream((userId, limit), 64).compile.toList
          )
        )

      def markRead(id: NotificationId, at: java.time.Instant): F[Unit] =
        pool
          .use(
            _.prepare(Q.markRead)
              .flatMap(_.execute((at.atOffset(java.time.ZoneOffset.UTC), id)))
          )
          .void
    }

  private object Q {
    val upsertPref: Command[NotificationPref] =
      sql"""INSERT INTO notification_prefs (user_id, kind, channel, enabled)
            VALUES $prefC
            ON CONFLICT (user_id, kind, channel) DO UPDATE SET enabled = EXCLUDED.enabled""".command

    val prefsFor: Query[UserId, NotificationPref] =
      sql"""SELECT user_id, kind, channel, enabled FROM notification_prefs
            WHERE user_id = $userIdC""".query(prefC)

    val insertEvent: Query[NotificationEvent, NotificationEvent] =
      sql"""INSERT INTO notification_events (id, user_id, kind, title, body, channel,
                                              delivered_at, read_at, created_at)
            VALUES $eventC
            RETURNING id, user_id, kind, title, body, channel, delivered_at, read_at, created_at"""
        .query(eventC)

    val listFor: Query[(UserId, Int), NotificationEvent] =
      sql"""SELECT id, user_id, kind, title, body, channel, delivered_at, read_at, created_at
            FROM notification_events WHERE user_id = $userIdC
            ORDER BY created_at DESC LIMIT $int4""".query(eventC)

    val markRead: Command[(java.time.OffsetDateTime, NotificationId)] =
      sql"UPDATE notification_events SET read_at = $timestamptz WHERE id = $eventIdC".command

    val _ = (notificationKind, notificationChannel) // silence unused-import
  }
}
