package marola.sightings

import java.time.Instant
import java.util.UUID

import scala.jdk.CollectionConverters.*

import kyo.*

import com.azure.cosmos.models.{
  CosmosItemRequestOptions,
  CosmosQueryRequestOptions,
  PartitionKey,
  SqlParameter,
  SqlQuerySpec
}
import com.azure.cosmos.{CosmosClientBuilder, CosmosContainer}

/**
 * Optional `SightingStore` backend for an actually-provisioned, shared Cosmos DB container — needed
 * once the Telegram bot exists and can run as a shared, always-on service (a
 * `LocalFileSightingStore` file only makes sense for one process on one host).
 */
final class CosmosDbSightingStore(
    endpoint: String,
    key: String,
    databaseId: String,
    containerId: String
) extends SightingStore:

  private lazy val container: CosmosContainer =
    val client = CosmosClientBuilder().endpoint(endpoint).key(key).buildClient()
    client.getDatabase(databaseId).getContainer(containerId)

  def record(sighting: Sighting): Unit < Sync =
    Sync.defer {
      val item = new java.util.HashMap[String, Object]()
      item.put("id", UUID.randomUUID().toString)
      item.put("beach_name", sighting.beachName)
      item.put("kind", sighting.kind.toString)
      item.put("note", sighting.note.orNull)
      item.put("reported_at", sighting.reportedAt.toString)
      container.createItem(item, PartitionKey(sighting.beachName), CosmosItemRequestOptions())
      ()
    }

  def recentFor(beachName: String, limit: Int): List[Sighting] < Sync =
    Sync.defer {
      val query = SqlQuerySpec(
        "SELECT TOP @limit * FROM c WHERE c.beach_name = @beachName ORDER BY c.reported_at DESC",
        List(SqlParameter("@limit", limit), SqlParameter("@beachName", beachName)).asJava
      )
      val options = CosmosQueryRequestOptions().setPartitionKey(PartitionKey(beachName))
      container
        .queryItems(query, options, classOf[java.util.Map[String, Object]])
        .iterator()
        .asScala
        .flatMap(CosmosDbSightingStore.fromItem)
        .toList
    }

object CosmosDbSightingStore:
  private def fromItem(item: java.util.Map[String, Object]): Option[Sighting] =
    for
      beachName <- Option(item.get("beach_name")).map(_.toString)
      kindStr <- Option(item.get("kind")).map(_.toString)
      kind <- SightingKind.values.find(_.toString == kindStr)
      reportedAtStr <- Option(item.get("reported_at")).map(_.toString)
    yield Sighting(
      beachName,
      kind,
      Option(item.get("note")).map(_.toString),
      Instant.parse(reportedAtStr)
    )
