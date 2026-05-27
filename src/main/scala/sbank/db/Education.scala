package sbank.db

import sbank.domain.*
import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

trait Education[F[_]] {
  def upsertVideo(v: EducationVideo): F[EducationVideo]
  def listVideos(limit: Int): F[List[EducationVideo]]
  def find(id: EducationVideoId): F[Option[EducationVideo]]
  def progress(
      userId: UserId,
      videoId: EducationVideoId
  ): F[Option[WatchProgress]]
  def saveProgress(w: WatchProgress): F[WatchProgress]
}

object Education {

  import Codecs.{educationVideo as videoC, educationVideoId as videoIdC, watchProgress as progressC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Education[F] =
    new Education[F] {

      def upsertVideo(v: EducationVideo): F[EducationVideo] =
        pool.use(_.prepare(Q.upsertVideo).flatMap(_.unique(v)))

      def listVideos(limit: Int): F[List[EducationVideo]] =
        pool.use(
          _.prepare(Q.listVideos).flatMap(_.stream(limit, 64).compile.toList)
        )

      def find(id: EducationVideoId): F[Option[EducationVideo]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))

      def progress(
          userId: UserId,
          videoId: EducationVideoId
      ): F[Option[WatchProgress]] =
        pool.use(_.prepare(Q.progress).flatMap(_.option((userId, videoId))))

      def saveProgress(w: WatchProgress): F[WatchProgress] =
        pool.use(_.prepare(Q.saveProgress).flatMap(_.unique(w)))
    }

  private object Q {
    val upsertVideo: Query[EducationVideo, EducationVideo] =
      sql"""INSERT INTO education_videos (id, youtube_id, title, description, duration_sec, points_reward, published_at)
            VALUES $videoC
            ON CONFLICT (youtube_id) DO UPDATE
              SET title = EXCLUDED.title, description = EXCLUDED.description,
                  duration_sec = EXCLUDED.duration_sec, points_reward = EXCLUDED.points_reward
            RETURNING id, youtube_id, title, description, duration_sec, points_reward, published_at"""
        .query(videoC)

    val listVideos: Query[Int, EducationVideo] =
      sql"""SELECT id, youtube_id, title, description, duration_sec, points_reward, published_at
            FROM education_videos ORDER BY published_at DESC LIMIT $int4"""
        .query(videoC)

    val byId: Query[EducationVideoId, EducationVideo] =
      sql"""SELECT id, youtube_id, title, description, duration_sec, points_reward, published_at
            FROM education_videos WHERE id = $videoIdC""".query(videoC)

    val progress: Query[(UserId, EducationVideoId), WatchProgress] =
      sql"""SELECT id, user_id, video_id, seconds_watched, completed, rewarded_at
            FROM watch_progress WHERE user_id = $userIdC AND video_id = $videoIdC"""
        .query(progressC)

    val saveProgress: Query[WatchProgress, WatchProgress] =
      sql"""INSERT INTO watch_progress (id, user_id, video_id, seconds_watched, completed, rewarded_at)
            VALUES $progressC
            ON CONFLICT (user_id, video_id) DO UPDATE
              SET seconds_watched = EXCLUDED.seconds_watched,
                  completed       = EXCLUDED.completed,
                  rewarded_at     = EXCLUDED.rewarded_at
            RETURNING id, user_id, video_id, seconds_watched, completed, rewarded_at"""
        .query(progressC)
  }
}
