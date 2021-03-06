package workflow.graph

import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
 * Transformers are operators that may be applied both to single input items and to RDDs of input items.
 * They may be chained together, along with [[Estimator]]s and [[LabelEstimator]]s, to produce complex
 * pipelines.
 *
 * Transformer extends [[Pipeline]], meaning that its publicly exposed methods for transforming data
 * and chaining are implemented there.
 *
 * @tparam A input item type the transformer takes
 * @tparam B output item type the transformer produces
 */
abstract class Transformer[A, B : ClassTag] extends TransformerOperator with Chainable[A, B] {
  private[graph] override def toPipeline: Pipeline[A, B] = new Pipeline(
    executor = new GraphExecutor(Graph(
      sources = Set(SourceId(0)),
      sinkDependencies = Map(SinkId(0) -> NodeId(0)),
      operators = Map(NodeId(0) -> this),
      dependencies = Map(NodeId(0) -> Seq(SourceId(0)))
    )),
    source = SourceId(0),
    sink = SinkId(0)
  )

  /**
   * The application of this Transformer to a single input item.
   * This method MUST be overridden by ML developers.
   *
   * @param in  The input item to pass into this transformer
   * @return  The output value
   */
  def apply(in: A): B

  /**
   * The application of this Transformer to an RDD of input items.
   * This method may optionally be overridden by ML developers.
   *
   * @param in The bulk RDD input to pass into this transformer
   * @return The bulk RDD output for the given input
   */
  def apply(in: RDD[A]): RDD[B] = in.map(apply)

  final override private[graph] def singleTransform(inputs: Seq[DatumExpression]): Any = {
    apply(inputs.head.get.asInstanceOf[A])
  }

  final override private[graph] def batchTransform(inputs: Seq[DatasetExpression]): RDD[_] = {
    apply(inputs.head.get.asInstanceOf[RDD[A]])
  }
}

object Transformer {
  /**
   * This constructor takes a function and returns a Transformer that maps it over the input RDD
   *
   * @param f The function to apply to every item in the RDD being transformed
   * @tparam I input type of the transformer
   * @tparam O output type of the transformer
   * @return Transformer that applies the given function to all items in the RDD
   */
  def apply[I, O : ClassTag](f: I => O): Transformer[I, O] = new Transformer[I, O] {
    override def apply(in: RDD[I]): RDD[O] = in.map(f)
    override def apply(in: I): O = f(in)
  }
}
