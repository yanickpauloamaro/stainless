/* Copyright 2009-2018 EPFL, Lausanne */

package stainless
package extraction
package inlining

trait Trees extends extraction.Trees { self =>

  case object Inline extends Flag("inline", Seq())
  case object InlineOnce extends Flag("inlineOnce", Seq())
  case object Synthetic extends Flag("synthetic", Seq())

  override def extractFlag(name: String, args: Seq[Any]): Flag = (name, args) match {
    case ("inline", Seq()) => Inline
    case ("inlineOnce", Seq()) => InlineOnce
    case _ => super.extractFlag(name, args)
  }

  override def getDeconstructor(that: inox.ast.Trees): inox.ast.TreeDeconstructor { val s: self.type; val t: that.type } = that match {
    case tree: Trees => new TreeDeconstructor {
      protected val s: self.type = self
      protected val t: tree.type = tree
    }.asInstanceOf[TreeDeconstructor { val s: self.type; val t: that.type }]

    case _ => super.getDeconstructor(that)
  }
}

trait Printer extends extraction.Printer {
  protected val trees: Trees
}

trait TreeDeconstructor extends extraction.TreeDeconstructor {
  protected val s: Trees
  protected val t: Trees

  override def deconstruct(f: s.Flag): DeconstructedFlag = f match {
    case s.Inline => (Seq(), Seq(), Seq(), (_, _, _) => t.Inline)
    case s.InlineOnce => (Seq(), Seq(), Seq(), (_, _, _) => t.InlineOnce)
    case s.Synthetic => (Seq(), Seq(), Seq(), (_, _, _) => t.Synthetic)
    case _ => super.deconstruct(f)
  }
}
