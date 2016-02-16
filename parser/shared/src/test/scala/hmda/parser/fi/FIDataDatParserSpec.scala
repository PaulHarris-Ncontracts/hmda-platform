package hmda.parser.fi

import org.scalatest.{ MustMatchers, PropSpec }
import org.scalatest.prop.PropertyChecks

class FIDataDatParserSpec extends PropSpec with PropertyChecks with MustMatchers with FIDataGenerators {
  property("FI Data must be parsed from DAT")(pending)
}