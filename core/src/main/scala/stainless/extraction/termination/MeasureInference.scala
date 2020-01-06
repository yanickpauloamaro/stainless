/* Copyright 2009-2019 EPFL, Lausanne */

package stainless
package extraction
package termination

import stainless.termination.TerminationReport

import scala.collection.mutable.{Map => MutableMap, HashSet => MutableSet, ListBuffer => MutableList}

object optInferMeasures extends inox.FlagOptionDef("infer-measures", true)

trait MeasureInference
  extends CachingPhase
    with SimplyCachedSorts
    with IdentitySorts
    with SimpleFunctions
    with SimplyCachedFunctions { self =>

  val s: Trees
  val t: extraction.Trees
  import s._

  import context.{options, timers}

  val sizes: SizeFunctions { val trees: s.type } = new {
    val trees: s.type = self.s
  } with SizeFunctions

  override protected def getContext(symbols: s.Symbols) = TransformerContext(symbols, MutableMap.empty)

  protected case class TransformerContext(symbols: Symbols, measureCache: MutableMap[FunDef, Expr]) {
    val program = inox.Program(s)(symbols)

    val pipeline = TerminationChecker(program, self.context)(sizes)

    val sizeFunctions = sizes.getFunctions(symbols)

    final object transformer extends inox.transformers.TreeTransformer {
      override val s: self.s.type = self.s
      override val t: self.t.type = self.t

      override def transform(e: s.Expr): t.Expr = e match {
        case Decreases(v: Variable, body) if v.getType(symbols).isInstanceOf[ADTType] =>
          t.Decreases(transform(size(v)), transform(body))

        case Decreases(Tuple(ts), body) =>
          t.Decreases(t.Tuple(ts.map {
            case v: Variable if v.getType(symbols).isInstanceOf[ADTType] => transform(size(v))
            case e => transform(e)
          }), transform(body))

        case _ =>
          super.transform(e)
      }

      private def size(v: Variable): Expr = {
        require(v.getType(symbols).isInstanceOf[ADTType])
        val ADTType(id, tps) = v.getType(symbols)
        FunctionInvocation(sizes.fullSizeId(symbols.sorts(id)), tps, Seq(v)).setPos(v)
      }
    }

    def inferMeasure(original: FunDef): FunDef = measureCache.get(original) match {
      case Some(measure) =>
        original.copy(fullBody = exprOps.withMeasure(original.fullBody, Some(measure)))

      case None => try {
        val guarantee = timers.evaluators.termination.inference.run {
          pipeline.terminates(original)
        }

        val result = guarantee match {
          case pipeline.Terminates(_, Some(measure)) =>
            measureCache ++= pipeline.measureCache.get
            original.copy(fullBody = exprOps.withMeasure(original.fullBody, Some(measure)))

          case pipeline.Terminates(_, None) =>
            original

          case _ if exprOps.measureOf(original.fullBody).isDefined =>
            original

          case nt: pipeline.NonTerminating =>
            context.reporter.error(original.getPos, nt.asString)
            original

          case _ =>
            context.reporter.error(original.getPos, s"Could not infer measure for function ${original.id.asString}")
            original
        }

        annotate(result, guarantee)
      } catch {
        case FailedMeasureInference(fd, msg) =>
          context.reporter.error(fd.getPos, msg)
          original
      }
    }

    private def annotate(fd: FunDef, guarantee: pipeline.TerminationGuarantee): FunDef = {
      fd.copy(flags = fd.flags :+ TerminationStatus(status(guarantee)))
    }

    private def status(g: pipeline.TerminationGuarantee): TerminationReport.Status = g match {
      case pipeline.NoGuarantee      => TerminationReport.Unknown
      case pipeline.Terminates(_, _) => TerminationReport.Terminating
      case _                         => TerminationReport.NonTerminating
    }
  }

  override protected def extractFunction(context: TransformerContext, fd: s.FunDef): t.FunDef = {
    if (options.findOptionOrDefault(optInferMeasures)) {
      context.transformer.transform(context.inferMeasure(fd))
    } else {
      context.transformer.transform(fd)
    }
  }

  override protected def extractSymbols(context: TransformerContext, symbols: s.Symbols): t.Symbols = {
    val sizeFunctions = sizes.getFunctions(symbols).map(context.transformer.transform(_))
    registerFunctions(super.extractSymbols(context, symbols), sizeFunctions)
  }
}

object MeasureInference { self =>
  def apply(tr: Trees)(implicit ctx: inox.Context): ExtractionPipeline {
    val s: tr.type
    val t: tr.type
  } = new {
    override val s: tr.type = tr
    override val t: tr.type = tr
    override val context = ctx
  } with MeasureInference
}