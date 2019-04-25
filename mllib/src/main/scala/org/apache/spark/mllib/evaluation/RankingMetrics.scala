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

package org.apache.spark.mllib.evaluation

import java.{lang => jl}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import org.apache.spark.annotation.Since
import org.apache.spark.api.java.{JavaRDD, JavaSparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD

/**
 * Evaluator for ranking algorithms.
 *
 * Java users should use `RankingMetrics$.of` to create a [[RankingMetrics]] instance.
 *
 * @param predictionAndLabels an RDD of (predicted ranking, ground truth set) pairs.
 */
@Since("1.2.0")
class RankingMetrics[T: ClassTag](predictionAndLabels: RDD[(Array[T], Array[T])])
  extends Logging with Serializable {

  /**
   * Compute the average precision of all the queries, truncated at ranking position k.
   *
   * If for a query, the ranking algorithm returns n (n is less than k) results, the precision
   * value will be computed as #(relevant items retrieved) / k. This formula also applies when
   * the size of the ground truth set is less than k.
   *
   * If a query has an empty ground truth set, zero will be used as precision together with
   * a log warning.
   *
   * See the following paper for detail:
   *
   * IR evaluation methods for retrieving highly relevant documents. K. Jarvelin and J. Kekalainen
   *
   * @param k the position to compute the truncated precision, must be positive
   * @return the average precision at the first k ranking positions
   */
  @Since("1.2.0")
  def precisionAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, lab) =>
      countRelevantItemRatio(pred, lab, k, k)
    }.mean()
  }

  /**
   * Returns the mean average precision (MAP) of all the queries.
   * If a query has an empty ground truth set, the average precision will be zero and a log
   * warning is generated.
   */
  lazy val meanAveragePrecision: Double = {
    predictionAndLabels.map { case (pred, lab) =>
      computeMeanAveragePrecisonAtK(pred, lab, pred.length)
    }.mean()
  }

  /**
   * Returns mean average precision truncated at position k.
   *
   * If a query has an empty ground truth set, the value will be zero and a log
   * warning is generated.
   *
   * @param pred predicted ranking
   * @param lab ground truth
   * @param k use the top k predicted ranking, must be positive
   * @return mean average precision of first k ranking positions
   */
  private def computeMeanAveragePrecisonAtK(pred: Array[T],
                                            lab: Array[T],
                                            k: Int): Double = {
    val labSet = lab.toSet
    if (labSet.nonEmpty) {
      var i = 0
      var cnt = 0
      var precSum = 0.0
      val n = math.min(pred.length, k)
      while (i < n) {
        if (labSet.contains(pred(i))) {
          cnt += 1
          precSum += cnt.toDouble / (i + 1)
        }
        i += 1
      }
      precSum / labSet.size
    } else {
      logWarning("Empty ground truth set, check input data")
      0.0
    }
  }

  /**
   * Compute the average NDCG value of all the queries, truncated at ranking position k.
   * The discounted cumulative gain at position k is computed as:
   *    sum,,i=1,,^k^ (2^{relevance of ''i''th item}^ - 1) / log(i + 1),
   * and the NDCG is obtained by dividing the DCG value on the ground truth set. In the current
   * implementation, the relevance value is binary.

   * If a query has an empty ground truth set, zero will be used as ndcg together with
   * a log warning.
   *
   * See the following paper for detail:
   *
   * IR evaluation methods for retrieving highly relevant documents. K. Jarvelin and J. Kekalainen
   *
   * @param k the position to compute the truncated ndcg, must be positive
   * @return the average ndcg at the first k ranking positions
   */
  @Since("1.2.0")
  def ndcgAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, lab) =>
      val labSet = lab.toSet

      if (labSet.nonEmpty) {
        val labSetSize = labSet.size
        val n = math.min(math.max(pred.length, labSetSize), k)
        var maxDcg = 0.0
        var dcg = 0.0
        var i = 0
        while (i < n) {
          // Base of the log doesn't matter for calculating NDCG,
          // if the relevance value is binary.
          val gain = 1.0 / math.log(i + 2)
          if (i < pred.length && labSet.contains(pred(i))) {
            dcg += gain
          }
          if (i < labSetSize) {
            maxDcg += gain
          }
          i += 1
        }
        dcg / maxDcg
      } else {
        logWarning("Empty ground truth set, check input data")
        0.0
      }
    }.mean()
  }

  /**
   * Compute the average recall of all the queries, truncated at ranking position k.
   *
   * If for a query, the ranking algorithm returns n results, the recall value will be
   * computed as #(relevant items retrieved) / #(ground truth set). This formula
   * also applies when the size of the ground truth set is less than k.
   *
   * If a query has an empty ground truth set, zero will be used as recall together with
   * a log warning.
   *
   * See the following paper for detail:
   *
   * IR evaluation methods for retrieving highly relevant documents. K. Jarvelin and J. Kekalainen
   *
   * @param k the position to compute the truncated recall, must be positive
   * @return the average recall at the first k ranking positions
   */
  @Since("3.0.0")
  def recallAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, lab) =>
      countRelevantItemRatio(pred, lab, k, lab.toSet.size)
    }.mean()
  }

  /**
   * Returns the relevant item ratio computed as #(relevant items retrieved) / denominator.
   * If a query has an empty ground truth set, the value will be zero and a log
   * warning is generated.
   *
   * @param pred predicted ranking
   * @param lab ground truth
   * @param k use the top k predicted ranking, must be positive
   * @param denominator the denominator of ratio
   * @return relevant item ratio at the first k ranking positions
   */
  private def countRelevantItemRatio(pred: Array[T],
                                     lab: Array[T],
                                     k: Int,
                                     denominator: Int): Double = {
    val labSet = lab.toSet
    if (labSet.nonEmpty) {
      val n = math.min(pred.length, k)
      var i = 0
      var cnt = 0
      while (i < n) {
        if (labSet.contains(pred(i))) {
          cnt += 1
        }
        i += 1
      }
      cnt.toDouble / denominator
    } else {
      logWarning("Empty ground truth set, check input data")
      0.0
    }
  }

  /**
   * Compute the mean average precision (MAP) of all the queries, truncated at ranking position k.
   *
   * If for a query, the ranking algorithm returns n (n is less than k) results, the MAP value will
   * be computed as MAP at n.
   *
   * If a query has an empty ground truth set, the average precision will be zero and a log
   * warning is generated.
   */
  @Since("3.0.0")
  def meanAveragePrecisionAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, lab) =>
      computeMeanAveragePrecisonAtK(pred, lab, k)
    }.mean()
  }
}

object RankingMetrics {

  /**
   * Creates a [[RankingMetrics]] instance (for Java users).
   * @param predictionAndLabels a JavaRDD of (predicted ranking, ground truth set) pairs
   */
  @Since("1.4.0")
  def of[E, T <: jl.Iterable[E]](predictionAndLabels: JavaRDD[(T, T)]): RankingMetrics[E] = {
    implicit val tag = JavaSparkContext.fakeClassTag[E]
    val rdd = predictionAndLabels.rdd.map { case (predictions, labels) =>
      (predictions.asScala.toArray, labels.asScala.toArray)
    }
    new RankingMetrics(rdd)
  }
}
