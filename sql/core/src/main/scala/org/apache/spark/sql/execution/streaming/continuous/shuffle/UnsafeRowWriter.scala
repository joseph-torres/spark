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

package org.apache.spark.sql.execution.streaming.continuous.shuffle

import org.apache.spark.Partitioner
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.sql.catalyst.expressions.UnsafeRow

/**
 * A [[ContinuousShuffleWriter]] sending data to [[UnsafeRowReceiver]] instances.
 *
 * @param writerId The partition ID of this writer.
 * @param outputPartitioner The partitioner on the reader side of the shuffle.
 * @param endpoints The [[UnsafeRowReceiver]] endpoints to write to. Indexed by partition ID within
 *                  outputPartitioner.
 */
class UnsafeRowWriter(
    writerId: Int,
    outputPartitioner: Partitioner,
    endpoints: Array[RpcEndpointRef]) extends ContinuousShuffleWriter {

  if (outputPartitioner.numPartitions != 1) {
    throw new IllegalArgumentException("multiple readers not yet supported")
  }

  if (outputPartitioner.numPartitions != endpoints.length) {
    throw new IllegalArgumentException(s"partitioner size ${outputPartitioner.numPartitions} did " +
      s"not match endpoint count ${endpoints.length}")
  }

  def write(epoch: Iterator[UnsafeRow]): Unit = {
    while (epoch.hasNext) {
      val row = epoch.next()
      endpoints(outputPartitioner.getPartition(row)).ask[Unit](ReceiverRow(writerId, row))
    }

    endpoints.foreach(_.ask[Unit](ReceiverEpochMarker(writerId)))
  }
}
