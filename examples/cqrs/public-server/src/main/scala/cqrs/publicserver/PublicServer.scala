package cqrs.publicserver

import java.net.URLEncoder
import java.util.UUID

import cats.Traverse
import cqrs.queries._
import cqrs.commands.{AddRecord, CreateMeter, MeterCreated, StoredEvent}
import play.api.libs.ws.WSClient
import play.api.routing.{Router => PlayRouter}
import cats.instances.option._
import cats.instances.future._
import endpoints.play.routing.{CirceEntities, Endpoints, OptionalResponses}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Implementation of the public API based on our “commands” and “queries” microservices.
  */
class PublicServer(
  commandsBaseUrl: String,
  queriesBaseUrl: String,
  wsClient: WSClient)(implicit
  ec: ExecutionContext
) extends PublicEndpoints
  with Endpoints
  with CirceEntities
  with OptionalResponses {

  private val commandsClient = new CommandsClient(commandsBaseUrl, wsClient)
  //#invocation
  private val queriesClient = new QueriesClient(queriesBaseUrl, wsClient)
  //#invocation

  val routes: PlayRouter.Routes =
    routesFromEndpoints(

      listMeters.implementedByAsync { _ =>
        //#invocation
        val metersList: Future[ResourceList] = queriesClient.query(FindAll)
        //#invocation
        metersList.map(_.value)
      },

      getMeter.implementedByAsync { id =>
        queriesClient.query(FindById(id, None)).map(_.value)
      },

      createMeter.implementedByAsync { createData =>
        for {
          maybeEvent <- commandsClient.command(CreateMeter(createData.label))
          maybeMeter <- Traverse[Option].flatSequence(
            maybeEvent.collect {
              case StoredEvent(t, MeterCreated(id, _)) =>
                //#invocation-find-by-id
                val maybeMeter: Future[MaybeResource] = queriesClient.query(FindById(id, after = Some(t)))
                //#invocation-find-by-id
                maybeMeter.map(_.value)
            }
          )
          meter <- maybeMeter.fold[Future[Meter]](Future.failed(new NoSuchElementException))(Future.successful)
        } yield meter
      },

      addRecord.implementedByAsync { case (id, addData) =>
        for {
          maybeEvent <- commandsClient.command(AddRecord(id, addData.date, addData.value))
          findMeter = (evt: StoredEvent) => queriesClient.query(FindById(id, after = Some(evt.timestamp))).map(_.value)
          maybeMeter <- Traverse[Option].flatTraverse(maybeEvent)(findMeter)
          meter <- maybeMeter.fold[Future[Meter]](Future.failed(new NoSuchElementException))(Future.successful)
        } yield meter
      }

    )

  implicit def uuidSegment: Segment[UUID] =
    new Segment[UUID] {
      def decode(segment: String): Option[UUID] = Try(UUID.fromString(segment)).toOption
      def encode(uuid: UUID): String = URLEncoder.encode(uuid.toString, utf8Name)
    }

  // These aliases are probably due to a limitation of circe
  implicit private def circeEncoderReq: io.circe.Encoder[QueryReq] = QueryReq.queryEncoder
  implicit private def circeDecoderResp: io.circe.Decoder[QueryResp] = QueryResp.queryDecoder

}
