package uk.co.neovahealth.nhADT

import ca.uhn.hl7v2.DefaultHapiContext
import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator
import com.tactix4.t4openerp.connector.OEConnector
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.camel.component.redis.RedisConstants
import org.apache.camel.model.IdempotentConsumerDefinition
import org.apache.camel.model.dataformat.HL7DataFormat
import org.apache.camel.scala.dsl.SIdempotentConsumerDefinition
import org.apache.camel.scala.dsl.builder.RouteBuilder
import org.apache.camel.{Exchange, LoggingLevel}
import uk.co.neovahealth.nhADT.rules.RuleHandler
import uk.co.neovahealth.nhADT.utils.ConfigHelper

import scala.concurrent.ExecutionContext
import scalaz.std.string._
import scalaz.syntax.std.option._


class ADTInRoute() extends RouteBuilder with EObsCalls with ADTErrorHandling with ADTProcessing with StrictLogging with RuleHandler{

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  implicit def idem2Sidem(i:IdempotentConsumerDefinition):SIdempotentConsumerDefinition = SIdempotentConsumerDefinition(i)(this)

  lazy val session = new OEConnector(ConfigHelper.protocol, ConfigHelper.host, ConfigHelper.port)
    .startSession(ConfigHelper.username, ConfigHelper.password, ConfigHelper.database)


  val ctx = new DefaultHapiContext()
  //the default FileBased ID Generator starts failing with multiple threads
  ctx.getParserConfiguration.setIdGenerator(new InMemoryIDGenerator())

  val msgHistory = "msgHistory"
  val hl7Listener = "hl7listener"
  val inputQueue = "activemq-in"
  val persistTimestamp = "direct:persistTimestamp"
  val getTimestamp = "direct:getVisitTimestamp"

  val DetectHistorical = "direct:detectHistorical"

  val Admit = "direct:admit"
  val CAdmit = "direct:cancelAdmit"
  val UpdateVisit   = "direct:updateVisit"
  val UpdateOrCreateVisit = "direct:updateOrCreateVisit"
  val Register = "direct:register"
  val UpdatePatient   = "direct:updatePatient"
  val UpdateOrCreatePatient = "direct:updateOrCreatePatient"

  val Transfer = "direct:transfer"
  val CTransfer = "direct:cancelTransfer"
  val Discharge = "direct:discharge"
  val CDischarge = "direct:cancelDischarge"
  val Merge = "direct:merge"



  hl7Listener ==> {
    unmarshal(hl7)
    setHeader("JMSXGroupID", (e: Exchange) => ~getHospitalNumber(e))
    setHeader("hospitalNoString", (e:Exchange) => e.in("JMSXGroupID").toString)
    setHeader("visitNameString", (e:Exchange) => ~getVisitName(e))
    setHeader("msgBody", (e:Exchange) => e.in[Message].toString)
    log(LoggingLevel.INFO, "received ${in.header.CamelHL7TriggerEvent} for ${in.header.JMSXGroupID}")
    process(e => {
      val m = e.in[Message]
      m.setParser(ctx.getPipeParser)
      e.in = m
    })
    choice {
      when(_ => ConfigHelper.autoAck) {
        inOnly {
          marshal(hl7)
          to(inputQueue)
        }
        unmarshal(hl7)
        transform(_.in[Message].generateACK())
        marshal(hl7)
      }
      otherwise {
        marshal(hl7)
        to(inputQueue)
      }
    }
  } routeId "Listener to activemq"

  inputQueue ==> {
    unmarshal(hl7)
    log(LoggingLevel.INFO, "processing ${in.header.CamelHL7TriggerEvent} for ${in.header.JMSXGroupID}")
    process(processRules)
    bean(RoutingSlipBean())
    to(msgHistory)
    transform(_.in[Message].generateACK())
    marshal(hl7)
  } routeId "Main Route"


  persistTimestamp ==> {
    when(getTimestamp(_)) {
      setHeader(RedisConstants.KEY, (e: Exchange) => ~getVisitName(e))
      setHeader(RedisConstants.VALUE, (e: Exchange) => ~getTimestamp(e))
      to("toRedis")
    }
  } routeId "Timestamp to redis"

  getTimestamp ==> {
    setHeader(RedisConstants.COMMAND,(e:Exchange) => constant("GET")(e))
    setHeader(RedisConstants.KEY,(e:Exchange) => ~getVisitName(e))
    enrich("fromRedis",new AggregateLastModTimestamp)
    process(e => getTimestamp(e))
    log(LoggingLevel.INFO,"Timestamp from redis : ${header.lastModTimestamp}. Timestamp from message: ${header.timestamp}")
  } routeId "Timestamp from redis"


  UpdatePatient ==> {
    process(patientUpdate(_))
  } routeId "UpdatePatient"

  UpdateVisit ==> {
    process(visitUpdate(_))
  } routeId "UpdateVisit"


  UpdateOrCreatePatient ==> {
    choice {
      when(patientExists(_)) {
        process(patientUpdate(_))
      }
      otherwise {
        process(handleUnknownPatient(patientNew(_)))
      }
    }
  } routeId "Create/Update Patient"

  UpdateOrCreateVisit ==> {
    choice {
      when(visitExists(_)) {
        process(visitUpdate(_))
      }
      when(e => getVisitName(e).isDefined && !visitExists(e)) {
        process(handleUnknownVisit(visitNew(_)))
      }
      otherwise {
        log(LoggingLevel.INFO, "Message has no visit identifier - can not update/create visit")
      }

    }
  } routeId "Create/Update Visit"

  Admit ==> {
    process(visitNew(_))
    -->(persistTimestamp)
  } routeId "Admit"


  CAdmit ==> {
    when(visitExists(_)) {
      process(cancelVisitNew(_))
    } otherwise {
      process(handleUnknownVisit( implicit e => {
        visitNew
        cancelVisitNew
      }))
    }
  } routeId "CancelAdmit"

  Transfer ==> {
    choice {
      when(visitExists(_)) {
        process(patientTransfer(_))
      }
      otherwise {
        process(handleUnknownVisit(implicit e => {
          visitNew
          patientTransfer
        }))
      }
    }
    -->(persistTimestamp)
  } routeId "Transfer"


  CTransfer ==> {
    when(visitExists(_)){
      process(cancelPatientTransfer(_))
    } otherwise {
      process(handleUnknownVisit(implicit e => {
        visitNew
        cancelPatientTransfer
      }))

    }
  } routeId "CancelTransfer"

  Discharge ==> {
    when(e => visitExists(e)) {
      process(patientDischarge(_))
    } otherwise {
      process(handleUnknownVisit(implicit e => {
        visitNew
        patientDischarge
      }))
    }
    -->(persistTimestamp)
  } routeId "Discharge"

  CDischarge ==> {
    when(visitExists(_)) {
      process(cancelPatientDischarge(_))
    } otherwise {
      process(e => handleUnknownVisit( e =>{
        visitNew(e)
      }))
    }
  } routeId "CancelDischarge"

  Register ==> {
    process(patientNew(_))
  } routeId "Register"

  Merge ==> {
    process(patientMerge(_))
  } routeId "Merge"

  DetectHistorical ==> {
    -->(getTimestamp)
    when(e => !refersToCurrentAction(e)) {
      throwException(new ADTHistoricalMessage("Message refers to an historical event"))
    }
  } routeId "DetectHistorical"

  def refersToCurrentAction(implicit e:Exchange): Boolean = {
    val r = for {
      l <- getHeader[String]("lastModTimestamp")
      t <- getHeader[String]("timestamp")
    } yield t >= l

    r | true
  }
}

