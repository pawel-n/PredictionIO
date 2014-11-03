package io.prediction.examples.friendrecommendation

import io.prediction.controller._

// For random algorithm
import scala.util.Random

class RandomAlgorithm (val ap: FriendRecommendationAlgoParams)
extends LAlgorithm[FriendRecommendationAlgoParams, FriendRecommendationTrainingData,
  RandomModel, FriendRecommendationQuery, FriendRecommendationPrediction] {
  override
  def train(pd: FriendRecommendationTrainingData): RandomModel = {
    new RandomModel() 
  }

  override
  def predict(model: RandomModel, query: FriendRecommendationQuery): FriendRecommendationPrediction = {
    val randomConfidence = Random.nextDouble
    val acceptance = randomConfidence > ap.threshold
    new FriendRecommendationPrediction(randomConfidence, acceptance)
  }
}
