package sbank.service

import sbank.domain.*
import sbank.db.TaxDocs
import sbank.external.BlobStorage

import cats.effect.*
import cats.syntax.all.*
import java.time.{Instant, LocalDate}
import java.util.UUID

/** Tax-document management. Documents themselves live in blob storage; the row in `tax_documents` just carries a
  * [[BlobRef]]. The mobile app downloads via a short-lived signed URL — the actual PDF is then cached on-device for the
  * "available offline" requirement.
  */
trait TaxDocService[F[_]] {
  def publish(
      userId: UserId,
      taxYear: Int,
      documentType: String,
      bytes: Array[Byte],
      issuedOn: LocalDate
  ): F[TaxDocument]
  def list(userId: UserId): F[List[TaxDocument]]
  def signedUrlFor(id: TaxDocumentId, ttlSeconds: Int): F[Option[String]]
}

object TaxDocService {

  def make[F[_]: Async](
      taxDocs: TaxDocs[F],
      blob: BlobStorage[F]
  ): TaxDocService[F] =
    new TaxDocService[F] {

      def publish(
          userId: UserId,
          taxYear: Int,
          docType: String,
          bytes: Array[Byte],
          issuedOn: LocalDate
      ): F[TaxDocument] =
        for {
          now <- Async[F].delay(Instant.now())
          ref = BlobRef.applyUnsafe(
            s"users/${userId.value}/${taxYear}_${docType}_${UUID.randomUUID().toString.take(8)}.pdf"
          )
          _ <- blob.put(ref, bytes, "application/pdf")
          doc = TaxDocument(
            id = TaxDocumentId.assume(UUID.randomUUID()),
            userId = userId,
            taxYear = taxYear,
            documentType = docType,
            blobRef = ref,
            issuedOn = issuedOn,
            createdAt = now
          )
          saved <- taxDocs.insert(doc)
        } yield saved

      def list(userId: UserId): F[List[TaxDocument]] =
        taxDocs.listFor(userId)

      def signedUrlFor(id: TaxDocumentId, ttlSeconds: Int): F[Option[String]] =
        taxDocs
          .find(id)
          .flatMap(_.traverse(d => blob.signedUrl(d.blobRef, ttlSeconds)))
    }
}
