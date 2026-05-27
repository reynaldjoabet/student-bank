package sbank.db

import sbank.domain.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import cats.effect.{Concurrent, Resource}

trait TaxDocs[F[_]] {
  def insert(d: TaxDocument): F[TaxDocument]
  def listFor(userId: UserId): F[List[TaxDocument]]
  def find(id: TaxDocumentId): F[Option[TaxDocument]]
}

object TaxDocs {

  import Codecs.{taxDocument as docC, taxDocumentId as docIdC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): TaxDocs[F] =
    new TaxDocs[F] {

      def insert(d: TaxDocument): F[TaxDocument] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(d)))
      def listFor(userId: UserId): F[List[TaxDocument]] =
        pool.use(
          _.prepare(Q.listFor).flatMap(_.stream(userId, 32).compile.toList)
        )
      def find(id: TaxDocumentId): F[Option[TaxDocument]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
    }

  private object Q {
    val insert: Query[TaxDocument, TaxDocument] =
      sql"""INSERT INTO tax_documents (id, user_id, tax_year, document_type, blob_ref, issued_on, created_at)
            VALUES $docC
            RETURNING id, user_id, tax_year, document_type, blob_ref, issued_on, created_at"""
        .query(docC)

    val listFor: Query[UserId, TaxDocument] =
      sql"""SELECT id, user_id, tax_year, document_type, blob_ref, issued_on, created_at
            FROM tax_documents WHERE user_id = $userIdC ORDER BY tax_year DESC, issued_on DESC"""
        .query(docC)

    val byId: Query[TaxDocumentId, TaxDocument] =
      sql"""SELECT id, user_id, tax_year, document_type, blob_ref, issued_on, created_at
            FROM tax_documents WHERE id = $docIdC""".query(docC)

    val _ = int4
  }
}
