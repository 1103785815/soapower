package models

import java.util.{ Date }

import play.api.db._
import play.api.Play.current
import play.api._
import play.api.libs.json._

import anorm._
import anorm.SqlParser._

case class RequestData(
  id: Pk[Long],
  localTarget: String,
  remoteTarget: String,
  request: String,
  startTime: Date,
  var response: String,
  var timeInMillis: Long,
  var status: Int) {

  def this(localTarget: String, remoteTarget: String, request: String) =
    this(null, localTarget, remoteTarget, request, new Date, null, -1, -1)

}

object RequestData {

  /**
   * Parse a RequestData from a ResultSet
   */
  val simple = {
    get[Pk[Long]]("request_data.id") ~
      get[String]("request_data.localTarget") ~
      get[String]("request_data.remoteTarget") ~
      get[String]("request_data.request") ~
      get[Date]("request_data.startTime") ~
      get[String]("request_data.response") ~
      get[Long]("request_data.timeInMillis") ~
      get[Int]("request_data.status") map {
        case id ~ localTarget ~ remoteTarget ~ request ~ startTime ~ response ~ timeInMillis ~ status =>
          RequestData(id, localTarget, remoteTarget, request, startTime, response, timeInMillis, status)
      }
  }

  /**
   * Insert a new RequestData.
   *
   * @param requestData the requestData
   */
  def insert(requestData: RequestData) = {
    try {
      DB.withConnection { implicit connection =>
        SQL(
          """
            insert into request_data 
              (id, localTarget, remoteTarget, request, startTime, response, timeInMillis, status) values (
              (select next value for request_data_seq), 
              {localTarget}, {remoteTarget}, {request}, {startTime}, {response}, {timeInMillis}, {status}
            )
          """).on(
            'localTarget -> requestData.localTarget,
            'remoteTarget -> requestData.remoteTarget,
            'request -> requestData.request,
            'startTime -> requestData.startTime,
            'response -> requestData.response,
            'timeInMillis -> requestData.timeInMillis,
            'status -> requestData.status).executeUpdate()
      }

    } catch {
      case e: Exception => Logger.error("Error during insertion of RequestData ", e)
    }
  }

  /*
  * Get All RequestData, used for testing only
  *
  */
  def findAll(): List[RequestData] = DB.withConnection { implicit c =>
    SQL("select * from request_data").as(RequestData.simple *)
  }

  /**
   * Return a page of (RequestData).
   *
   * @param page Page to display
   * @param pageSize Number of requestData per page
   * @param orderBy RequestData property used for sorting
   * @param filter Filter applied on the name column
   */
  def list(page: Int = 0, pageSize: Int = 10, filterIn: String = "%"): Page[(RequestData)] = {

    val offset = pageSize * page

    var filter = filterIn
    if (filterIn == "") filter = "%"

    DB.withConnection { implicit connection =>

      val requests = SQL(
        """
          select * from request_data
          where request_data.remoteTarget like {filter}
          order by request_data.id desc
          limit {pageSize} offset {offset}
        """).on(
          'pageSize -> pageSize,
          'offset -> offset,
          'filter -> filter).as(RequestData.simple *)
      val totalRows = SQL(
        """
          select count(*) from request_data 
          where request_data.remoteTarget like {filter}
        """).on(
          'filter -> filter).as(scalar[Long].single)
      Page(requests, page, offset, totalRows)

    }

  }

  // use by Json : from scala to json
  implicit object RequestDataWrites extends Writes[RequestData] {

    def writes(o: RequestData): JsValue = JsObject(
      List("0" -> JsString(o.id.toString),
        "1" -> JsString(o.localTarget),
        "2" -> JsString(o.remoteTarget),
        "3" -> JsString("request file"),
        "4" -> JsString(o.startTime.toString),
        "5" -> JsString("reponse file"),
        "6" -> JsString(o.timeInMillis.toString),
        "7" -> JsString(o.status.toString)))
  }

}
