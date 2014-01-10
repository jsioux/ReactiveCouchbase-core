package org.reactivecouchbase.client

import org.reactivecouchbase._
import com.couchbase.client.protocol.views.{Stale, Query}
import play.api.libs.json._
import scala.concurrent.{Promise, Future, ExecutionContext}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import play.api.libs.iteratee.{Enumeratee, Enumerator}
import net.spy.memcached.{ReplicateTo, PersistTo}
import net.spy.memcached.ops.OperationStatus
import scala.concurrent.duration.Duration
import org.reactivecouchbase.CouchbaseExpiration._
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsObject

object CappedBucket {
  private val buckets = new ConcurrentHashMap[String, CappedBucket]()
  private def docName = "play2couchbase-cappedbucket-designdoc"
  private def viewName = "byNaturalOrder"
  private def cappedRef = "__playcbcapped"
  private def cappedNaturalId = "__playcbcappednatural"
  private def designDocOld =
    s"""
      {
        "views":{
           "byNaturalOrder": {
               "map": "function (doc, meta) { if (doc.$cappedRef) { if (doc.$cappedNaturalId) { emit(doc.$cappedNaturalId, null); } } } "
           }
        }
      }
    """

  private def designDoc = Json.obj(
    "views" -> Json.obj(
      "byNaturalOrder" -> Json.obj(
        "map" -> s"""
                    | function (doc, meta) {
                    |   if (doc.$cappedRef) {
                    |     if (doc.$cappedNaturalId) {
                    |       emit(doc.$cappedNaturalId, null);
                    |     }
                    |   }
                    | }
                  """.stripMargin
      )
    )
  )

  /**
   *
   * Build a Capped Bucket from a bucket
   *
   * @param bucket the bucket to use the bucket to transform in capped bucket
   * @param ec ExecutionContext for async processing ExecutionContext for async processing
   * @param max max elements in the capped bucket
   * @param reaper trigger reaper to kill elements after max
   * @return the capped bucket
   */
  def apply(bucket: CouchbaseBucket, ec: ExecutionContext, max: Int, reaper: Boolean = true) = {
    if (!buckets.containsKey(bucket.alias)) {
      buckets.putIfAbsent(bucket.alias, new CappedBucket(bucket, ec, max, reaper))
    }
    buckets.get(bucket.alias)
  }

  private def enabledReaper(bucket: CouchbaseBucket, max: Int, ec: ExecutionContext) = {
    if (!reaperOn.containsKey(bucket.alias)) {
      reaperOn.putIfAbsent(bucket.alias, true)
      bucket.driver.logger.info(s"Capped reaper is on for ${bucket.alias} ...")
      bucket.driver.scheduler().schedule(Duration(0, TimeUnit.MILLISECONDS), Duration(1000, TimeUnit.MILLISECONDS))({
        val query = new Query().setIncludeDocs(false).setStale(Stale.FALSE).setDescending(true).setSkip(max)
        bucket.rawSearch(docName, viewName)(query)(ec).toList(ec).map { f =>
          f.map { elem =>
            bucket.delete(elem.id.get)(ec)
          }}(ec)
      })(ec)
    }
  }

  private lazy val reaperOn = new ConcurrentHashMap[String, Boolean]()
  private lazy val triggerPromise = Promise[Unit]()
  private lazy val trigger = triggerPromise.future

  private def setupViews(bucket: CouchbaseBucket, ec: ExecutionContext) = {
    bucket.createDesignDoc(CappedBucket.docName, CappedBucket.designDoc)(ec).map(_ => triggerPromise.success(()))(ec)
  }
}

/**
 *
 * Represent a Capped bucket (capped is handle in the driver, not on the server side)
 *
 * @param bucket the bucket to use the bucket to use
 * @param ec ExecutionContext for async processing 
 * @param max max elements in the bucket
 * @param reaper enable reaper to remove old elements
 */
class CappedBucket(bucket: CouchbaseBucket, ec: ExecutionContext, max: Int, reaper: Boolean = true) {

  if (!CappedBucket.triggerPromise.isCompleted) CappedBucket.setupViews(bucket, ec)
  if (reaper) CappedBucket.enabledReaper(bucket, max, ec)

  /**
   *
   * Retrieve the oldest document from the capped bucket
   *
   * @param r Json reader for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def oldestOption[T](implicit r: Reads[T], ec: ExecutionContext): Future[Option[T]] = {
    val query = new Query().setIncludeDocs(true).setStale(Stale.FALSE).setDescending(false).setLimit(1)
    CappedBucket.trigger.flatMap(_ => Couchbase.find[T](CappedBucket.docName, CappedBucket.viewName)(query)(bucket, r, ec).map(_.headOption))
  }

  /**
   *
   * Retrieve the last inserted document from the capped bucket
   *
   * @param r Json reader for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def lastInsertedOption[T](implicit r: Reads[T], ec: ExecutionContext): Future[Option[T]] = {
    val query = new Query().setIncludeDocs(true).setStale(Stale.FALSE).setDescending(true).setLimit(1)
    CappedBucket.trigger.flatMap(_ => Couchbase.find[T](CappedBucket.docName, CappedBucket.viewName)(query)(bucket, r, ec).map(_.headOption))
  }

  /**
   *
   * Retrieve an infinite stream of data from a capped bucket. Each time a document is inserted in the bucket, doc
   * document is pushed in the stream.
   *
   * @param from natural insertion id to retrieve from
   * @param every retrieve new doc every
   * @param unit time unit
   * @param r Json reader for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def tail[T](from: Long = 0L, every: Long = 1000L, unit: TimeUnit = TimeUnit.MILLISECONDS)(implicit r: Reads[T], ec: ExecutionContext): Future[Enumerator[T]] = {
    CappedBucket.trigger.map( _ => Couchbase.tailableQuery[JsObject](CappedBucket.docName, CappedBucket.viewName, { obj =>
      (obj \ CappedBucket.cappedNaturalId).as[Long]
    }, from, every, unit)(bucket, CouchbaseRWImplicits.documentAsJsObjectReader, ec).through(Enumeratee.map { elem =>
       r.reads(elem.asInstanceOf[JsValue])
    }).through(Enumeratee.collect {
      case JsSuccess(elem, _) => elem
    }))
  }

  /**
   *
   * Insert document into the capped bucket
   *
   * @param key the key of the inserted doc
   * @param value the doc to insert
   * @param exp expiration of data
   * @param persistTo persistance flag
   * @param replicateTo replication flag
   * @param w Json writer for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def insert[T](key: String, value: T, exp: CouchbaseExpirationTiming = Constants.expiration, persistTo: PersistTo = PersistTo.ZERO, replicateTo: ReplicateTo = ReplicateTo.ZERO)(implicit w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    val jsObj = w.writes(value).as[JsObject]
    val enhancedJsObj = jsObj ++ Json.obj(CappedBucket.cappedRef -> true, CappedBucket.cappedNaturalId -> System.currentTimeMillis())
    CappedBucket.trigger.flatMap(_ => Couchbase.set[JsObject](key, enhancedJsObj, exp, persistTo, replicateTo)(bucket, CouchbaseRWImplicits.jsObjectToDocumentWriter, ec))
  }

  /**
   *
   * Insert a document into the capped bucket
   *
   * @param key the key of the inserted doc
   * @param value the doc to insert
   * @param exp expiration of data
   * @param persistTo persistance flag
   * @param replicateTo replication flag
   * @param w Json writer for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def insertWithKey[T](key: T => String, value: T, exp: CouchbaseExpirationTiming = Constants.expiration, persistTo: PersistTo = PersistTo.ZERO, replicateTo: ReplicateTo = ReplicateTo.ZERO)(implicit w: Writes[T], ec: ExecutionContext): Future[OperationStatus] = {
    val jsObj = w.writes(value).as[JsObject]
    val enhancedJsObj = jsObj ++ Json.obj(CappedBucket.cappedRef -> true, CappedBucket.cappedNaturalId -> System.currentTimeMillis())
    CappedBucket.trigger.flatMap(_ => Couchbase.setWithKey[JsObject]({ _ => key(value)}, enhancedJsObj, exp, persistTo, replicateTo)(bucket, CouchbaseRWImplicits.jsObjectToDocumentWriter, ec))
  }

  /**
   *
   * Insert a stream of data into the capped bucket
   *
   * @param data stream of data
   * @param exp expiration of data to insert
   * @param persistTo persistance flag
   * @param replicateTo replication flag
   * @param w Json writer for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def insertStream[T](data: Enumerator[(String, T)], exp: CouchbaseExpirationTiming = Constants.expiration, persistTo: PersistTo = PersistTo.ZERO, replicateTo: ReplicateTo = ReplicateTo.ZERO)(implicit w: Writes[T], ec: ExecutionContext): Future[List[OperationStatus]] = {
    val enhancedEnumerator = data.through(Enumeratee.map { elem =>
      val jsObj = w.writes(elem._2).as[JsObject]
      val enhancedJsObj = jsObj ++ Json.obj(CappedBucket.cappedRef -> true, CappedBucket.cappedNaturalId -> System.currentTimeMillis())
      (elem._1, enhancedJsObj)
    })
    CappedBucket.trigger.flatMap(_ => Couchbase.setStream[JsObject](enhancedEnumerator, exp, persistTo, replicateTo)(bucket, CouchbaseRWImplicits.jsObjectToDocumentWriter, ec))
  }

  /**
   *
   * Insert a stream of data into the capped bucket
   *
   * @param key the key extractor
   * @param data stream of data to insert
   * @param exp expiration of data
   * @param persistTo persistance flag
   * @param replicateTo replication flag
   * @param w Json writer for type T
   * @param ec ExecutionContext for async processing
   * @tparam T type of the doc
   * @return
   */
  def insertStreamWithKey[T](key: T => String, data: Enumerator[T], exp: CouchbaseExpirationTiming = Constants.expiration, persistTo: PersistTo = PersistTo.ZERO, replicateTo: ReplicateTo = ReplicateTo.ZERO)(implicit w: Writes[T], ec: ExecutionContext): Future[List[OperationStatus]] = {
    val enhancedEnumerator = data.through(Enumeratee.map { elem =>
      val jsObj = w.writes(elem).as[JsObject]
      val enhancedJsObj = jsObj ++ Json.obj(CappedBucket.cappedRef -> true, CappedBucket.cappedNaturalId -> System.currentTimeMillis())
      (key(elem), enhancedJsObj)
    })
    CappedBucket.trigger.flatMap(_ => Couchbase.setStream[JsObject](enhancedEnumerator, exp, persistTo, replicateTo)(bucket, CouchbaseRWImplicits.jsObjectToDocumentWriter, ec))
  }
}
