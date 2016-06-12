package com.timgroup.iterata

import scala.annotation.tailrec
import scala.collection.{GenTraversableOnce, AbstractIterator, Iterator}

import MemoizeExhaustionIterator.Implicits.IteratorWithMemoizeExhaustion
import scala.collection.parallel.{defaultTaskSupport, TaskSupport}

/**
 * A "parallel iterator" combining Scala’s parallel collections with an
 * underlying grouped iterator, processing the contents of each chunk in
 * parallel, and exposing the interface of a simple ungrouped iterator.
 *
 * See: http://docs.scala-lang.org/overviews/parallel-collections/overview.html
 *
 * The goal is to read a chunk from the underlying grouped iterator,
 * and then to process each element inside the chunk in parallel via `#map`
 * and `#flatMap`, using the standard Scala parallel collections to speed up
 * the processing the passed function over the iterator.
 *
 * ParIterator is typically constructed via the method `Iterator#par()` which
 * is added via the implicits in `ParIterator.Implicits`, for example:
 *
 * {{{
 * scala> import com.timgroup.iterata.ParIterator.Implicits._
 * scala> val it = (1 to 100000).iterator.par().map(n => (n + 1, Thread.currentThread.getId))
 * scala> it.map(_._1).toSet.size
 * res2: Int = 8 // addition was distributed over 8 threads
 * }}}
 *
 * @param groupedIt  an underlying grouped iterator, e.g. from `Iterator#grouped`
 * @tparam A         the type of each element
 */

class ParIterator[A](groupedIt: Iterator[Seq[A]], taskSupport: TaskSupport = defaultTaskSupport) extends AbstractIterator[A] {
  implicit class WithTaskSupport[A](xs: Seq[A]) {
    def parWithTaskSupport = {
      val par = xs.par
      par.tasksupport = taskSupport
      par
    }
  }

  val groupedItNoEmptyChunks = groupedIt.filterNot(_.isEmpty)
  var currChunk: List[A] = Nil

  //////////////////////////////////////////////////////////////////////////
  // Overrides to process each chunk via Scala parallel collections
  //////////////////////////////////////////////////////////////////////////

  override def flatMap[B](f: A => GenTraversableOnce[B]): Iterator[B] =
    new ParIterator(allChunks.map(xs => xs.parWithTaskSupport.flatMap(f).toList), taskSupport)

  override def map[B](f: A => B): Iterator[B] =
    new ParIterator(allChunks.map(xs => xs.parWithTaskSupport.map(f).toList), taskSupport)

  override def filter(p: A => Boolean): Iterator[A] =
    new ParIterator(allChunks.map(xs => xs.parWithTaskSupport.filter(p).toList), taskSupport)

  override def find(p: A => Boolean): Option[A] =
    new ParIterator(allChunks.map(xs => xs.parWithTaskSupport.find(p).toList), taskSupport).take(1).toList.headOption

  private def allChunks = currChunk match {
    case Nil => groupedItNoEmptyChunks
    case _   => Seq(currChunk).iterator.memoizeExhaustion ++ groupedItNoEmptyChunks
  }

  //////////////////////////////////////////////////////////////////////////
  // Implementation of basic iterator interface, no parallelism here
  //////////////////////////////////////////////////////////////////////////

  override def hasNext: Boolean = currChunk.nonEmpty || groupedItNoEmptyChunks.hasNext

  @tailrec
  final override def next(): A = currChunk match {
    case a :: as => currChunk = as; a
    case Nil     => currChunk = groupedItNoEmptyChunks.next().toList; next()
  }
}

object ParIterator {
  object Implicits {

    implicit class GroupedIteratorWithPar[A](groupedIt: Iterator[Seq[A]]) {
      def par(taskSupport: TaskSupport = defaultTaskSupport): Iterator[A] =
        new ParIterator[A](groupedIt, taskSupport)
    }

    implicit class UngroupedIteratorWithPar[A](it: Iterator[A]) {
      def par(chunkSize: Int = 2048, taskSupport: TaskSupport = defaultTaskSupport): Iterator[A] ={
        val v = it.grouped(chunkSize).par(taskSupport)
        v
      }
    }

  }

}