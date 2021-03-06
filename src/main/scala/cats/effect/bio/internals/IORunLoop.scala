/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.bio.internals

import cats.effect.bio.BIO
import cats.effect.bio.BIO.{Async, Bind, Delay, Map, Pure, RaiseError, Suspend}

import scala.collection.mutable.ArrayStack

private[effect] object IORunLoop {
  private type Current = BIO[Any, Any]
  private type Bind = Any => BIO[Any, Any]
  private type CallStack = ArrayStack[Bind]
  private type Callback = Either[Any, Any] => Unit

  case class CustomException[E](e: E) extends Exception(e.toString, null, true, false)

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def start[E, A](source: BIO[E, A], cb: Either[E, A] => Unit): Unit =
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], null, null, null)

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def startCancelable[E, A](source: BIO[E, A], conn: IOConnection, cb: Either[E, A] => Unit): Unit =
    loop(source, conn, cb.asInstanceOf[Callback], null, null, null)

  /**
   * Loop for evaluating an `IO` value.
   *
   * The `rcbRef`, `bFirstRef` and `bRestRef`  parameters are
   * nullable values that can be supplied because the loop needs
   * to be resumed in [[RestartCallback]].
   */
  private def loop(
    source: Current,
    cancelable: IOConnection,
    cb: Either[Any, Any] => Unit,
    rcbRef: RestartCallback,
    bFirstRef: Bind,
    bRestRef: CallStack): Unit = {

    var currentIO: Current = source
    var conn: IOConnection = cancelable
    var bFirst: Bind = bFirstRef
    var bRest: CallStack = bRestRef
    var rcb: RestartCallback = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, f) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch { case NonFatal(e) =>
            currentIO = RaiseError(f(e))
          }

        case Suspend(thunk, f) =>
          currentIO = try thunk() catch { case NonFatal(ex) => RaiseError(f(ex)) }

        case RaiseError(ex) =>
          findErrorHandler[Any](bFirst, bRest) match {
            case null =>
              cb(Left(ex))
              return
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => Logger.reportFailure(e); throw e }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Async(register) =>
          if (conn eq null) conn = IOConnection()
          if (rcb eq null) rcb = new RestartCallback(conn, cb.asInstanceOf[Callback])
          rcb.prepare(bFirst, bRest)
          register(conn, rcb)
          return
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            cb(Right(unboxed))
            return
          case bind =>
            val fa = try bind(unboxed) catch { case NonFatal(ex) => Logger.reportFailure(ex); throw ex }
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }
    } while (true)
  }

  /**
   * Evaluates the given `IO` reference until an asynchronous
   * boundary is hit.
   */
  def step[E, A](source: BIO[E, A]): BIO[E, A] = {
    var currentIO: Current = source
    var bFirst: Bind = null
    var bRest: CallStack = null
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, f) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch { case NonFatal(e) =>
            currentIO = RaiseError(f(e))
          }

        case Suspend(thunk, f) =>
          currentIO = try thunk() catch { case NonFatal(ex) => RaiseError(f(ex)) }

        case RaiseError(ex) =>
          findErrorHandler[Any](bFirst, bRest) match {
            case null =>
              return currentIO.asInstanceOf[BIO[E, A]]
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => Logger.reportFailure(e); throw e }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Async(register) =>
          // Cannot inline the code of this method — as it would
          // box those vars in scala.runtime.ObjectRef!
          return suspendInAsync(currentIO, bFirst, bRest, register).asInstanceOf[BIO[E, A]]
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return (if (currentIO ne null) currentIO else Pure(unboxed))
              .asInstanceOf[BIO[E, A]]
          case bind =>
            currentIO = try bind(unboxed) catch { case NonFatal(ex) => Logger.reportFailure(ex); throw ex }
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
    } while (true)
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def suspendInAsync[E, A](currentIO: BIO[E, A],
                                   bFirst: Bind,
                                   bRest: CallStack,
                                   register: (IOConnection, Either[E, Any] => Unit) => Unit): BIO[E, A] = {

    // Hitting an async boundary means we have to stop, however
    // if we had previous `flatMap` operations then we need to resume
    // the loop with the collected stack
    if (bFirst != null || (bRest != null && bRest.nonEmpty))
      Async { (conn, cb) =>
        val rcb = new RestartCallback(conn, cb.asInstanceOf[Callback])
        rcb.prepare(bFirst, bRest)
        register(conn, rcb)
      }
    else
      currentIO
  }

  /**
   * Pops the next bind function from the stack, but filters out
   * `IOFrame.ErrorHandler` references, because we know they won't do
   * anything — an optimization for `handleError`.
   */
  private def popNextBind(bFirst: Bind, bRest: CallStack): Bind = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[_, _, _, _]])
      bFirst
    else if (bRest != null) {
      var cursor: Bind = null
      while (cursor == null && bRest.nonEmpty) {
        val ref = bRest.pop()
        if (!ref.isInstanceOf[IOFrame.ErrorHandler[_, _, _, _]]) cursor = ref
      }
      cursor
    } else {
      null
    }
  }

  /**
   * Finds a [[IOFrame]] capable of handling errors in our bind
   * call-stack, invoked after a `RaiseError` is observed.
   */
  private def findErrorHandler[E](bFirst: Bind, bRest: CallStack): IOFrame[E, Any, BIO[E, Any]] = {
    var result: IOFrame[E, Any, BIO[E, Any]] = null
    var cursor = bFirst
    var continue = true

    while (continue) {
      if (cursor != null && cursor.isInstanceOf[IOFrame[_, _, _]]) {
        result = cursor.asInstanceOf[IOFrame[E, Any, BIO[E, Any]]]
        continue = false
      } else {
        cursor = if (bRest != null && bRest.nonEmpty) bRest.pop() else null
        continue = cursor != null
      }
    }
    result
  }

  /**
   * A `RestartCallback` gets created only once, per [[startCancelable]]
   * (`unsafeRunAsync`) invocation, once an `Async` state is hit,
   * its job being to resume the loop after the boundary, but with
   * the bind call-stack restored
   *
   * This is a trick the implementation is using to avoid creating
   * extraneous callback references on asynchronous boundaries, in
   * order to reduce memory pressure.
   *
   * It's an ugly, mutable implementation.
   * For internal use only, here be dragons!
   */
  private final class RestartCallback(conn: IOConnection, cb: Callback)
    extends Callback {

    private[this] var canCall = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _

    def prepare(bFirst: Bind, bRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
    }

    def apply(either: Either[Any, Any]): Unit =
      if (canCall) {
        canCall = false
        either match {
          case Right(value) =>
            loop(Pure(value), conn, cb, this, bFirst, bRest)
          case Left(e) =>
            loop(RaiseError(e), conn, cb, this, bFirst, bRest)
        }
      }
  }
}
