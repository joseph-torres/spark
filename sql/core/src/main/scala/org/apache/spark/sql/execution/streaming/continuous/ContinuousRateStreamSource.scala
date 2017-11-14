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

package org.apache.spark.sql.execution.streaming.continuous

import scala.collection.JavaConverters._

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.streaming.{LongOffset, Offset}
import org.apache.spark.sql.sources.v2.{ContinuousReadSupport, DataSourceV2, DataSourceV2Options}
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.types.{LongType, StructField, StructType, TimestampType}

object ContinuousRateStreamSource {
  val NUM_PARTITIONS = "numPartitions"
  val ROWS_PER_SECOND = "rowsPerSecond"
}

class ContinuousRateStreamSource extends DataSourceV2 with ContinuousReadSupport {
  override def createContinuousReader(
      offset: java.util.Optional[Offset],
      schema: java.util.Optional[StructType],
      options: DataSourceV2Options): ContinuousRateStreamReader = {
    new ContinuousRateStreamReader(options)
  }

  def commit(end: Offset): Unit = {}

  /** Stop this source and free any resources it has allocated. */
  def stop(): Unit = {}
}

class ContinuousRateStreamReader(options: DataSourceV2Options)
  extends DataSourceV2Reader with ContinuousReader {
  override def readSchema(): StructType = {
    StructType(
        StructField("timestamp", TimestampType, false) ::
        StructField("value", LongType, false) :: Nil)
  }

  override def createReadTasks(): java.util.List[ReadTask[Row]] = {
    val numPartitions = options.get(ContinuousRateStreamSource.NUM_PARTITIONS).orElse("2").toInt
    val rowsPerSecond = options.get(ContinuousRateStreamSource.ROWS_PER_SECOND).orElse("6").toLong

    val start = 0L
    val perPartitionRate = rowsPerSecond.toDouble / numPartitions.toDouble

    Range(0, numPartitions).map { n =>
      // Have each partition handle a different n mod numPartitions slice.
      RateStreamReadTask(start, n, numPartitions, perPartitionRate).asInstanceOf[ReadTask[Row]]
    }.asJava
  }
}

case class RateStreamReadTask(
    startValue: Long, partitionIndex: Int, increment: Long, rowsPerSecond: Double)
  extends ReadTask[Row] {
  override def createDataReader(): DataReader[Row] =
    new RateStreamDataReader(startValue, partitionIndex, increment, rowsPerSecond.toLong)
}

class RateStreamDataReader(
    startValue: Long, partitionIndex: Int, increment: Long, rowsPerSecond: Long)
  extends ContinuousDataReader[Row] {

  private var nextReadTime = 0L
  private var numReadRows = 0L

  private var currentValue = startValue + partitionIndex
  private var currentRow: Row = null

  override def next(): Boolean = {
    // Set the timestamp for the first time.
    if (currentRow == null) nextReadTime = System.currentTimeMillis() + 1000

    if (numReadRows == rowsPerSecond) {
      // Sleep until we reach the next second.
      while (System.currentTimeMillis < nextReadTime) {
        Thread.sleep(nextReadTime - System.currentTimeMillis)
      }
      numReadRows = 0
      nextReadTime += 1000
    }

    val nextReadTimeStamp =
      DateTimeUtils.toJavaTimestamp(DateTimeUtils.fromMillis(nextReadTime))
    currentRow = Row(
      DateTimeUtils.toJavaTimestamp(DateTimeUtils.fromMillis(System.currentTimeMillis)),
      currentValue)
    currentValue += increment
    numReadRows += 1

    true
  }

  override def get: Row = currentRow

  override def close(): Unit = {}

  // We use the value corresponding to partition 0 as the global offset.
  override def getOffset(): Offset = LongOffset(currentValue - partitionIndex)
}
