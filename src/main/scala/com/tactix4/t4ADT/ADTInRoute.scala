package com.tactix4.t4ADT

import org.apache.camel.scala.dsl.builder.RouteBuilder
import org.apache.camel.model.dataformat.HL7DataFormat

import ca.uhn.hl7v2.util.Terser
import ca.uhn.hl7v2.model.Message

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.Exception._

import org.joda.time.format.{DateTimeFormatter, DateTimeFormatterBuilder, DateTimeFormat}

import com.tactix4.t4skr.T4skrConnector
import com.tactix4.t4ADT.utils.Instrumented
import org.apache.camel.Exchange
import org.apache.camel.scala.dsl.SIdempotentConsumerDefinition

import com.tactix4.t4skr.core.HospitalNo
import org.apache.camel.component.hl7.HL7.terser
import ca.uhn.hl7v2.model.v24.message.ADT_AXX


//TODO: Convert all camelCase fields in config files to not_camel_case
//TODO: create patientExists method in connector
//TODO: add a create table method for sql store

/**
 * A Camel Route for receiving ADT messages over an MLLP connector
 * via the mina2 component, validating them, then calling the associated
 * t4skrConnetor methods and returning an appropriate ack
 * Note: we block on the async t4skrConnector methods because the mina connection
 * is synchronous
 */

class ADTApplicationException(msg:String, cause:Throwable=null) extends Throwable(msg,cause)
class ADTFieldException(msg:String,cause:Throwable=null)extends Throwable(msg,cause)
class ADTUnsupportedMessageException(msg:String=null,cause:Throwable=null)extends Throwable(msg,cause)
class ADTDuplicateMessageException(msg:String=null,cause:Throwable=null)extends Throwable(msg,cause)

class ADTInRoute(implicit val terserMap: Map[String,Map[String, String]],
                 val protocol: String,
                 val host: String,
                 val port: Int,
                 val username: String,
                 val password: String,
                 val database: String,
                 val inputDateFormats: List[String],
                 val wards: List[String],
                 val sexMap: Map[String,String],
                 val toDateFormat: String,
                 val timeOutMillis: Int,
                 val redeliveryDelay: Long,
                 val maximumRedeliveries: Int) extends RouteBuilder with ADTProcessing with ADTErrorHandling with Instrumented{



  val connector = new T4skrConnector(protocol, host, port).startSession(username,password,database)//.map(s => {s.openERPSession.context.setTimeZone("Europe/London"); s})


  val fromDateTimeFormat:DateTimeFormatter = new DateTimeFormatterBuilder().append(null, inputDateFormats.map(DateTimeFormat.forPattern(_).getParser).toArray).toFormatter
  val toDateTimeFormat = DateTimeFormat.forPattern(toDateFormat)
  val datesToParse = List("dob","visit_start_date_time","discharge_date")

  val triggerEventHeader = "CamelHL7TriggerEvent"
  val hl7 = new HL7DataFormat()
  hl7.setValidate(false)


  val patientUpdateTimer = metrics.timer("patientUpdate")
  val patientNewTimer = metrics.timer("patientNew")
  val patientMergeTimer = metrics.timer("patientMerge")
  val visitNewTimer = metrics.timer("visitNew")
  val visitUpdateTimer = metrics.timer("visitUpdate")
  val patientTransferTimer = metrics.timer("patientTransfer")
  val patientDischargeTimer = metrics.timer("patientDischarge")

  val metricMap = Map(
    "A08" ->(patientUpdateTimer, patientUpdate(_: Message)),
    "A31" -> (patientUpdateTimer, patientUpdate(_:Message)),
    "A28" -> (patientNewTimer, patientNew(_:Message)),
    "A05" -> (patientNewTimer,patientNew(_:Message)),
    "A40" -> (patientMergeTimer,patientMerge(_:Message)),
    "A01" -> (visitNewTimer,visitNew(_:Message)),
    "A02" -> (patientTransferTimer,patientTransfer(_:Message)),
    "A03" -> (patientDischargeTimer,patientDischarge(_:Message)),
//  {
//      //happily accept discharge messages for patients that don't exist without any further processing
//      when(_.getProperty("PatientAlreadyExists") == false) process(e => e.in[Message].generateACK())
//      otherwise process(e => patientDischarge(m))
//    }}),
    "A11" -> (visitUpdateTimer, visitUpdate(_:Message)),
    "A12" -> (visitUpdateTimer,visitUpdate(_:Message)),
    "A13" -> (visitUpdateTimer,visitUpdate(_:Message))
  )

  def patientExists(hospitalNumber: HospitalNo): Boolean = {
    hospitalNumber != null && hospitalNumber.length > 0 && Await.result(connector.flatMap(_.getPatientByHospitalNumber(hospitalNumber)), 2000 millis).isDefined
  }

  def visitExists(visitId:VisitId) : Boolean = {
    visitId != null && visitId.length > 0 && Await.result(connector.flatMap(_.visitExists(visitId)), 2000 millis)
  }

  def handleMessageType(t:String) = when(_.in(triggerEventHeader) == t) process(
    e => {
      metrics.meter(t).mark()
      e.setProperty("PatientAlreadyExists",patientExists(terser("PID-3-1").evaluate(e,classOf[String])))
      e.setProperty("VisitAlreadyExists",visitExists(terser("PV1-19").evaluate(e,classOf[String])))
      metricMap(t)._1.time{e.in = metricMap(t)._2(e.in[Message])}
    }
  )



  "hl7listener" ==> {
    process(_ =>  metrics.meter("AllMessages").mark() )
    unmarshal(hl7)
    SIdempotentConsumerDefinition(idempotentConsumer(_.getIn.getHeader("CamelHL7MessageControl"))
      .messageIdRepositoryRef("messageIdRepo")
      .skipDuplicate(false)
      .removeOnFailure(false)
    )(this) {
      process(e => e.getIn.setHeader("msgBody",e.getIn.getBody.toString))
      process(e => e.getIn.setHeader("origMessage",e.in[Message]))
      process(e => e.getIn.setHeader("terser",new Terser(e.in[Message])))
      when(_.getProperty(Exchange.DUPLICATE_MESSAGE)) process(e => throw new ADTDuplicateMessageException("Duplicate Message"))
      choice {
        handleMessageType("A08")
        handleMessageType("A31")
        handleMessageType("A28")
        handleMessageType("A05")
        handleMessageType("A40")
        handleMessageType("A01")
        handleMessageType("A02")
        when(_.in(triggerEventHeader) == "A03") process(
          e => {
            metrics.meter("A03").mark()
            allCatch.opt{
              val t = e.getIn.getHeader("terser").asInstanceOf[Terser]
              e.setProperty("PatientAlreadyExists",patientExists(t.get("PID-3-1")))
              e.setProperty("VisitAlreadyExists",visitExists(t.get("PV1-19")))
            }
            if(e.getProperty("PatientAlreadyExists") == false) { e.in = e.in[Message].generateACK() }
            else patientDischargeTimer.time{e.in = patientDischarge(e.in[Message])}
          }
          )
        handleMessageType("A03")
        handleMessageType("A11")
        handleMessageType("A12")
        handleMessageType("A13")
        otherwise process(e =>  {
          metrics.meter("Unsupported").mark()
          throw new ADTUnsupportedMessageException("Unsupported message type: " + e.in(triggerEventHeader))
        })
      }
      wireTap("seda:update")
      to("msgHistory")
    }
  }
  "seda:update" ==> {
    when(e => (e.getProperty("PatientAlreadyExists") == false) && !List("A31", "A08","A03").contains(e.in(triggerEventHeader)))  process(e => {
      val msg = e.getIn.getHeader("origMessage").asInstanceOf[Message]
      val t = new Terser(msg)
      t.set("MSH-9-2","A31")
      e.in = patientUpdate(msg)
    })
    when(e => e.getProperty("VisitAlreadyExists") == false && e.in(triggerEventHeader) != "A01") process(e => {
      val msg = e.getIn.getHeader("origMessage").asInstanceOf[Message]
      val t = new Terser(msg)
      if(catching(classOf[HL7Exception]).opt(t.getSegment("PV1")).isDefined){
        t.set("MSH-9-2","A01")
        e.in = visitNew(msg)
      }

    })

  }


  def extract(f : Terser => Map[String,String] => Future[_]) (implicit message:Message): Message = {
    implicit val terser = new Terser(message)
    implicit val mappings = getMappings(terser, terserMap)

    val result = allCatch either Await.result(f(terser)(mappings), timeOutMillis millis)

    result.left.map((error: Throwable) => throw new ADTApplicationException(error.getMessage, error))

    message.generateACK()
  }

  def patientMerge(implicit message:Message): Message = extract { implicit terser => implicit m =>
    val requiredFields = validateRequiredFields(List(hosptialNumber, oldHospitalNumber))(m,implicitly)
    connector.flatMap(_.patientMerge(requiredFields(hosptialNumber), requiredFields(oldHospitalNumber)))
  }

  def patientTransfer(implicit message:Message): Message = extract { implicit terser => implicit m =>
    val i = getHospitalNumber(m,implicitly)
    val w = validateRequiredFields(List("ward_identifier"))(m,implicitly)
    connector.flatMap(_.patientTransfer(i,w("ward_identifier")))
  }

  def patientUpdate(implicit message:Message) :Message = extract {implicit terser => implicit m =>
    val i = getHospitalNumber(m,implicitly)
    val o = validateAllOptionalFields(Map(hosptialNumber->i))(m,implicitly)
    connector.flatMap(_.patientUpdate(i,o))
  }

  def patientDischarge(implicit message: Message)  = extract{implicit t => implicit m =>
    val i = getHospitalNumber(m,implicitly)
    val r = validateOptionalFields(List("discharge_date"))(m,implicitly)
    val o = r.get("discharge_date") getOrElse new DateTime().toString(toDateTimeFormat)
    connector.flatMap(_.patientDischarge(i,o))
  }

  def patientNew(implicit message: Message) = extract{implicit t => implicit m =>
    val i = getHospitalNumber(m,implicitly)
    val o = validateAllOptionalFields(Map(hosptialNumber->i))(m,implicitly)
    connector.flatMap(_.patientNew(i, o))
  }

  def visitNew(implicit message: Message) = extract{implicit t => implicit m =>
    val requiredFields =  validateRequiredFields(List("ward_identifier","visit_identifier","visit_start_date_time"))(m,implicitly)
    if(wards contains requiredFields("ward_identifier")) {
      val o = validateAllOptionalFields(requiredFields)(m, implicitly)
      connector.flatMap(_.visitNew(getHospitalNumber(m, implicitly), requiredFields("ward_identifier"), requiredFields("visit_identifier"), requiredFields("visit_start_date_time"), o))
    }
    else {
      Future.successful()
    }
  }

  def visitUpdate(implicit message:Message) = extract{ implicit t => implicit m =>
    Future.successful()
  }

}
