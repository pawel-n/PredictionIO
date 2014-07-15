package io.prediction.workflow

import io.prediction.api.Params
import io.prediction.api.EmptyParams
import io.prediction.core.Doer
import scala.language.existentials

import io.prediction.core.BaseEvaluator
import io.prediction.core.BaseEngine
import io.prediction.core.BaseAlgorithm2
import io.prediction.core.LModelAlgorithm
import io.prediction.java.JavaUtils

import io.prediction.api.LAlgorithm

import com.github.nscala_time.time.Imports.DateTime

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf

import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream
import scala.collection.JavaConversions._
import java.lang.{ Iterable => JIterable }
import java.util.{ Map => JMap }

import io.prediction.core._
import io.prediction._

import org.apache.spark.rdd.RDD

import scala.reflect.Manifest

import io.prediction.java._

// skipOpt = true: use slow parallel model for prediction, requires one extra
// join stage.
class AlgoServerWrapper2[Q, P, A](
    val algos: Array[_ <: BaseAlgorithm2[_,_,_,Q,P]],
    val serving: BaseServing[_, Q, P],
    val skipOpt: Boolean = false,
    val verbose: Boolean = false)
extends Serializable {
  
  // Use algo.predictBase
  def onePassPredict(
    modelIter: Iterator[(AI, Any)], 
    queryActualIter: Iterator[(Q, A)])
  : Iterator[(Q, P, A)] = {
    val models = modelIter.toSeq.sortBy(_._1).map(_._2)

    queryActualIter.map{ case(query, actual) => {
      val predictions = algos.zipWithIndex.map {
        case (algo, i) => algo.predictBase(models(i), query)
      }
      val prediction = serving.serveBase(query, predictions)

      (query, prediction, actual)
    }}
  }

  // Use algo.predictBase
  def predictLocalModel(models: Seq[RDD[Any]], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    println("predictionLocalModel")
    val sc = models.head.context
    // must have only one partition since we need all models per feature.
    // todo: duplicate indexedModel into multiple partition.
    val reInput = input.coalesce(numPartitions = 1)

    val indexedModels: Seq[RDD[(AI, Any)]] = models.zipWithIndex
      .map { case (rdd, ai) => rdd.map(m => (ai, m)) }

    val rddModel: RDD[(AI, Any)] = sc.union(indexedModels)
      .coalesce(numPartitions = 1)

    rddModel.zipPartitions(reInput)(onePassPredict)
  }

  // Use algo.batchPredictBase
  def predictParallelModel(models: Seq[Any], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    if (verbose) { 
      println("predictionParallelModel")
    }

    // Prefixed with "i" stands for "i"ndexed
    val iInput: RDD[(QI, (Q, A))] = input.zipWithUniqueId.map(_.swap)

    val iQuery: RDD[(QI, Q)] = iInput.map(e => (e._1, e._2._1))
    val sc = input.context

    // Each algo/model is run independely.
    val iAlgoPredictionSeq: Seq[RDD[(QI, (AI, P))]] = models
      .zipWithIndex
      .map { case (model, ai) => {
        algos(ai)
          .batchPredictBase(model, iQuery)
          .map{ case (fi, p) => (fi, (ai, p)) }
      }}

    val iAlgoPredictions: RDD[(QI, Seq[P])] = sc
      .union(iAlgoPredictionSeq)
      .groupByKey
      .mapValues { _.toSeq.sortBy(_._1).map(_._2) }

    val joined: RDD[(QI, (Seq[P], (Q, A)))] = iAlgoPredictions.join(iInput)
    
    if (verbose) {
      println("predictionParallelModel.before combine")
      joined.collect.foreach {  case(fi, (ps, (q, a))) => {
        val pstr = DebugWorkflow.debugString(ps)
        val qstr = DebugWorkflow.debugString(q)
        val astr = DebugWorkflow.debugString(a)
        //e => println(DebugWorkflow.debugString(e))
        println(s"I: $fi Q: $qstr A: $astr Ps: $pstr")
      }}
    }

    val combined: RDD[(QI, (Q, P, A))] = joined
    .mapValues{ case (predictions, (query, actual)) => {
      val prediction = serving.serveBase(query, predictions)
      (query, prediction, actual)
    }}

    if (verbose) {
      println("predictionParallelModel.after combine")
      combined.collect.foreach { case(qi, (q, p, a)) => {
        val qstr = DebugWorkflow.debugString(q)
        val pstr = DebugWorkflow.debugString(p)
        val astr = DebugWorkflow.debugString(a)
        println(s"I: $qi Q: $qstr A: $astr P: $pstr")
      }}
    }

    combined.values
  }

  def predict(models: Seq[Any], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    // We split the prediction into multiple mode.
    // If all algo support using local model, we will run against all of them
    // in one pass.
    val someNonLocal = algos
      //.exists(!_.isInstanceOf[LAlgorithm[_, _, _, Q, P]])
      .exists(!_.isInstanceOf[LModelAlgorithm[_, Q, P]])

    if (!someNonLocal && !skipOpt) {
      // When algo is local, the model is the only element in RDD[M].
      val localModelAlgo = algos
        .map(_.asInstanceOf[LModelAlgorithm[_, Q, P]])
        //.map(_.asInstanceOf[LAlgorithm[_, _, _, Q, P]])
      val rddModels = localModelAlgo.zip(models)
        .map{ case (algo, model) => algo.getModel(model) }
      predictLocalModel(rddModels, input)
    } else {
      predictParallelModel(models, input)
    }
  }
}

class MetricsWrapper[MP <: Params, DP, MU, MR, MMR <: AnyRef](
    //TDP <: Params, VDP <: BaseParams, VU, VR, CVR <: AnyRef](
  val metrics: BaseMetrics[_,DP,_,_,_,MU,MR,MMR]) 
extends Serializable {
  def computeSet(input: (DP, Iterable[MU])): (DP, MR) = {
    val results = metrics.computeSetBase(input._1, input._2.toSeq)
    (input._1, results)
  }

  def computeMultipleSets(input: Array[(DP, MR)]): MMR = {
    // maybe sort them.
    //val data = input.map(e => (e._1, e._2))
    metrics.computeMultipleSetsBase(input)
  }
}

object APIDebugWorkflow {
  def run[
      DSP <: Params,
      PP <: Params,
      SP <: Params,
      MP <: Params,
      DP,
      TD,
      PD,
      Q,
      P,
      A,
      MU : Manifest,
      MR : Manifest,
      MMR <: AnyRef : Manifest
      ](
    batch: String = "",
    dataSourceClass: Class[_ <: BaseDataSource[DSP, DP, TD, Q, A]] = null,
    dataSourceParams: Params = EmptyParams(),
    preparatorClass: Class[_ <: BasePreparator[PP, TD, PD]] = null,
    preparatorParams: Params = EmptyParams(),
    algorithmClassMap: 
      Map[String, Class[_ <: BaseAlgorithm2[_ <: Params, PD, _, Q, P]]] = null,
    algorithmParamsList: Seq[(String, Params)] = null,
    servingClass: Class[_ <: BaseServing[SP, Q, P]] = null,
    servingParams: Params = EmptyParams(),
    metricsClass: Class[_ <: BaseMetrics[MP, DP, Q, P, A, MU, MR, MMR]] = null,
    metricsParams: Params = EmptyParams() 
  ) {
    println("APIDebugWorkflow.run")
    println("Start spark context")
    val sc = WorkflowContext(batch)

    if (dataSourceClass == null || dataSourceParams == null) {
      println("Dataprep Class or Params is null. Stop here");
      return
    }
    
    println("Data Source")
    val dataSource = Doer(dataSourceClass, dataSourceParams)

    val evalParamsDataMap
    : Map[EI, (DP, TD, RDD[(Q, A)])] = dataSource
      .readBase(sc)
      .zipWithIndex
      .map(_.swap)
      .toMap

    val localParamsSet: Map[EI, DP] = evalParamsDataMap.map { 
      case(ei, e) => (ei -> e._1)
    }

    val evalDataMap: Map[EI, (TD, RDD[(Q, A)])] = evalParamsDataMap.map {
      case(ei, e) => (ei -> (e._2, e._3))
    }

    println(s"Number of training set: ${localParamsSet.size}")

    evalDataMap.foreach{ case (ei, data) => {
      val (trainingData, validationData) = data
      println(s"TrainingData $ei")
      println(DebugWorkflow.debugString(trainingData))
      println(s"ValidationData $ei")
      validationData.collect.map(DebugWorkflow.debugString).foreach(println)
    }}

    println("Data source complete")
    
    if (preparatorClass == null) {
      println("Preparator is null. Stop here")
      return
    }

    println("Preparator")
    val preparator = Doer(preparatorClass, preparatorParams)
    
    val evalPreparedMap: Map[EI, PD] = evalDataMap
    .map{ case (ei, data) => (ei, preparator.prepareBase(sc, data._1)) }

    evalPreparedMap.foreach{ case (ei, pd) => {
      println(s"Prepared $ei")
      println(DebugWorkflow.debugString(pd))
    }}
  
    println("Preparator complete")
    
    if (algorithmClassMap == null) {
      println("Algo is null. Stop here")
      return
    }

    println("Algo model construction")

    // Instantiate algos
    val algoInstanceList: Array[BaseAlgorithm2[_, PD, _, Q, P]] =
    //val algoInstanceList: Array[BaseAlgorithm2[_, _, _, Q, P]] =
    algorithmParamsList
      .map { 
        case (algoName, algoParams) => 
          Doer(algorithmClassMap(algoName), algoParams)
      }
      .toArray

    // Model Training
    // Since different algo can have different model data, have to use Any.
    // We need to use par here. Since this process allows algorithms to perform
    // "Actions" 
    // (see https://spark.apache.org/docs/latest/programming-guide.html#actions)
    // on RDDs. Meaning that algo may kick off a spark pipeline to for training.
    // Hence, we parallelize this process.
    val evalAlgoModelMap: Map[EI, Seq[(AI, Any)]] = evalPreparedMap
    .par
    .map { case (ei, preparedData) => {

      val algoModelSeq: Seq[(AI, Any)] = algoInstanceList
      .zipWithIndex
      .map { case (algo, index) => {
        val model: Any = algo.trainBase(sc, preparedData)
        (index, model)
      }}

      (ei, algoModelSeq)
    }}
    .seq
    .toMap

    evalAlgoModelMap.map{ case(ei, aiModelSeq) => {
      aiModelSeq.map { case(ai, model) => {
        println(s"Model ei: $ei ai: $ei")
        println(DebugWorkflow.debugString(model))
      }}
    }}

    if (servingClass == null) {
      println("Serving is null. Stop here")
      return
    }
    val serving = Doer(servingClass, servingParams)
    //val server = serverClass.newInstance

    println("Algo prediction")

    val evalPredictionMap
    : Map[EI, RDD[(Q, P, A)]] = evalDataMap.map { case (ei, data) => {
      val validationData: RDD[(Q, A)] = data._2
      val algoModel: Seq[Any] = evalAlgoModelMap(ei)
        .sortBy(_._1)
        .map(_._2)

      val algoServerWrapper = new AlgoServerWrapper2[Q, P, A](
        algoInstanceList, serving, skipOpt = false, verbose = true)
      (ei, algoServerWrapper.predict(algoModel, validationData))
    }}
    .toMap

    evalPredictionMap.foreach{ case(ei, fpaRdd) => {
      println(s"Prediction $ei $fpaRdd")
      fpaRdd.collect.foreach{ case(f, p, a) => {
        val fs = DebugWorkflow.debugString(f)
        val ps = DebugWorkflow.debugString(p)
        val as = DebugWorkflow.debugString(a)
        println(s"F: $fs P: $ps A: $as")
      }}

    }}
    
    if (metricsClass == null) {
      println("Metrics is null. Stop here")
      return
    }

    val metrics = Doer(metricsClass, metricsParams)
    
    // Metrics Unit
    val evalMetricsUnitMap: Map[Int, RDD[MU]] =
      evalPredictionMap.mapValues(_.map(metrics.computeUnitBase))

    evalMetricsUnitMap.foreach{ case(i, e) => {
      println(s"MetricsUnit: i=$i e=$e")
    }}
    
    // Metrics Set
    val metricsWrapper = new MetricsWrapper(metrics)

    val evalMetricsResultsMap
    : Map[EI, RDD[(DP, MR)]] = evalMetricsUnitMap
    .map{ case (ei, metricsUnits) => {
      val metricsResults
      : RDD[(DP, MR)] = metricsUnits
        .coalesce(numPartitions=1)
        .glom()
        .map(e => (localParamsSet(ei), e.toIterable))
        .map(metricsWrapper.computeSet)

      (ei, metricsResults)
    }}

    evalMetricsResultsMap.foreach{ case(ei, e) => {
      println(s"MetricsResults $ei $e")
    }}

    val multipleMetricsResults: RDD[MMR] = sc
      .union(evalMetricsResultsMap.values.toSeq)
      .coalesce(numPartitions=1)
      .glom()
      .map(metricsWrapper.computeMultipleSets)

    val metricsOutput: Array[MMR] = multipleMetricsResults.collect

    println(s"DataSourceParams: $dataSourceParams")
    println(s"PreparatorParams: $preparatorParams")
    algorithmParamsList.zipWithIndex.foreach { case (ap, ai) => {
      println(s"Algo: $ai Name: ${ap._1} Params: ${ap._2}")
    }}
    println(s"ServingParams: $servingParams")
    println(s"MetricsParams: $metricsParams")

    metricsOutput foreach { println }
    
    println("APIDebugWorkflow.run completed.") 
  }
}