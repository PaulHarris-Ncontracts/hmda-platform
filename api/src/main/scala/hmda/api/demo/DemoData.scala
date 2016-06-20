package hmda.api.demo

import akka.actor.ActorSystem
import hmda.api.model.InstitutionSummary
import hmda.api.persistence.CommonMessages._
import hmda.api.persistence.{ FilingPersistence, SubmissionPersistence }
import hmda.api.persistence.FilingPersistence.CreateFiling
import hmda.api.persistence.InstitutionPersistence.CreateInstitution
import hmda.api.persistence.SubmissionPersistence.CreateSubmission
import hmda.model.fi._

object DemoData {
  val institutions = {
    val i1 = Institution("12345", "First Bank", Active)
    val i2 = Institution("123456", "Second Bank", Inactive)
    Set(i1, i2)
  }

  val filings = {
    val f1 = Filing("2016", "12345", Completed)
    val f2 = Filing("2017", "12345", NotStarted)
    val f3 = Filing("2017", "123456", InProgress)
    Seq(f1, f2, f3)
  }

  val institutionSummary = {
    val institution = institutions.head
    val f = filings.filter(x => x.fid == institution.id)
    InstitutionSummary(institution.id, institution.name, f.reverse)
  }

  val newSubmissions = {
    val s1 = Submission(1, Created)
    val s2 = Submission(2, Created)
    val s3 = Submission(3, Created)
    Seq(s1, s2, s3)
  }

  def loadData(system: ActorSystem): Unit = {
    Thread.sleep(500)
    loadInstitutions(system)
    loadFilings(system)
    loadNewSubmissions(system)
  }

  def loadInstitutions(system: ActorSystem): Unit = {
    val institutionsActor = system.actorSelection("/user/institutions")
    institutions.foreach(i => institutionsActor ! CreateInstitution(i))
  }

  def loadFilings(system: ActorSystem): Unit = {
    filings.foreach { filing =>
      val filingActor = system.actorOf(FilingPersistence.props(filing.fid))
      filingActor ! CreateFiling(filing)
      Thread.sleep(100)
      filingActor ! Shutdown
    }
  }

  def loadNewSubmissions(system: ActorSystem): Unit = {
    newSubmissions.foreach { s =>
      val submissionsActor = system.actorOf(SubmissionPersistence.props("12345", "2017"))
      submissionsActor ! CreateSubmission
      Thread.sleep(100)
      submissionsActor ! Shutdown
    }
  }
}
