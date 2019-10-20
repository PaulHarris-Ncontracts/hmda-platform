package hmda.api.http.filing.submissions

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.{ ByteString, Timeout }
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{ cors, corsRejectionHandler }
import hmda.api.http.directives.HmdaTimeDirectives
import hmda.messages.submission.SubmissionProcessingCommands.{ GetHmdaValidationErrorState, GetVerificationStatus }
import hmda.model.filing.submission.{ SubmissionId, SubmissionStatus, VerificationStatus }
import hmda.model.processing.state.{ EditSummary, HmdaValidationErrorState }
import hmda.persistence.submission.{ EditDetailsPersistence, HmdaValidationError }
import hmda.util.http.FilingResponseUtils._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import hmda.api.http.model.filing.submissions._
import hmda.auth.OAuth2Authorization
import hmda.messages.submission.EditDetailsCommands.GetEditRowCount
import hmda.messages.submission.EditDetailsEvents._
import hmda.messages.submission.SubmissionProcessingEvents.HmdaRowValidatedError
import hmda.model.filing.EditDescriptionLookup
import hmda.query.HmdaQuery._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.matching.Regex
import scala.util.{ Failure, Success }

trait EditsHttpApi extends HmdaTimeDirectives {

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  val log: LoggingAdapter
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout
  val sharding: ClusterSharding

  //institutions/<lei>/filings/<period>/submissions/<submissionId>/edits
  def editsSummaryPath(oAuth2Authorization: OAuth2Authorization): Route =
    path("institutions" / Segment / "filings" / Segment / "submissions" / IntNumber / "edits") { (lei, period, seqNr) =>
      oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
        timedGet { uri =>
          val submissionId = SubmissionId(lei, period, seqNr)
          val hmdaValidationError = sharding
            .entityRefFor(HmdaValidationError.typeKey, s"${HmdaValidationError.name}-$submissionId")

          val fEdits: Future[HmdaValidationErrorState] = hmdaValidationError ? (ref => GetHmdaValidationErrorState(submissionId, ref))

          val fVerification: Future[VerificationStatus] = hmdaValidationError ? (ref => GetVerificationStatus(ref))

          val fEditsAndVer = for {
            edits <- fEdits
            ver   <- fVerification
          } yield (edits, ver)

          onComplete(fEditsAndVer) {
            case Success((edits, ver)) =>
              println("This is the size of quality: " + edits.quality.size)
              println("This is the size of macro: " + edits.`macro`.size)
              val syntactical =
                SyntacticalEditSummaryResponse(edits.syntactical.map { editSummary =>
                  toEditSummaryResponse(editSummary, period)
                }.toSeq)
              val validity = ValidityEditSummaryResponse(edits.validity.map { editSummary =>
                toEditSummaryResponse(editSummary, period)
              }.toSeq)
              val quality = QualityEditSummaryResponse(edits.quality.map { editSummary =>
                toEditSummaryResponse(editSummary, period)
              }.toSeq, edits.qualityVerified)
              val `macro` = MacroEditSummaryResponse(edits.`macro`.map { editSummary =>
                toEditSummaryResponse(editSummary, period)
              }.toSeq, edits.macroVerified)
              val editsSummaryResponse =
                EditsSummaryResponse(
                  syntactical,
                  validity,
                  quality,
                  `macro`,
                  SubmissionStatusResponse(
                    submissionStatus = SubmissionStatus.valueOf(edits.statusCode),
                    verification = ver
                  )
                )
              complete(editsSummaryResponse)
            case Failure(e) =>
              failedResponse(StatusCodes.InternalServerError, uri, e)
          }
        }
      }
    }

  //institutions/<lei>/filings/<period>/submissions/<submissionId>/edits/csv

  def editsSummaryCsvPath(oAuth2Authorization: OAuth2Authorization): Route =
    path("institutions" / Segment / "filings" / Segment / "submissions" / IntNumber / "edits" / "csv") { (lei, period, seqNr) =>
      oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
        val submissionId = SubmissionId(lei, period, seqNr)
        val csv = csvHeaderSource
          .concat(validationErrorEventStream(submissionId))
          .map(ByteString(_))
        complete(HttpEntity.Chunked.fromData(ContentTypes.`text/csv(UTF-8)`, csv))
      }
    }

  //institutions/<lei>/filings/<period>/submissions/<submissionId>/edits/<edit>
  def editDetailsPath(oAuth2Authorization: OAuth2Authorization): Route = {
    val editNameRegex: Regex = new Regex("""[SVQ]\d\d\d(?:-\d)?""")
    path("institutions" / Segment / "filings" / Segment / "submissions" / IntNumber / "edits" / editNameRegex) {
      (lei, period, seqNr, editName) =>
        oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
          timedGet { uri =>
            parameters('page.as[Int] ? 1) { page =>
              val submissionId = SubmissionId(lei, period, seqNr)
              val persistenceId =
                s"${EditDetailsPersistence.name}-$submissionId"
              val editDetailsPersistence = sharding
                .entityRefFor(EditDetailsPersistence.typeKey, s"${EditDetailsPersistence.name}-$submissionId")

              val fEditRowCount: Future[EditDetailsRowCounted] = editDetailsPersistence ? (ref => GetEditRowCount(editName, ref))

              val fDetails: Future[EditDetailsSummary] = for {
                editRowCount <- fEditRowCount
                s            = EditDetailsSummary(editName, Nil, uri.path.toString(), page, editRowCount.count)
                summary      <- editDetails(persistenceId, s)
              } yield summary

              onComplete(fDetails) {
                case Success(summary) =>
                  complete(summary)
                case Failure(e) =>
                  failedResponse(StatusCodes.InternalServerError, uri, e)
              }
            }
          }
        }

    }
  }

  def editsRoutes(oAuth2Authorization: OAuth2Authorization): Route =
    handleRejections(corsRejectionHandler) {
      cors() {
        encodeResponse {
          editsSummaryPath(oAuth2Authorization) ~ editDetailsPath(oAuth2Authorization) ~ editsSummaryCsvPath(oAuth2Authorization)
        }
      }
    }

  private def toEditSummaryResponse(e: EditSummary, period: String): EditSummaryResponse =
    EditSummaryResponse(e.editName, EditDescriptionLookup.lookupDescription(e.editName, period))

  private def editDetails(persistenceId: String, summary: EditDetailsSummary): Future[EditDetailsSummary] = {
    val editDetails = eventEnvelopeByPersistenceId(persistenceId)
      .map(envelope => envelope.event.asInstanceOf[EditDetailsPersistenceEvent])
      .collect {
        case EditDetailsAdded(editDetail) => editDetail
      }
      .filter(e => e.edit == summary.editName)
      .drop(summary.fromIndex)
      .take(summary.count)
      .runWith(Sink.seq)
    editDetails.map(e => summary.copy(rows = e.flatMap(r => r.rows)))
  }

  private val csvHeaderSource =
    Source.fromIterator(() => Iterator("editType,editId,ULI,editDescription\n"))

  private def validationErrorEventStream(submissionId: SubmissionId): Source[String, NotUsed] = {
    val persistenceId = s"${HmdaValidationError.name}-$submissionId"
    eventsByPersistenceId(persistenceId).collect {
      case evt @ HmdaRowValidatedError(_, _) => evt
    }.mapConcat(
        e =>
          e.validationErrors.map(
            e =>
              EditsCsvResponse(
                e.validationErrorType.toString,
                e.editName,
                e.uli,
                EditDescriptionLookup.lookupDescription(e.editName, submissionId.period)
              )
          )
      )
      .map(_.toCsv)
  }

}
