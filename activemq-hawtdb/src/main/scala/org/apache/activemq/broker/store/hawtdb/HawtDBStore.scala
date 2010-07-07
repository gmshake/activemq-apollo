/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.store.hawtdb

import org.apache.activemq.broker.store.{StoreBatch, Store}
import org.fusesource.hawtdispatch.BaseRetained
import java.util.concurrent.atomic.AtomicLong
import collection.mutable.ListBuffer
import java.util.HashMap
import java.util.concurrent.{TimeUnit, Executors, ExecutorService}
import org.apache.activemq.apollo.util.IntCounter
import org.apache.activemq.apollo.store.{QueueEntryRecord, MessageRecord, QueueStatus, QueueRecord}
import org.apache.activemq.apollo.broker.{Logging, Log, BaseService}
import org.apache.activemq.apollo.dto.{HawtDBStoreDTO, StoreDTO}
import collection.{JavaConversions, Seq}
import org.apache.activemq.apollo.broker.{Reporting, ReporterLevel, Reporter}
import org.fusesource.hawtdispatch.ScalaDispatch._
import ReporterLevel._
import java.io.File

object HawtDBStore extends Log {
  val DATABASE_LOCKED_WAIT_DELAY = 10 * 1000;

  /**
   * Creates a default a configuration object.
   */
  def defaultConfig() = {
    val rc = new HawtDBStoreDTO
    rc.directory = new File("activemq-data")
    rc
  }

  /**
   * Validates a configuration object.
   */
  def validate(config: HawtDBStoreDTO, reporter:Reporter):ReporterLevel = {
    new Reporting(reporter) {
      if( config.directory==null ) {
        error("The HawtDB Store directory property must be configured.")
      }
    }.result
  }
}

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HawtDBStore extends Store with BaseService with Logging {

  import HawtDBStore._
  override protected def log = HawtDBStore

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the BaseService interface
  //
  /////////////////////////////////////////////////////////////////////
  val dispatchQueue = createQueue("hawtdb store")

  var next_queue_key = new AtomicLong(0)
  var next_msg_key = new AtomicLong(0)

  var executor_pool:ExecutorService = _
  var config:HawtDBStoreDTO = defaultConfig
  val client = new HawtDBClient(this)

  def configure(config: StoreDTO, reporter: Reporter) = configure(config.asInstanceOf[HawtDBStoreDTO], reporter)

  def configure(config: HawtDBStoreDTO, reporter: Reporter) = {
    if ( HawtDBStore.validate(config, reporter) < ERROR ) {
      if( serviceState.isStarted ) {
        // TODO: apply changes while he broker is running.
        reporter.report(WARN, "Updating cassandra store configuration at runtime is not yet supported.  You must restart the broker for the change to take effect.")
      } else {
        this.config = config
      }
    }
  }

  protected def _start(onCompleted: Runnable) = {
    executor_pool = Executors.newFixedThreadPool(20)
    client.config = config
    executor_pool {
      client.start
      onCompleted.run
    }
  }

  protected def _stop(onCompleted: Runnable) = {
    new Thread() {
      override def run = {
        executor_pool.shutdown
        executor_pool.awaitTermination(1, TimeUnit.DAYS)
        executor_pool = null
        client.stop
        onCompleted.run
      }
    }.start
  }

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the BrokerDatabase interface
  //
  /////////////////////////////////////////////////////////////////////

  /**
   * Deletes all stored data from the store.
   */
  def purge(callback: =>Unit) = {
    executor_pool ^{
      client.purge
      callback
    }
  }

  def addQueue(record: QueueRecord)(callback: (Option[Long]) => Unit) = {
    val key = next_queue_key.incrementAndGet
    record.key = key
    executor_pool ^{
      client.addQueue(record)
      callback(Some(key))
    }
  }

  def removeQueue(queueKey: Long)(callback: (Boolean) => Unit) = {
    executor_pool ^{
      callback(client.removeQueue(queueKey))
    }
  }

  def getQueueStatus(id: Long)(callback: (Option[QueueStatus]) => Unit) = {
    executor_pool ^{
      callback( client.getQueueStatus(id) )
    }
  }

  def listQueues(callback: (Seq[Long]) => Unit) = {
    executor_pool ^{
      callback( client.listQueues )
    }
  }

  def loadMessage(id: Long)(callback: (Option[MessageRecord]) => Unit) = {
    executor_pool ^{
      callback( client.loadMessage(id) )
    }
  }


  def listQueueEntries(id: Long)(callback: (Seq[QueueEntryRecord]) => Unit) = {
    executor_pool ^{
      callback( client.getQueueEntries(id) )
    }
  }

  def flushMessage(id: Long)(callback: => Unit) = ^{
    val action: HawtDBBatch#MessageAction = pendingStores.get(id)
    if( action == null ) {
      callback
    } else {
      val prevDisposer = action.tx.getDisposer
      action.tx.setDisposer(^{
        callback
        if(prevDisposer!=null) {
          prevDisposer.run
        }
      })
      flush(action.tx.txid)
    }

  } >>: dispatchQueue

  def createStoreBatch() = new HawtDBBatch


  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the StoreBatch interface
  //
  /////////////////////////////////////////////////////////////////////
  class HawtDBBatch extends BaseRetained with StoreBatch {

    class MessageAction {

      var msg= 0L
      var store: MessageRecord = null
      var enqueues = ListBuffer[QueueEntryRecord]()
      var dequeues = ListBuffer[QueueEntryRecord]()

      def tx = HawtDBBatch.this
      def isEmpty() = store==null && enqueues==Nil && dequeues==Nil
      def cancel() = {
        tx.rm(msg)
        if( tx.isEmpty ) {
          tx.cancel
        }
      }
    }

    var actions = Map[Long, MessageAction]()
    var txid:Int = 0

    def rm(msg:Long) = {
      actions -= msg
    }

    def isEmpty = actions.isEmpty
    def cancel = {
      delayedTransactions.remove(txid)
      onPerformed
    }

    def store(record: MessageRecord):Long = {
      record.key = next_msg_key.incrementAndGet
      val action = new MessageAction
      action.msg = record.key
      action.store = record
      this.synchronized {
        actions += record.key -> action
      }
      record.key
    }

    def action(msg:Long) = {
      actions.get(msg) match {
        case Some(x) => x
        case None =>
          val x = new MessageAction
          x.msg = msg
          actions += msg->x
          x
      }
    }

    def enqueue(entry: QueueEntryRecord) = {
      this.synchronized {
        action(entry.messageKey).enqueues += entry
      }
    }

    def dequeue(entry: QueueEntryRecord) = {
      this.synchronized {
        action(entry.messageKey).dequeues += entry
      }
    }

    override def dispose = {
      transaction_source.merge(this)
    }

    def onPerformed() {
      super.dispose
    }
  }

  def key(x:QueueEntryRecord) = (x.queueKey, x.queueSeq)

  val transaction_source = createSource(new ListEventAggregator[HawtDBBatch](), dispatchQueue)
  transaction_source.setEventHandler(^{drain_transactions});
  transaction_source.resume

  var pendingStores = new HashMap[Long, HawtDBBatch#MessageAction]()
  var pendingEnqueues = new HashMap[(Long,Long), HawtDBBatch#MessageAction]()
  var delayedTransactions = new HashMap[Int, HawtDBBatch]()

  var next_tx_id = new IntCounter
  
  def drain_transactions = {
    transaction_source.getData.foreach { tx =>

      val tx_id = next_tx_id.incrementAndGet
      tx.txid = tx_id
      delayedTransactions.put(tx_id, tx)

      if( config.flushDelay > 0 ) {
        dispatchQueue.dispatchAfter(config.flushDelay, TimeUnit.MILLISECONDS, ^{flush(tx_id)})
      } else {
        dispatchQueue.dispatchAsync(^{flush(tx_id)})
      }

      tx.actions.foreach { case (msg, action) =>
        if( action.store!=null ) {
          pendingStores.put(msg, action)
        }
        action.enqueues.foreach { queueEntry=>
          pendingEnqueues.put(key(queueEntry), action)
        }


        // dequeues can cancel out previous enqueues
        action.dequeues.foreach { currentDequeue=>
          val currentKey = key(currentDequeue)
          val prevAction:HawtDBBatch#MessageAction = pendingEnqueues.remove(currentKey)
          if( prevAction!=null ) {

            // yay we can cancel out a previous enqueue
            prevAction.enqueues = prevAction.enqueues.filterNot( x=> key(x) == currentKey )

            // if the message is not in any queues.. we can gc it..
            if( prevAction.enqueues == Nil && prevAction.store !=null ) {
              pendingStores.remove(msg)
              prevAction.store = null
            }

            // Cancel the action if it's now empty
            if( prevAction.isEmpty ) {
              prevAction.cancel()
            }

            // since we canceled out the previous enqueue.. now cancel out the action
            action.dequeues = action.dequeues.filterNot( _ == currentDequeue)
            if( action.isEmpty ) {
              action.cancel()
            }
          }
        }
      }

    }
  }

  def flush(tx_id:Int) = {
    flush_source.merge(tx_id)
  }

  val flush_source = createSource(new ListEventAggregator[Int](), dispatchQueue)
  flush_source.setEventHandler(^{drain_flushes});
  flush_source.resume

  def drain_flushes:Unit = {

    if( !serviceState.isStarted ) {
      return
    }
    
    val txs = flush_source.getData.flatMap{ tx_id =>
      val tx = delayedTransactions.remove(tx_id)
      // Message may be flushed or canceled before the timeout flush event..
      // tx may be null in those cases
      if (tx!=null) {

        tx.actions.foreach { case (msg, action) =>
          if( action.store!=null ) {
            pendingStores.remove(msg)
          }
          action.enqueues.foreach { queueEntry=>
            val k = key(queueEntry)
            pendingEnqueues.remove(k)
          }
        }

        Some(tx)
      } else {
        None
      }
    }

    if( !txs.isEmpty ) {
      // suspend so that we don't process more flush requests while
      // we are concurrently executing a flush
      flush_source.suspend
      executor_pool ^{
        client.store(txs)
        txs.foreach { x=>
          x.onPerformed
        }
        flush_source.resume
      }
    }
  }

}