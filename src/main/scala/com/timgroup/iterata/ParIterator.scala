package com.timgroup.iterata

import scala.annotation.tailrec
import scala.collection.{GenTraversableOnce, AbstractIterator, Iterator}

/**
 * This "parallel iterator" combines Scala’s parallel collections with an
 * underlying grouped iterator, processing the contents of each chunk in
 * parallel.
 *
 * See: http://docs.scala-lang.org/overviews/parallel-collections/overview.html
 *
 * The goal is to read a chunk from the underlying grouped iterator,
 * and then to process each element inside the chunk in parallel via `#map`
 * and `#flatMap`, using the standard Scala parallel collections.
 *
 * @param groupedIt  an underlying grouped iterator, e.g. from `Iterator#grouped`
 * @tparam A         the type of each element
 */
class ParIterator[A](val groupedIt: Iterator[Seq[A]]) extends AbstractIterator[A] {
  var currChunk: List[A] = Nil

  //////////////////////////////////////////////////////////////////////////
  // Overrides to process each chunk via Scala parallel collections
  //////////////////////////////////////////////////////////////////////////

  override def flatMap[B](f: A => GenTraversableOnce[B]): Iterator[B] =
    new ParIterator(allChunks.map(xs => xs.par.flatMap(f).toList))

  override def map[B](f: A => B): Iterator[B] =
    new ParIterator(allChunks.map(xs => xs.par.map(f).toList))

  private def allChunks = currChunk match {
    case Nil => groupedIt
    case _   => Seq(currChunk).toIterator ++ groupedIt
  }

  //////////////////////////////////////////////////////////////////////////
  // Implementation of basic iterator interface, no parallelism here
  //////////////////////////////////////////////////////////////////////////

  override def hasNext: Boolean = currChunk.nonEmpty || groupedIt.hasNext

  @tailrec
  final override def next(): A = currChunk match {
    case a :: as => currChunk = as; a
    case Nil     => currChunk = groupedIt.next().toList; next()
  }
}

object ParIterator {

  object Implicits {

    implicit class GroupedIteratorWithPar[A](groupedIt: Iterator[Seq[A]]) {
      def par: Iterator[A] =
        new ParIterator[A](groupedIt)
    }

    implicit class UngroupedIteratorWithPar[A](it: Iterator[A]) {
      def par(chunkSize: Int = 2048): Iterator[A] =
        it.grouped(chunkSize).par
    }

  }

}
