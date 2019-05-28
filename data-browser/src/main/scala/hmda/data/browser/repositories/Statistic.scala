package hmda.data.browser.repositories

import slick.jdbc.GetResult

private[browser] final case class Statistic(count: Long, sum: Double)

object Statistic {
  implicit val getResult: GetResult[Statistic] = GetResult(
    r => Statistic(r.<<, r.<<))
}