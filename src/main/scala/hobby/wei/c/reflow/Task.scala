/*
 * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hobby.wei.c.reflow

import java.util.concurrent.locks.ReentrantLock
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.State._
import hobby.wei.c.tool.Locker
import hobby.wei.c.tool.Locker.CodeZ

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016
  */
abstract class Task {
  private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)

  private var thread: Thread = _
  private var tracker: Tracker = _
  private var trat: Trait[_] = _
  private var input: Out = _
  private[reflow] var out: Out = _
  private var aborted: Boolean = _
  private var working: Boolean = _

  final def env(tracker: Tracker, trat: Trait[_], input: Out, out: Out): Unit = {
    this.tracker = tracker
    this.trat = trat
    this.input = input
    this.out = out
  }

  protected final def isReinforceRequired: Boolean = tracker.reinforceRequired.get()

  protected final def isReinforcing: Boolean = tracker.getState == REINFORCING

  /**
    * 请求强化运行。
    *
    * @return 之前的任务是否已经请求过, 同{isReinforceRequired()}
    */
  protected final def requireReinforce(): Boolean = tracker.requireReinforce()

  /**
    * 取得输入的value.
    *
    * @param key
    * @tparam T
    * @return
    */
  protected final def input[T](key: String): Option[T] = input.get(key)

  protected final def output[T](key: String, value: T): Unit = out.put(key, value)

  protected final def output(map: Map[String, Any]): Unit = map.foreach { m: (String, Any) =>
    output(m._1, m._2)
  }

  /**
    * 如果 {@link #isReinforceRequired()}为true, 则缓存一些参数用于再次执行时使用。
    * 问: 为何不直接用全局变量来保存?
    * 答: 下次并不重用本对象。
    *
    * @param key
    * @param value
    */
  protected final def cache[T](key: String, value: T): Unit = if (isReinforceRequired) {
    input.cache(key, null); // 仅用来测试key是否重复。
    tracker.cache(trat, create = true).cache(key, value)
  }

  protected final def progress(progress: Float): Unit = tracker.onTaskProgress(trat, progress, out)

  /**
    * 如果认为任务失败, 则应该主动调用本方法来强制结束任务。
    * 不设计成直接声明{@link #doWork()}方法throws异常, 是为了让客户代码尽量自己处理好异常, 以防疏忽。
    *
    * @param e
    */
  protected final def failed[T](e: Exception): T = {
    // 简单粗暴的抛出去, 由Runner统一处理。
    // 这里不抛出Exception的子类, 是为了防止被客户代码错误的给catch住。
    // 但是exec()方法catch了本Error并转换为正常的Assist.FailedException
    throw new FailedError(e.ensuring(_.nonNull))
  }

  /**
    * 如果子类在{@link #doWork()}中检测到了中断请求(如: 在循环里判断{@link #isAborted()}),
    * 应该在处理好了当前事宜、准备好中断的时候调用本方法以中断整个任务。
    */
  protected final def abortDone[T](): T = throw new AbortError()

  @throws[CodeException]
  @throws[AbortException]
  @throws[FailedException]
  final def exec(): Unit = {
    Locker.syncr {
      if (aborted) return
      thread = Thread.currentThread()
      working = true
    }
    try {
      // 这里反馈进度有两个用途: 1. Feedback subProgress; 2. 并行任务进度统计。
      progress(0)
      doWork()
      progress(1)
    } catch {
      case e: FailedError =>
        throw new FailedException(e.getCause)
      case e: AbortError =>
        throw new AbortException(e.getCause)
      case e: Exception =>
        // 各种非正常崩溃的RuntimeException, 如NullPointerException等。
        throw new CodeException(e)
    }
  }

  final def abort(): Unit = {
    Locker.syncr {
      aborted = true
    }
    if (working) {
      thread.interrupt()
      onAbort()
    }
  }

  protected final def traite: Trait[_] = trat

  protected final def isAborted: Boolean = aborted

  protected abstract def doWork(): Unit

  protected def onAbort(): Unit = {}

  /**
    * 当同一个任务被放到多个{@link Dependency}中运行, 而某些代码段需要Class范围的串行化时, 应该使用本方法包裹。
    * 注意: 不要使用synchronized关键字, 因为它在等待获取锁的时候不能被中断, 而本方法使用{@link ReentrantLock}锁机制,
    * 当{@link #abort()}请求到达时, 如果还处于等待获取锁状态, 则可以立即中断。
    *
    * @param codes 要执行的代码段, 应包裹在{Locker.CodeZ#exec()}中。
    * @param scope 锁的作用范围。通常应该写某Task子类的ClassName.class, 而不要去{#getClass()},
    *              因为如果该类再有一个子类, 本方法在不同的实例返回不同的Class对象, scope不同, 同步目的将失效。
    * @tparam T 返回值类型。
    * @return {Locker.CodeZ#exec()}的返回值。
    */
  protected final def synca[T](scope: Class[_ <: Task])(codes: => T): Option[T] = sync(new CodeZ[T] {
    override def exec() = codes
  }, scope)

  protected final def sync[T](codes: Locker.CodeZ[T], scope: Class[_ <: Task]): Option[T] = {
    try {
      Locker.sync(codes, scope.ensuring(_.nonNull))
    } catch {
      case e: InterruptedException =>
        assert(isAborted)
        throw new AbortError(e)
    }
  }
}