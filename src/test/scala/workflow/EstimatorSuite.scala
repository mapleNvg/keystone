package workflow

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.scalatest.FunSuite
import pipelines.{LocalSparkContext, Logging}

class EstimatorSuite extends FunSuite with LocalSparkContext with Logging {
  test("estimator withData") {
    sc = new SparkContext("local", "test")

    val intEstimator = new Estimator[Int, Int] {
      protected def fit(data: RDD[Int]): Transformer[Int, Int] = {
        val first = data.first()
        Transformer(_ => first)
      }
    }

    val trainData = sc.parallelize(Seq(32, 94, 12))
    val testData = sc.parallelize(Seq(42, 58, 61))

    val pipeline = intEstimator.withData(trainData)
    assert(pipeline.apply(testData).collect().toSeq === Seq(32, 32, 32))
  }
}
