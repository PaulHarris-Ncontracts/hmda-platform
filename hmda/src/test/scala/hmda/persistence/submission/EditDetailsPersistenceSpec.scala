package hmda.persistence.submission

import akka.actor
import akka.actor.testkit.typed.scaladsl.TestProbe
import hmda.persistence.AkkaCassandraPersistenceSpec
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed.{Cluster, Join}
import hmda.messages.submission.EditDetailPersistenceCommands.{
  GetEditRowCount,
  PersistEditDetails
}
import hmda.messages.submission.EditDetailPersistenceEvents.{
  EditDetailsAdded,
  EditDetailsPersistenceEvent
}
import hmda.model.edits.{EditDetails, EditDetailsRow}
import hmda.model.filing.submission.SubmissionId

class EditDetailsPersistenceSpec extends AkkaCassandraPersistenceSpec {
  override implicit val system = actor.ActorSystem()
  override implicit val typedSystem = system.toTyped

  val sharding = ClusterSharding(typedSystem)

  val editDetailProbe = TestProbe[EditDetailsPersistenceEvent]("edit-detail")
  val editRowCountProbe = TestProbe[Int]("row-count")

  override def beforeAll(): Unit = {
    super.beforeAll()
    EditDetailsPersistence.startShardRegion(sharding)
  }

  "Edit Details" must {
    val submissionId = SubmissionId("ABCD", "2018", 1)
    Cluster(typedSystem).manager ! Join(Cluster(typedSystem).selfMember.address)
    "be persisted and read back" in {
      val editDetail1 = EditDetails("S300",
                                    Seq(
                                      EditDetailsRow("1"),
                                      EditDetailsRow("2")
                                    ))
      val editDetail2 = EditDetails("S301",
                                    Seq(
                                      EditDetailsRow("1")
                                    ))

      val editDetailPersistence =
        sharding.entityRefFor(EditDetailsPersistence.typeKey,
                              s"${EditDetailsPersistence.name}-$submissionId")

      editDetailPersistence ! PersistEditDetails(editDetail1,
                                                 Some(editDetailProbe.ref))
      editDetailProbe.expectMessage(EditDetailsAdded(editDetail1))

      editDetailPersistence ! PersistEditDetails(editDetail2,
                                                 Some(editDetailProbe.ref))
      editDetailProbe.expectMessage(EditDetailsAdded(editDetail2))

      editDetailPersistence ! GetEditRowCount("S300", editRowCountProbe.ref)
      editRowCountProbe.expectMessage(2)

      editDetailPersistence ! GetEditRowCount("S301", editRowCountProbe.ref)
      editRowCountProbe.expectMessage(1)

    }
  }

}
