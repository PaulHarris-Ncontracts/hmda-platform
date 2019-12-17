package hmda.dashboard.models

import io.circe.{Decoder, Encoder, HCursor}

case class LARByAgencyAggregationResponse(aggregations: Seq[LARByAgency])

object LARByAgencyAggregationResponse {
  private object constants {
    val Results = "results"
  }

  implicit val encoder: Encoder[LARByAgencyAggregationResponse] =
    Encoder.forProduct1(constants.Results)(aggR =>
      aggR.aggregations)

  implicit val decoder: Decoder[LARByAgencyAggregationResponse] = (c: HCursor) =>
    for {
      a <- c.downField(constants.Results).as[Seq[LARByAgency]]
    } yield LARByAgencyAggregationResponse(a)
}
