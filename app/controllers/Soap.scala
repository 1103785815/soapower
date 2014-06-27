package controllers

import play.Logger
import play.api.mvc._
import play.api.libs.iteratee._
import models._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import models.RequestData._
import org.jboss.netty.handler.codec.http.HttpMethod

object Soap extends Controller {

  def index(environment: String, localTarget: String) = Action.async(parse.xml) {
    implicit request =>
      Logger.debug("Request on environment:" + environment + " localTarget:" + localTarget)
      val requestContentType = request.contentType.get
      val sender = request.remoteAddress
      val content = request.body.toString()
      val headers = request.headers.toSimpleMap
      forwardRequest(environment, localTarget, sender, content, headers, requestContentType)
  }

  /**
   * Automatically detect new services. If the given parameters interpolates an existing service, then nothing is created otherwise a new service is created.
   * The new service takes the given parameters and theses defaults parameters :
   * <ul>
   * <li>record Xml Data set to false</li>
   * <li>record All Data set to false</li>
   * <li>timeoutms set to default (60000ms)</li>
   * </ul>
   * After this the equivalent of  {@link Soap#index} is made.
   *
   * @param group The group of soap request. It is a logical separation between environments.
   * @param environment The environment group of the soap request. It is a logical separation between services.
   * @param remoteTarget The remote target to be call. The underlying soap request is forwarded to this remote target.
   */
  def autoIndex(group: String, environment: String, remoteTarget: String) = Action.async(parse.xml) {
    implicit request =>
      val requestContentType = request.contentType.get
      Logger.info("Automatic service detection request on group: " + group + " environment:" + environment + " remoteTarget: " + remoteTarget)

      // Extract local target from the remote target
      val localTarget = UtilExtract.extractPathFromURL(remoteTarget)

      if (!localTarget.isDefined) {
        val err = "Invalid remoteTarget:" + remoteTarget
        Logger.error(err)
        BadRequest(err)
      }

      // Search the corresponding service
      val optionService = Await.result(Service.findByLocalTargetAndEnvironmentName(Service.SOAP, localTarget.get, environment), 2.seconds)
      var service: Service = null.asInstanceOf[Service]

      // Now the service exists then we have to forward the request
      val sender = request.remoteAddress
      val content = request.body.toString()
      val headers = request.headers.toSimpleMap

      var err: Option[String] = None

      if (optionService.get == null || !optionService.isDefined) {
        // If the service doesn't exits {
        val description = "This service was automatically generated by soapower"
        val timeoutms = 60000
        val recordContentData = false
        val recordData = false
        val useMockGroup = false

        val environmentOption = Await.result(Environment.findByNameAndGroups(environment, group), 2.seconds)
        // Check that the environment exists for the given group
        environmentOption.map {
          environmentReal =>
            Logger.debug("Detection new service, prepare it to insert")
            // The environment exists so the service creation can be performed
            service = new Service(Some(BSONObjectID.generate),
              description,
              Service.SOAP,
              HttpMethod.POST.toString,
              localTarget.get,
              remoteTarget,
              timeoutms.toInt,
              recordContentData,
              recordData,
              useMockGroup,
              None,
              Some(environment))
            // Persist environment to database
            Service.insert(service)
        }.getOrElse {
          err = Some("environment " + environment + " with group " + group + " unknown")
          Logger.error(err.get)
        }
      }

      if (err.isDefined) {
        Future(BadRequest(err.get))
      } else {
        // Now the service exists then we have to forward the request
        forwardRequest(environment, localTarget.get, sender, content, headers, requestContentType)
      }
  }


  /**
   * Replay a given request.
   * @param requestId request to replay, content is post by user
   */
  def replay(requestId: String) = Action.async(parse.xml) {
    implicit request =>
      RequestData.loadRequest(requestId).map {
        tuple => tuple match {
          case Some(doc: BSONDocument) =>
            val contentType = doc.getAs[String]("contentType").get
            val sender = doc.getAs[String]("sender").get
            val content = request.body.toString()
            val headers = doc.getAs[Map[String, String]]("requestHeaders").get
            val environmentName = doc.getAs[String]("environmentName").get
            val s = Service.findById(environmentName, doc.getAs[BSONObjectID]("serviceId").get.stringify)
            val service = Await.result(s, 1.seconds)
            if (!service.isDefined) {
              NotFound("The service " + doc.getAs[BSONObjectID]("serviceId").get.stringify + " does not exist")
            } else {
              Await.result(forwardRequest(environmentName, service.get.localTarget, sender, content, headers, contentType), 10.seconds)
            }
          case _ =>
            NotFound("The request does not exist")
        }
      }
  }

  private def forwardRequest(environmentName: String, localTarget: String, sender: String, content: String, headers: Map[String, String], requestContentType: String): Future[SimpleResult] = {
    val service = Service.findByLocalTargetAndEnvironmentName(Service.SOAP, localTarget, environmentName)

    service.map(svc =>
      if (svc.isDefined && svc.get != null) {
        val client = new Client(svc.get, environmentName, sender, content, headers, Service.SOAP, requestContentType)
        if (svc.get.useMockGroup && svc.get.mockGroupId.isDefined) {
          val fmock = Mock.findByMockGroupAndContent(BSONObjectID(svc.get.mockGroupId.get), content)
          val mock = Await.result(fmock, 1.second)
          client.workWithMock(mock)
          val sr = new Results.Status(mock.httpStatus).stream(Enumerator(mock.response.getBytes()).andThen(Enumerator.eof[Array[Byte]]))
            .withHeaders("ProxyVia" -> "soapower")
            .withHeaders(UtilConvert.headersFromString(mock.httpHeaders).toArray: _*)
            .as(XML)

          val timeoutFuture = play.api.libs.concurrent.Promise.timeout(sr, mock.timeoutms.milliseconds)
          Await.result(timeoutFuture, 10.second) // 10 seconds (10000 ms) is the maximum allowed.
        } else {
          client.sendSoapRequestAndWaitForResponse
          // forward the response to the client
          new Results.Status(client.response.status).chunked(Enumerator(client.response.bodyBytes).andThen(Enumerator.eof[Array[Byte]]))
            .withHeaders("ProxyVia" -> "soapower")
            .withHeaders(client.response.headers.toArray: _*).as(client.response.contentType)
        }
      } else {
        val err = "environment " + environmentName + " with localTarget " + localTarget + " unknown"
        Logger.error(err)
        BadRequest(err)
      }
    )
  }
}
