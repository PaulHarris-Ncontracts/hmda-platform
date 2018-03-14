package hmda.publication.reports.aggregate

import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.{ ActorSystem, Props }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import akka.stream.Supervision._
import akka.stream.alpakka.s3.javadsl.S3Client
import akka.stream.alpakka.s3.{ MemoryBufferType, S3Settings }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.{ ByteString, Timeout }
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import hmda.persistence.model.HmdaActor
import hmda.query.repository.filing.LoanApplicationRegisterCassandraRepository
import akka.stream.alpakka.s3.javadsl.MultipartUploadResult
import hmda.census.model.MsaIncomeLookup
import hmda.persistence.messages.commands.publication.PublicationCommands.GenerateAggregateReports

import scala.concurrent.duration._
import scalaz.Alpha.X

object AggregateReportPublisher {
  val name = "aggregate-report-publisher"
  def props(): Props = Props(new AggregateReportPublisher)
}

class AggregateReportPublisher extends HmdaActor with LoanApplicationRegisterCassandraRepository {

  val decider: Decider = { e =>
    repositoryLog.error("Unhandled error in stream", e)
    Supervision.Resume
  }

  override implicit def system: ActorSystem = context.system
  val materializerSettings = ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  override implicit def materializer: ActorMaterializer = ActorMaterializer(materializerSettings)(system)

  val duration = config.getInt("hmda.actor.timeout")
  implicit val timeout = Timeout(duration.seconds)

  val accessKeyId = config.getString("hmda.publication.aws.access-key-id")
  val secretAccess = config.getString("hmda.publication.aws.secret-access-key ")
  val region = config.getString("hmda.publication.aws.region")
  val bucket = config.getString("hmda.publication.aws.public-bucket")
  val environment = config.getString("hmda.publication.aws.environment")

  val awsCredentials = new AWSStaticCredentialsProvider(
    new BasicAWSCredentials(accessKeyId, secretAccess)
  )
  val awsSettings = new S3Settings(MemoryBufferType, None, awsCredentials, region, false)
  val s3Client = new S3Client(awsSettings, context.system, materializer)

  val aggregateReports: List[AggregateReport] = List(
    AggregateA1, AggregateA2, AggregateA3,
    A42, A43, A45, A46, A47,
    A11_1, A11_2, A11_3, A11_4, A11_5, A11_6, A11_7, A11_8, A11_9, A11_10
  //A52, A53 FIXME: A5X reports futures don't resolve
  )

  val nationalAggregateReports: List[AggregateReport] = List(
    NationalAggregateA1, NationalAggregateA2, NationalAggregateA3,
    N41, N43, N45, N46, N47,
    N11_1, N11_2, N11_3, N11_4, N11_5, N11_6, N11_7, N11_8, N11_9, N11_10
  //A52, A53 FIXME: A5X reports futures don't resolve
  )

  override def receive: Receive = {

    case GenerateAggregateReports() =>
      log.info(s"Generating aggregate reports for 2017 filing year")
      generateReports

    case _ => //do nothing
  }

  private def generateReports = {
    val larSource = readData(1000)
    val msaList = MsaIncomeLookup.everyFips.toList

    val combinations = combine(msaList, aggregateReports) ++ combine(List(-1), nationalAggregateReports)

    val simpleReportFlow: Flow[(Int, AggregateReport), AggregateReportPayload, NotUsed] =
      Flow[(Int, AggregateReport)].mapAsyncUnordered(1) {
        case (msa, report) => report.generate(larSource, msa)
      }

    val s3Flow: Flow[AggregateReportPayload, CompletionStage[MultipartUploadResult], NotUsed] =
      Flow[AggregateReportPayload]
        .map(payload => {
          val filePath = s"$environment/reports/aggregate/2017/${payload.msa}/${payload.reportID}.txt"
          log.info(s"Publishing Aggregate report. MSA: ${payload.msa}, Report #: ${payload.reportID}")

          Source.single(ByteString(payload.report))
            .runWith(s3Client.multipartUpload(bucket, filePath))
        })

    Source(combinations).via(simpleReportFlow).via(s3Flow).runWith(Sink.ignore)
  }

  /**
   * Returns all combinations of MSA and Aggregate Reports
   * Input:   List(407, 508) and List(A41, A42)
   * Returns: List((407, A41), (407, A42), (508, A41), (508, A42))
   */
  private def combine(msas: List[Int], reports: List[AggregateReport]): List[(Int, AggregateReport)] = {
    msas.flatMap(msa => List.fill(reports.length)(msa).zip(reports))
  }

}

