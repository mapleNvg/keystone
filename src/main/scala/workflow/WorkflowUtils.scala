package workflow

import org.apache.spark.rdd.RDD

object WorkflowUtils {
  def instructionsToPipeline[A, B](instructions: Seq[Instruction]): Pipeline[A, B] = {
    val (newNodes, newDataDeps, newFitDeps, _) = instructions.indices.foldLeft(
      (Seq[Node](), Seq[Seq[Int]](), Seq[Option[Int]](), Map(Pipeline.SOURCE -> Pipeline.SOURCE))
    ) {
      case ((nodes, dataDeps, fitDeps, idMap), instruction) =>
        instructions(instruction) match {
          case est: EstimatorNode => (nodes, dataDeps, fitDeps, idMap)
          case transformer: TransformerNode => (nodes, dataDeps, fitDeps, idMap)
          case source: SourceNode =>
            (nodes :+ source, dataDeps :+ Seq(), fitDeps :+ None, idMap + (instruction -> nodes.length))
          case TransformerApplyNode(transformer, inputs) => {
            instructions(transformer) match {
              case transformerNode: TransformerNode => (
                nodes :+ transformerNode,
                dataDeps :+ inputs.map(idMap.apply),
                fitDeps :+ None,
                idMap + (instruction -> nodes.length))

              case EstimatorFitNode(est, estInputs) => (
                nodes :+ new DelegatingTransformerNode(
                  s"Fit[${instructions(est).asInstanceOf[EstimatorNode].label}]"),
                dataDeps :+ inputs.map(idMap.apply),
                fitDeps :+ Some(idMap(transformer)),
                idMap + (instruction -> nodes.length))

              case _ => throw new RuntimeException("Transformer apply instruction must point at a Transformer")
            }
          }
          case EstimatorFitNode(est, inputs) => (
            nodes :+ instructions(est).asInstanceOf[EstimatorNode],
            dataDeps :+ inputs.map(idMap.apply),
            fitDeps :+ None,
            idMap + (instruction -> nodes.length))
        }
    }

    new ConcretePipeline(newNodes, newDataDeps, newFitDeps, newNodes.length - 1)
  }

  /**
   * Linearizes a pipeline DAG into a Seq[Instruction]
   * by walking backwards from the sink in a depth-first manner
   */
  def pipelineToInstructions[A, B](pipeline: Pipeline[A, B]): Seq[Instruction] = {
    val nodes = pipeline.nodes
    val dataDeps = pipeline.dataDeps
    val fitDeps = pipeline.fitDeps
    val sink = pipeline.sink

    pipelineToInstructionsRecursion(sink, nodes, dataDeps, fitDeps, Map(Pipeline.SOURCE -> Pipeline.SOURCE), Seq())._2
  }

  def pipelineToInstructionsRecursion(
      current: Int,
      nodes: Seq[Node],
      dataDeps: Seq[Seq[Int]],
      fitDeps: Seq[Option[Int]],
      nodeIdToInstructionId: Map[Int, Int],
      instructions: Seq[Instruction]
    ): (Map[Int, Int], Seq[Instruction]) = {

    val (newIdMap, newInstructions) = (fitDeps(current) ++ dataDeps(current))
      .foldLeft((nodeIdToInstructionId, instructions)) {
      case ((curIdMap, curInstructions), dep)
        if !curIdMap.contains(dep) && dep != Pipeline.SOURCE =>
        pipelineToInstructionsRecursion(dep, nodes, dataDeps, fitDeps, curIdMap, curInstructions)
      case ((curIdMap, curInstructions), _) => (curIdMap, curInstructions)
    }

    val dataInputs = dataDeps(current).map(newIdMap.apply)

    nodes(current) match {
      case source: SourceNode =>
        (newIdMap + (current -> newInstructions.length), newInstructions :+ source)

      case transformer: TransformerNode =>
        (newIdMap + (current -> (newInstructions.length + 1)),
          newInstructions ++ Seq(transformer, TransformerApplyNode(newInstructions.length, dataInputs)))

      case delTransformer: DelegatingTransformerNode =>
        val transformerId = newIdMap(fitDeps(current).get)
        (newIdMap + (current -> newInstructions.length),
          newInstructions :+ TransformerApplyNode(transformerId, dataInputs))

      case est: EstimatorNode =>
        (newIdMap + (current -> (newInstructions.length + 1)),
          newInstructions ++ Seq(est, EstimatorFitNode(newInstructions.length, dataInputs)))
    }
  }

  /**
   * Get the set of all instruction ids depending on the result of a given instruction
   * (including transitive dependencies)
   *
   * @param id
   * @param instructions
   * @return
   */
  def getChildren(id: Int, instructions: Seq[Instruction]): Set[Int] = {
    val children = scala.collection.mutable.Set[Int]()

    // Todo: Can optimize by looking at only instructions > id
    // Todo: Could also make a more optimized implementation
    // by calculating it for all instructions at once
    for ((instruction, index) <- instructions.zipWithIndex) {
      if (instruction.getDependencies.exists { x =>
        children.contains(x) || x == id
      }) {
        children.add(index)
      }
    }

    children.toSet
  }

  /**
   * Get a seq of all instruction ids with the result of a given instruction in their
   * direct dependencies. (Does not include transitive dependencies)
   *
   * Note: This includes ids as many times in the seq as they have dependencies on
   * the given instruction
   *
   * @param id
   * @param instructions
   * @return
   */
  def getImmediateChildren(id: Int, instructions: Seq[Instruction]): Seq[Int] = {
    // Todo: Can optimize by looking at only instructions > id
    // Todo: Could also make a more optimized implementation
    // by calculating it for all instructions at once
    instructions.indices.foldLeft(Seq[Int]()) {
      case (children, i) => children ++ instructions(i).getDependencies.filter(_ == id).map(x => i)
    }
  }

  /**
   * Get the set of all instruction ids on whose results a given instruction depends
   *
   * @param id
   * @param instructions
   * @return
   */
  def getParents(id: Int, instructions: Seq[Instruction]): Set[Int] = {
    // Todo: Could make a more optimized implementation
    // by calculating it for all instructions at once
    val dependencies = if (id != Pipeline.SOURCE) instructions(id).getDependencies else Seq()
    dependencies.map {
      parent => getParents(parent, instructions) + parent
    }.fold(Set())(_ union _)
  }

  def numPerPartition[T](rdd: RDD[T]): Map[Int, Int] = {
    rdd.mapPartitionsWithIndex {
      case (id, partition) => Iterator.single((id, partition.length))
    }.collect().toMap
  }

  /**
   * Remove the given set of instruction indices from the pipeline, updating all dependency pointers
   * appropriately. Errors when the removed instructions have dependencies on them
   *
   * @param setToRemove
   * @param instructions
   * @return A tuple containing the new instructions, and
   *         a mapping of old dependency index to new dependency index
   */
  def removeInstructions(setToRemove: Set[Int], instructions: Seq[Instruction]): (Seq[Instruction], Int => Int) = {
    val offsets = collection.mutable.Map[Int, Int]()
    for (indexToRemove <- setToRemove;
         indexToOffset <- (indexToRemove + 1) until instructions.length) {
      offsets(indexToOffset) = offsets.getOrElse(indexToOffset, 0) + 1
    }

    val newInstructions = instructions.zipWithIndex.collect {
      case (instruction, i) if !setToRemove.contains(i) =>
        instruction.mapDependencies(oldDep =>
          if (setToRemove.contains(oldDep)) {
            throw new RuntimeException("WorkflowUtils.removeInstructions attempted to break a dependency")
          } else {
            oldDep - offsets.getOrElse(oldDep, 0)
          }
        )
    }

    val oldToNewIndexMapping = (i: Int) => i - offsets.getOrElse(i, 0)

    (newInstructions, oldToNewIndexMapping)
  }

  /**
   * This method first replaces all dependencies on specific instructions with a different int, effectively
   * disconnecting them from the pipeline. It then removes the disconnected instructions from the pipeline.
   *
   * It is useful if you want to replace a section of the pipeline, by disconnecting and removing a section,
   * and setting endpoints to use for splicing in new instructions.
   *
   * @param dependencyReplacement A map of (instruction index to remove) to
   *                              (int to be swapped in for all previous dependencies on it)
   * @param instructions The pipeline to remove from.
   * @return A tuple containing the new instructions, and
   *         a mapping of old dependency index to new dependency index
   */
  def disconnectAndRemoveInstructions(dependencyReplacement: Map[Int, Int], instructions: Seq[Instruction])
  : (Seq[Instruction], Int => Int) = {
    val removeResult = removeInstructions(dependencyReplacement.keys.toSet, instructions.map(_.mapDependencies {
      i => dependencyReplacement.getOrElse(i, i)
    }))

    (removeResult._1, (i: Int) => dependencyReplacement.getOrElse(i, removeResult._2(i)))
  }

  /**
   * Splice a sequence of instructions into an existing sequence of instructions.
   *
   * @param splice The sequence to insert
   * @param instructions The existing sequence to have instructions spliced into
   * @param spliceSourceMap A mapping from dependencies in the instructions being spliced to instructions in the
   *                        existing sequence.
   * @param spliceSink The dependency in the existing sequence to map to the sink of the instructions
   *                   being spliced
   * @return A tuple containing the new instructions, and
   *         a mapping of old dependency index to new dependency index
   */
  def spliceInstructions(
    splice: Seq[Instruction],
    instructions: Seq[Instruction],
    spliceSourceMap: Map[Int, Int],
    spliceSink: Int
  ): (Seq[Instruction], Int => Int) = {
    val spliceIndex = spliceSourceMap.values.max + 1
    val indicesDependingOnSpliceSink = instructions.indices
      .filter(instructions(_).getDependencies.contains(spliceSink)) :+ instructions.length
    require(spliceIndex <= indicesDependingOnSpliceSink.min,
      "Can't splice the instruction set because the splice " +
        "depends on instructions that come after where it is supposed to be used.")

    val start = instructions.take(spliceIndex)
    val splicedInstructions = splice.map(_.mapDependencies { dep =>
      spliceSourceMap.getOrElse(dep, dep + start.length)
    })
    val end = instructions.drop(spliceIndex).map(_.mapDependencies { dep =>
      if (spliceSink == dep) {
        start.length + splicedInstructions.length - 1
      } else if (dep >= spliceIndex) {
        dep + splicedInstructions.length
      } else {
        dep
      }
    })

    val oldToNewIndexMapping = (i: Int) => {
      if (spliceSink == i) {
        start.length + splicedInstructions.length - 1
      } else if (i >= spliceIndex) {
        i + splicedInstructions.length
      } else {
        i
      }
    }

    val newInstructions = start ++ splicedInstructions ++ end

    (newInstructions, oldToNewIndexMapping)
  }
}
