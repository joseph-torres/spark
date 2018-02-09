/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming

import java.{util => ju}
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.encoderFor
import org.apache.spark.sql.catalyst.expressions.{Attribute, UnsafeRow}
import org.apache.spark.sql.catalyst.plans.logical.{LeafNode, Statistics}
import org.apache.spark.sql.catalyst.streaming.InternalOutputModes._
import org.apache.spark.sql.sources.v2.reader.{DataReader, DataReaderFactory, SupportsScanUnsafeRow}
import org.apache.spark.sql.sources.v2.reader.streaming.{MicroBatchReader, Offset => OffsetV2}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils


object MemoryStream {
  protected val currentBlockId = new AtomicInteger(0)
  protected val memoryStreamId = new AtomicInteger(0)

  def apply[A : Encoder](implicit sqlContext: SQLContext): MemoryStream[A] =
    new MemoryStream[A](memoryStreamId.getAndIncrement(), sqlContext)
}

/**
 * A [[Source]] that produces value stored in memory as they are added by the user.  This [[Source]]
 * is intended for use in unit tests as it can only replay data when the object is still
 * available.
 */
case class MemoryStream[A : Encoder](id: Int, sqlContext: SQLContext)
    extends MicroBatchReader with SupportsScanUnsafeRow with Logging {
  protected val encoder = encoderFor[A]
  private val attributes = encoder.schema.toAttributes
  protected val logicalPlan = StreamingExecutionRelation(this, attributes)(sqlContext.sparkSession)
  protected val output = logicalPlan.output

  /**
   * All batches from `lastCommittedOffset + 1` to `currentOffset`, inclusive.
   * Stored in a ListBuffer to facilitate removing committed batches.
   */
  @GuardedBy("this")
  protected val batches = new ListBuffer[Array[UnsafeRow]]

  @GuardedBy("this")
  protected var currentOffset: LongOffset = new LongOffset(-1)

  @GuardedBy("this")
  private var startOffset = new LongOffset(-1)

  @GuardedBy("this")
  private var endOffset = new LongOffset(-1)

  /**
   * Last offset that was discarded, or -1 if no commits have occurred. Note that the value
   * -1 is used in calculations below and isn't just an arbitrary constant.
   */
  @GuardedBy("this")
  protected var lastOffsetCommitted : LongOffset = new LongOffset(-1)

  def toDS(): Dataset[A] = {
    Dataset(sqlContext.sparkSession, logicalPlan)
  }

  def toDF(): DataFrame = {
    Dataset.ofRows(sqlContext.sparkSession, logicalPlan)
  }

  def addData(data: A*): Offset = {
    addData(data.toTraversable)
  }

  def addData(data: TraversableOnce[A]): Offset = {
    val objects = data.toSeq
    val rows = objects.iterator.map(d => encoder.toRow(d).copy().asInstanceOf[UnsafeRow]).toArray
    logDebug(s"Adding: $objects")
    this.synchronized {
      currentOffset = currentOffset + 1
      batches += rows
      currentOffset
    }
  }

  override def toString: String = s"MemoryStream[${Utils.truncatedString(output, ",")}]"

  override def setOffsetRange(start: Optional[OffsetV2], end: Optional[OffsetV2]): Unit = {
    synchronized {
      startOffset = start.orElse(LongOffset(-1)).asInstanceOf[LongOffset]
      endOffset = end.orElse(currentOffset).asInstanceOf[LongOffset]
    }
  }

  override def readSchema(): StructType = encoder.schema

  override def deserializeOffset(json: String): OffsetV2 = LongOffset(json.toLong)

  override def getStartOffset: OffsetV2 = synchronized {
    if (startOffset.offset == -1) null else startOffset
  }

  override def getEndOffset: OffsetV2 = synchronized {
    if (endOffset.offset == -1) null else endOffset
  }

  override def createUnsafeRowReaderFactories(): ju.List[DataReaderFactory[UnsafeRow]] = {
    synchronized {
      // Compute the internal batch numbers to fetch: [startOrdinal, endOrdinal)
      val startOrdinal = startOffset.offset.toInt + 1
      val endOrdinal = endOffset.offset.toInt + 1

      // Internal buffer only holds the batches after lastCommittedOffset.
      val newBlocks = synchronized {
        val sliceStart = startOrdinal - lastOffsetCommitted.offset.toInt - 1
        val sliceEnd = endOrdinal - lastOffsetCommitted.offset.toInt - 1
        assert(sliceStart <= sliceEnd, s"sliceStart: $sliceStart sliceEnd: $sliceEnd")
        batches.slice(sliceStart, sliceEnd)
      }

      logDebug(generateDebugString(newBlocks.flatten, startOrdinal, endOrdinal))

      newBlocks.map { block =>
        new MemoryStreamDataReaderFactory(block).asInstanceOf[DataReaderFactory[UnsafeRow]]
      }.asJava
    }
  }

  private def generateDebugString(
      rows: Seq[UnsafeRow],
      startOrdinal: Int,
      endOrdinal: Int): String = {
    val fromRow = encoder.resolveAndBind().fromRow _
    s"MemoryBatch [$startOrdinal, $endOrdinal]: " +
        s"${rows.map(row => fromRow(row)).mkString(", ")}"
  }

  override def commit(end: OffsetV2): Unit = synchronized {
    def check(newOffset: LongOffset): Unit = {
      val offsetDiff = (newOffset.offset - lastOffsetCommitted.offset).toInt

      if (offsetDiff < 0) {
        sys.error(s"Offsets committed out of order: $lastOffsetCommitted followed by $end")
      }

      batches.trimStart(offsetDiff)
      lastOffsetCommitted = newOffset
    }

    LongOffset.convert(end) match {
      case Some(lo) => check(lo)
      case None => sys.error(s"MemoryStream.commit() received an offset ($end) " +
        "that did not originate with an instance of this class")
    }
  }

  override def stop() {}

  def reset(): Unit = synchronized {
    batches.clear()
    startOffset = LongOffset(-1)
    endOffset = LongOffset(-1)
    currentOffset = new LongOffset(-1)
    lastOffsetCommitted = new LongOffset(-1)
  }
}

class MemoryStreamDataReaderFactory(records: Array[UnsafeRow])
    extends DataReaderFactory[UnsafeRow] {
  override def createDataReader(): DataReader[UnsafeRow] = {
    new DataReader[UnsafeRow] {
      private var currentIndex = -1

      override def next(): Boolean = {
        // Return true as long as the new index is in the array.
        currentIndex += 1
        currentIndex < records.length
      }

      override def get(): UnsafeRow = records(currentIndex)

      override def close(): Unit = {}
    }
  }
}
