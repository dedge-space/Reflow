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

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Condition, ReentrantLock}
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.reflow.Assist.{between, eatExceptions, AbortException, FailedException, InnerError, Monitor}
import hobby.wei.c.reflow.Dependency.{IsPar, SetTo, _}
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.{Locker, Snatcher}

import scala.collection.{mutable, _}
import scala.util.control.Breaks._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016;
  *          1.1, 31/01/2018.
  */
private[reflow] abstract class Tracker(val reflow: Reflow, val outer: Option[Env]) extends TAG.ClassName {
  private lazy final val snatcher4Init = new Snatcher
  // 这两个变量，在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到cache中）；
  // 而在Reinforce阶段，会从外部传入。
  // 因此有这样的设计。
  @volatile private var cacheInited: Boolean = outer.fold(false)(_.isReinforcing)
  private final lazy val reinforceCache = outer.fold(new ReinforceCache) { env =>
    if (env.isReinforcing) env.obtainCache.getOrElse(new ReinforceCache) else new ReinforceCache
  }

  @deprecated(message = "不要直接调用本属性，特别是对于`SubReflow`，根本不需要使用它，否则会导致状态错误。", since = "0.0.1")
  private final lazy val reinforceRequired = new AtomicBoolean(false)

  private final def getOrInitFromOuterCache(trat: String = null, sub: Option[ReinforceCache] = None): ReinforceCache = {
    if (!cacheInited) {
      snatcher4Init.tryOn {
        if (!cacheInited) {
          outer.foreach { env =>
            env.tracker.getOrInitFromOuterCache(env.trat.name$, Option(reinforceCache))
          }
          cacheInited = true
        }
      }
    }
    sub.foreach(reinforceCache.subs.putIfAbsent(trat, _))
    reinforceCache
  }

  final def getCache = getOrInitFromOuterCache()

  private[reflow] def getPrevOutFlow: Out

  private[reflow] def isInput(trat: Trait[_]): Boolean

  def getState: State.Tpe

  final def isSubReflow: Boolean = outer.isDefined

  final def isReinforceRequired: Boolean = outer.fold(reinforceRequired.get)(_.isReinforceRequired)

  final def isReinforcing: Boolean = outer.fold(getState.group == REINFORCING.group /*group代表了几个状态*/)(_.isReinforcing)

  final def requireReinforce(trat: Trait[_ <: Task]): Boolean = {
    assert(!trat.isPar)
    // 必须提前执行，以便递归。本实现支持并发，线程安全。
    val required = outer.fold(reinforceRequired.getAndSet(true))(_.requireReinforce())
    val cache = getCache
    if (cache.inputs.isNull) {
      cache.inputs = getPrevOutFlow.ensuring(_.nonNull)
      if (debugMode) log.w("[requireReinforce]**********************************************************cache.inputs:%s.", cache.inputs)
    }
    if (cache.begins.isEmpty || reflow.basis.topOf(cache.begins.head._1) == reflow.basis.topOf(trat) /*处于同一个并行组*/ ) {
      cache.begins += ((trat.name$, ()))
      onRequireReinforce(trat, cache)
    }
    required
  }

  /** 注意：对于并行任务，每次请求`reinforce`都会回调一次本方法。 */
  protected def onRequireReinforce(trat: Trait[_ <: Task], cache: ReinforceCache): Unit = {}

  private[reflow] def onTaskStart(trat: Trait[_]): Unit

  private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit

  private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit

  /**
    * @param trigger 触发者。可能是从里层的任务传来的。
    * @param trat    当前正在执行的trat。
    * @param forError
    * @param e
    */
  private[reflow] def performAbort(trigger: String, trat: Trait[_], forError: Boolean, e: Exception): Unit

  /** 先于{@link #endRunner(Runner)}执行。 */
  private[reflow] def innerError(runner: Runner, e: Exception): Unit

  private[reflow] def endRunner(runner: Runner): Unit
}

private[reflow] class ReinforceCache {
  // 用`trat.name$`作为`key`, 同一个`Reflow`中，`name$`是不能重名的，因此该方法可靠。
  /** 子`Trait`的`Task`缓存用到的`Out`。 */
  lazy val caches = new concurrent.TrieMap[String, Out]
  /** 子`Trait`的`Task`启动的`Reflow`对应的`Tracker`的`Cache`。 */
  lazy val subs = new concurrent.TrieMap[String, ReinforceCache]
  /** 作`Set`用。`reinforce`阶段开始时的`Trait.name$`。 */
  lazy val begins = new concurrent.TrieMap[String, Unit]
  @volatile var inputs: Out = _
  /** 对于一个并行任务的某些子任务的`reinforce`请求，我们不打算再次执行整个并行任务，因此
    * 需要保留`浏览`运行模式的`输入`（见`inputs`）和`结果`（本`outs`），以便在`reinforce`之后合并输出。
    * 本输出包含的`_keys`是当前`并行`任务中所有未申请`reinforce`的执行输出。 */
  @volatile var outs: Out = _

  override def toString = s"ReinforceCache:\n caches:$caches,\n subs:$subs,\n begins:$begins,\n inputs:$inputs,\n outs:$outs."
}

private[reflow] object Tracker {
  private[reflow] final class Impl(reflow: Reflow, traitIn: Trait[_ <: Task], transIn: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]], state: Scheduler.State$,
                                   feedback: Feedback, outer: Option[Env]) extends Tracker(reflow: Reflow, outer: Option[Env]) with Scheduler with TAG.ClassName {
    private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
    private lazy val lockSync: ReentrantLock = Locker.getLockr(new AnyRef)
    private lazy val snatcher = new Snatcher.ActionQueue()(lock)

    private val sum = reflow.basis.traits.length
    private lazy val runnersParallel = new concurrent.TrieMap[Runner, Any]
    private lazy val progress = new concurrent.TrieMap[String, Float]
    private lazy val reporter = new Reporter(feedback, sum)
    @volatile private var remaining = reflow.basis.traits
    @volatile private var normalDone, reinforceDone: Boolean = _
    @volatile private var outFlowTrimmed, prevOutFlow: Out = _
    @volatile private var timeStart: Long = _

    private[reflow] def start(): Unit = {
      if (debugMode) log.w("[start]reinforcing:%s, subReflow:%s.=====>>>>>", isReinforcing, isSubReflow)
      assert(remaining.nonEmpty, s"`start()`时候，不应该存在空任务列表。isReinforcing:$isReinforcing")
      // 如果当前是子Reflow, 则首先看是不是到了reinforce阶段。
      if (isReinforcing) {
        if (isSubReflow) {
          state.forward(COMPLETED)
          state.forward(REINFORCE_PENDING)
        } else assert(state.get == REINFORCE_PENDING)
        val cache = getCache
        // TODO: 这是一个没有申请reinforce的嵌套子任务，不应该走到这里。
        assert(cache.begins.nonEmpty)
        assert(cache.inputs.nonNull, s"${cache.inputs} 应该缓存有输入参数。")
        // 切换到reinforce的开始位置
        switch2ReinforceBegins(cache)
        prevOutFlow = cache.inputs
        outFlowTrimmed = new Out(reflow.basis.outsFlowTrimmed(remaining.head.name$))
        outFlowTrimmed.fillWith(prevOutFlow, fullVerify = false)
        tryScheduleNext(remaining.head)
      } else {
        timeStart = System.currentTimeMillis
        assert(state.get == PENDING)
        // prevOutFlow = new Out() // 不用赋值
        outFlowTrimmed = new Out(traitIn.outs$)
        tryScheduleNext(traitIn)
      }
    }

    override private[reflow] def endRunner(runner: Runner): Unit = {
      if (debugMode) log.w("[endRunner]trait:%s.", runner.trat.name$.s)
      // 拿到父级`trait`（注意：如果当前是并行的任务，则`runner.trat`是子级）。
      val (tratGlobal, veryBeginning) = if (isInput(runner.trat)) (traitIn, true) else (remaining.head, false)
      // 断言`trat`与`remaining`的一致性。
      assert(runner.trat == tratGlobal || (tratGlobal.isPar && tratGlobal.asPar.traits().contains(runner.trat)))
      runnersParallel -= runner
      // 并行任务全部结束
      if (runnersParallel.isEmpty) snatcher.queueAction {
        val state$ = state.get$
        // 判断放在里面，`snatcher`如果在执行完毕后发现了信号重来，可以再次执行本判断，避免重复。
        // 正常情况下，本函数提执行完毕，由于已经直接或间接执行了`tryScheduleNext()`，`runnersParallel`不可能再为`empty`，
        // 除非任务执行速度过快，下一轮已经完毕再到了这里。那么，本`tryOn{}`可以再次重复，这是正确的。
        if (runnersParallel.isEmpty &&
          // 如果已经中断，由于本方法体是`endRunner`，反正都执行完毕了，那么就什么也不用再干了，Game Over吧。
          // 至于`interruptSync()`，在`performAbort()`也已处理妥当。
          state$ != ABORTED && state$ != FAILED &&
          // 不过假如本`Reflow`执行完毕了，`runnersParallel`将一直是`empty`。下面的条件确保不会重复执行。
          state$ != COMPLETED && state$ != UPDATED) {
          if (veryBeginning) {
            // nothing ...
          } else {
            val cache = getCache
            if (isReinforcing) { // 如果当前任务`是`申请了`reinforce`的且处于执行阶段，则应该把输出进行合并。
              // 本条件判断必须在上一个的后面
              if (isOnReinforceBegins(tratGlobal, cache))
                if (tratGlobal.isPar) {
                  assert(cache.outs.nonNull)
                  joinOutFlow(cache.outs)
                } else {
                  assert(cache.begins.size == 1)
                  assert(cache.outs.isNull)
                  // nothing ...
                }
            } else if (isReinforceRequired /*必须放在`else`分支，即必须在`!isReinforcing`的前提下。*/ ) {
              if (isOnReinforceBegins(tratGlobal, cache))
                if (tratGlobal.isPar) {
                  val map = (new mutable.AnyRefMap[String, Kce[_ <: AnyRef]] /: cache.begins.keySet) (_ ++= reflow.basis.dependencies(_))
                  // val keys = outFlowTrimmed._keys.keySet &~ map.keySet
                  // val out = new Out(outFlowTrimmed._keys.filterKeys(keys.contains))
                  val out = new Out(outFlowTrimmed._keys.filterNot(map contains _._1))
                  out.fillWith(outFlowTrimmed)
                  cache.outs = out
                } else {
                  assert(cache.begins.size == 1)
                  assert(cache.outs.isNull)
                  // nothing ...
                }
            }
          }
          val transGlobal = if (veryBeginning) Option(transIn) else reflow.basis.transGlobal.get(tratGlobal.name$)
          val currIsLast = if (veryBeginning) remaining.isEmpty.ensuring(_ == false) else remaining.tail.isEmpty
          // 切换任务结果集
          outFlowNextStage(tratGlobal, if (currIsLast) null else if (veryBeginning) remaining.head else remaining.tail.head,
            transGlobal, (_, afterGlobalTrans) => {
              // 处理完成事件
              if (currIsLast) { // 当前是最后一个
                if (isReinforcing) {
                  val prev = state.get
                  val success = state.forward(UPDATED)
                  // 会被混淆优化掉
                  Monitor.assertStateOverride(prev, UPDATED, success)
                  // 放在interruptSync()的前面，虽然不能保证有Poster的事件到达会在sync()返回结果的前面，但没有Poster的还是可以的，这样便于测试。
                  // 这个比较特殊：因为本执行体已经在queueAction()里面了。
                  /*snatcher.queueAction{*/ reporter.reportOnUpdate(afterGlobalTrans) /*}*/
                  interruptSync(true)
                } else {
                  val prev = state.get
                  val success = state.forward(COMPLETED)
                  // 会被混淆优化掉
                  Monitor.assertStateOverride(prev, COMPLETED, success)
                  // 这个比较特殊：因为本执行体已经在queueAction()里面了。
                  /*snatcher.queueAction{*/ reporter.reportOnComplete(tratGlobal.name$, afterGlobalTrans) /*}*/
                  interruptSync(!isReinforceRequired)
                }
              }
            })
          progress.clear()
          if (!veryBeginning) remaining = remaining.tail
          if (remaining.nonEmpty) {
            tryScheduleNext(remaining.head)
          } else if (!isSubReflow && isReinforceRequired && state.forward(REINFORCE_PENDING)) {
            remaining = reflow.basis.traits
            start()
          }
        }
      }
    }

    // 必须在进度反馈完毕之后再下一个，否则可能由于线程优先级问题，导致低优先级的进度没有反馈完，而新进入的高优先级任务又要争用同步锁，造成死锁的问题。
    private def tryScheduleNext(trat: Trait[_ <: Task]): Unit = {
      assert(runnersParallel.isEmpty)
      if (trat.isPar) {
        val cache = getCache
        val begin = isReinforcing && isOnReinforceBegins(trat, cache)
        trat.asPar.traits().filter { t => // 过滤掉没有申请reinforce的
          if (begin) cache.begins.contains(t.name$) else true
        }.foreach { t =>
          runnersParallel += ((new Runner(Env(t, this)), Unit))
          progress.put(t.name$, 0f) // 把并行的任务put进去，不然计算子进度会有问题。
        }
      } else {
        //progress.put(trat.name$, 0f)
        runnersParallel += ((new Runner(Env(trat, this)), Unit))
      }
      runnersParallel.foreach { kv =>
        val runner = kv._1
        import Period._
        runner.trat.period$ match {
          case INFINITE => Worker.sPreparedBuckets.sInfinite.offer(runner)
          case LONG => Worker.sPreparedBuckets.sLong.offer(runner)
          case SHORT => Worker.sPreparedBuckets.sShort.offer(runner)
          case TRANSIENT => Worker.sPreparedBuckets.sTransient.offer(runner)
        }
      }
      Worker.scheduleBuckets()
    }

    override private[reflow] def innerError(runner: Runner, e: Exception): Unit = {
      if (debugMode) log.e("[innerError]trait:%s.", runner.trat.name$.s)
      // 正常情况下是不会走的，仅用于测试。
      performAbort(runner.trat.name$, runner.trat, forError = true, e)
    }

    override private[reflow] def getPrevOutFlow = prevOutFlow

    override private[reflow] def isInput(trat: Trait[_]) = trat == traitIn

    /** 切换到reinforce的开始位置。 */
    private def switch2ReinforceBegins(cache: ReinforceCache): Unit = breakable {
      while (true) {
        if (remaining.head.isPar) {
          if (remaining.head.asPar.traits().forall(t => !cache.begins.contains(t.name$))) {
            remaining = remaining.tail
          } else break
        } else {
          if (remaining.head.name$ != cache.begins.head._1 /*只有一个元素*/ ) {
            remaining = remaining.tail
          } else break
        }
      }
    }

    private def isOnReinforceBegins(trat: Trait[_ <: Task] = remaining.head, cache: ReinforceCache = getCache): Boolean = {
      assert(isReinforcing || isReinforceRequired, "调用时机有误。")
      cache.begins.nonEmpty && {
        if (trat.isPar) trat.asPar.traits().exists(t => cache.begins.contains(t.name$))
        else trat.name$ == cache.begins.head._1
      }
    }

    private def outFlowNextStage(trat: Trait[_ <: Task], next: Trait[_ <: Task] /*`null`表示当前已是最后*/ ,
                                 transGlobal: Option[Set[Transformer[_ <: AnyRef, _ <: AnyRef]]], onTransGlobal: (Out, Out) => Unit): Unit = {
      verifyOutFlow()
      // outFlowTrimmed这里需要作一次变换：
      // 由于outsFlowTrimmed存储的是globalTrans`前`的输出需求，
      // 而prevOutFlow需要存储globalTrans`后`的结果。
      val transOut = transGlobal.fold {
        // 对于Input任务，如果没用trans，则其输出与basis.inputs完全一致；
        // 而对于其它任务，本来在转换之前的就是trimmed了的，没用trans，那就保留原样。
        if (debugMode && isInput(trat)) assert(outFlowTrimmed._keys.values.toSet == reflow.basis.inputs)
        outFlowTrimmed
      } { ts =>
        val tranSet = ts.mutable
        val map = outFlowTrimmed._map.mutable
        val nulls = outFlowTrimmed._nullValueKeys.mutable
        doTransform(tranSet, map, nulls)
        val flow = new Out(if (isInput(trat)) reflow.basis.inputs else {
          val reasoning = outFlowTrimmed._keys.values.toSet -- tranSet.map(_.in) ++ tranSet.map(_.out)
          if (next.isNull) reflow.basis.outs.ensuring(_.forall(reasoning.contains))
          else reasoning
        })
        flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
        flow
      }
      onTransGlobal(outFlowTrimmed, transOut)
      prevOutFlow = transOut
      if (next.nonNull) {
        outFlowTrimmed = new Out(reflow.basis.outsFlowTrimmed(next.name$))
        outFlowTrimmed.fillWith(prevOutFlow, fullVerify = false)
      } else {
        // 全部执行完毕
        outFlowTrimmed = transOut
        // nothing ...
      }
    }

    private def joinOutFlow(flow: Out): Unit = if (flow /*input任务可能是一样的*/ ne outFlowTrimmed) outFlowTrimmed.putWith(flow._map, flow._nullValueKeys,
      ignoreDiffType = false /*合并到整体输出流，这里类型都是绝对匹配的。*/ ,
      fullVerify = false /*本方法的目的是一部分一部分的填入输出，在并行的任务没有全部执行完毕的情况下，通常是处于还没有填满的状态，所以不应该进行满载验证。*/)

    private def verifyOutFlow(): Unit = if (debugMode) outFlowTrimmed.verify()

    override private[reflow] def performAbort(trigger: String, trat: Trait[_], forError: Boolean, e: Exception): Unit = {
      if (state.forward(if (forError) FAILED else ABORTED)) {
        // 如果能走到这里，那么总是先于endRunner之前执行，也就意味着runnersParallel不可能为empty。
        // 但是也有可能由外部scheduler触发，runnersParallel还是会empty。
        Monitor.abortion(trigger, trat.name$, forError)
        runnersParallel.foreach(_._1.abort(trigger))
        snatcher.queueAction {
          if (forError) reporter.reportOnFailed(trigger /*为null时不会走到这里*/ , e)
          else reporter.reportOnAbort(trigger)
        }
      } else if (state.abort()) {
        // 已经到达COMPLETED/REINFORCE阶段了
      } else {
        // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
      }
      interruptSync(true /*既然是中断，应该让reinforce级别的sync请求也终止*/)
    }

    @deprecated(message = "已在{Impl}中实现, 本方法不会被调用。", since = "0.0.1")
    override def sync(): Out = ???

    @throws[InterruptedException]
    override def sync(reinforce: Boolean, milliseconds: Long): Out = {
      val start = System.currentTimeMillis
      Locker.sync$(new Locker.CodeC[Out](1) {
        @throws[InterruptedException]
        override protected def exec(cons: Array[Condition]) = {
          // 不去判断`state`是因为任务流可能会失败
          while (!(if (reinforce) reinforceDone else normalDone)) {
            if (debugMode) log.i("[sync]++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++>>>")
            if (milliseconds == -1) {
              cons(0).await()
              if (debugMode) log.i("[sync]+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++done, 0.")
            } else {
              val delta = milliseconds - (System.currentTimeMillis - start)
              if (delta <= 0 || !cons(0).await(delta, TimeUnit.MILLISECONDS)) {
                throw new InterruptedException()
              }
              if (debugMode) log.i("[sync]+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++done, 1.")
            }
          }
          outFlowTrimmed
        }
      }, lockSync, interruptable = false)
    }.get

    private def interruptSync(reinforce: Boolean) {
      Monitor.duration(this, reflow.name, timeStart, System.currentTimeMillis, state.get, state.get$, isSubReflow)
      if (debugMode) log.i("[interruptSync]------------------------------------------------------------------------------reinforce:%s.", reinforce)
      normalDone = true
      if (reinforce) reinforceDone = true
      try {
        Locker.sync$(new Locker.CodeC[Unit](1) {
          @throws[InterruptedException]
          override protected def exec(cons: Array[Condition]): Unit = {
            cons(0).signalAll()
            if (debugMode) log.i("[interruptSync]----------------------------------------------------------------------------------signalAll.")
          }
        }, lockSync, interruptable = false)
      } catch {
        case _: Exception => // 不可能抛异常
      }
    }

    override def abort(): Unit = performAbort(outer.fold[String](null)(_.trat.name$), outer.fold[Trait[_]](null)(_.trat), forError = false, null)

    override def getState = state.get

    @deprecated(message = "不要调用。", since = "0.0.1")
    override def isDone = ???

    /**
      * 每个Task都执行。
      */
    override private[reflow] def onTaskStart(trat: Trait[_]): Unit = {
      if (isReinforcing) state.forward(REINFORCING)
      else if (!isInput(trat)) {
        // 必须放在外面：
        // 1. 以防止并行的任务发生reportOnProgress在reportOnStart之前的错误；
        // 2. 对于同步阻塞开销更小。
        snatcher.queueAction {
          if (state.forward(EXECUTING)) {
            // 但反馈有且只有一次（上面forward方法只会成功一次）
            if (reflow.basis.stepOf(trat) == 0) reporter.reportOnStart()
            else { // progress会在任务开始、进行中及结束时report，这里do nothing。
            }
          }
        }
      }
    }

    // 为什么这里有个`name`? 可能是较深层次的`trat.name$`名称；`desc`与其同步。
    override private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit = {
      // 跟上面onTaskStart()保持一致，否则会出现顺序问题。
      snatcher.queueAction {
        // 即使对于REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
        if (state.get == EXECUTING) reporter.reportOnProgress(name, reflow.basis.stepOf(trat), subProgress(trat, progress), out, desc)
      }
    }

    // 注意：本方法为单线程操作。
    private def subProgress(trat: Trait[_], pogres: Float): Float = {
      def result: Float = progress.values.sum /*reduce(_ + _)*/ / progress.size

      val value = between(0, pogres, 1)
      // 如果只有一个任务，单线程，不需要同步；
      // 而如果是多个任务，那对于同一个trat，总是递增的（即单线程递增），所以
      // 总体上，即使是并行，那也是发生在不同的trat之间（即不同的trat并行递增），推导出
      // progress.values.sum也是递增的，因此也不需要全局同步。
      if (progress.size <= 1) value
      else {
        if (pogres > 0) {
          assert(progress.contains(trat.name$))
          progress.put(trat.name$, value)
          result
        } else result
      }
    }

    override private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit = {
      joinOutFlow(flow)
      Monitor.complete(if (isInput(trat)) -1 else reflow.basis.stepOf(trat), out, flow, outFlowTrimmed)
    }
  }

  private[reflow] class Runner private(env: Env, trat: Trait[_ <: Task]) extends Worker.Runner(trat: Trait[_ <: Task], null) with Equals with TAG.ClassName {
    def this(env: Env) = this(env, env.trat)

    implicit lazy val logTag: LogTag = new LogTag(className + "/" + trat.name$.takeRight(8))

    private lazy val workDone = new AtomicBoolean(false)
    private lazy val runnerDone = new AtomicBoolean(false)
    @volatile private var aborted = false
    @volatile private var trigger = trat.name$
    @volatile private var task: Task = _
    private var timeBegin: Long = _

    override def equals(any: scala.Any) = super.equals(any)

    override def canEqual(that: Any) = super.equals(that)

    override def hashCode() = super.hashCode()

    // 该用法遵循 JSR-133
    def abort(_trigger: String): Unit = if (!aborted) {
      aborted = true
      trigger = _trigger
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      if (debugMode) log.i("[run]----->>>>>")
      var working = false
      try {
        task = trat.newTask()
        // 判断放在task的创建后面, 配合abort()中的顺序。
        if (aborted) onAbort(trigger)
        else {
          onStart()
          working = true
          if (task.exec(env, this)) {
            working = false
            onWorkDone()
          } else if (aborted) {
            working = false
            onAbort(trigger)
          } else {
            // SubReflowTask的异步
          }
        }
      } catch {
        case e: Exception =>
          if (working) {
            e match {
              case _: AbortException => // 框架抛出的, 表示成功中断。
                onAbort(trat.name$)
              case e: FailedException =>
                onFailed(e.getCause.as[Exception])
              case e: CodeException => // 客户代码问题
                onException(e)
              case _ =>
                onException(new CodeException(e))
            }
          } else {
            innerError(e)
          }
      } finally {
        runnerDone.set(true)
        endMe()
      }
    }

    private def transOutput(): Out = {
      if (debugMode) log.i("[transOutput]")
      if (env.tracker.isInput(trat)) env.out // 不在这里作转换的理由：无论如何，到tracker里去了之后还要进行一遍trim合并，那就在这里节省一遍吧。
      else {
        val flow = new Out(env.tracker.reflow.basis.dependencies(trat.name$))
        env.tracker.reflow.basis.transformers.get(trat.name$).fold {
          flow.putWith(env.out._map, env.out._nullValueKeys, ignoreDiffType = false, fullVerify = true)
          flow
        } { t =>
          val map = env.out._map.mutable
          val nulls = env.out._nullValueKeys.mutable
          doTransform(t.mutable, map, nulls, flow._keys)
          flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
          flow
        }
      }
    }

    private def afterWork(flow: Out) {
      if (debugMode) log.i("[afterWork]")
      onComplete(env.out, flow)
      if (aborted) onAbort(trigger)
    }

    /** 仅在`成功`执行任务之后才可以调用本方法。 */
    def onWorkDone(): Unit = onWorkEnd(afterWork(transOutput()))

    /** 在执行任务`失败`后应该调用本方法。 */
    def onWorkEnd(doSth: => Unit) {
      if (debugMode) log.i("[onWorkEnd]")
      import hobby.chenai.nakam.basis.TAG.ThrowMsg
      require(!workDone.getAndSet(true), "如果`task.exec()`返回`true`, `task`不可以再次回调`workDone()。`".tag)
      doSth
      endMe()
    }

    // 这是可靠的，详见 VolatileTest。
    def endMe(): Unit = if (workDone.get && runnerDone.compareAndSet(true, false)) env.tracker.endRunner(this)

    def onStart() {
      if (debugMode) log.i("[onStart]")
      env.tracker.onTaskStart(trat)
      timeBegin = System.currentTimeMillis
    }

    def onComplete(out: Out, flow: Out) {
      if (debugMode) log.i("[onComplete]")
      Monitor.duration(trat.name$, timeBegin, System.currentTimeMillis, trat.period$)
      env.tracker.onTaskComplete(trat, out, flow)
    }

    // 人为触发，表示任务失败
    def onFailed(e: Exception, name: String = trat.name$) {
      if (debugMode) log.e(e, "[onFailed]trait:%s.", name.s)
      withAbort(name, e)
    }

    // 客户代码异常
    def onException(e: CodeException) {
      if (debugMode) log.e(e, "[onException].")
      withAbort(trat.name$, e)
    }

    def onAbort(trigger: String) {
      if (debugMode) log.e("[onAbort]")
      env.tracker.performAbort(trigger, trat, forError = false, null)
    }

    def withAbort(trigger: String, e: Exception) {
      if (!aborted) aborted = true
      env.tracker.performAbort(trigger, trat, forError = true, e)
    }

    def innerError(e: Exception) {
      env.tracker.innerError(this, e)
      throw new InnerError(e)
    }
  }

  private[reflow] class SubReflowTask() extends Task {
    @volatile private var scheduler: Scheduler = _

    override private[reflow] def exec$(env: Env, runner: Runner): Boolean = {
      progress(0)
      val trat = env.trat.as[ReflowTrait]
      scheduler = trat.reflow.start(In.from(env.input), trat.feedback.join(env, runner, progress(1)), null, env)
      false // 异步。
    }

    override protected def doWork(): Unit = {}

    override protected def onAbort(): Unit = {
      if (scheduler.nonNull) scheduler.abort()
      super.onAbort()
    }
  }

  private[reflow] class SubReflowFeedback(env: Env, runner: Runner, doSth: () => Unit) extends Feedback with TAG.ClassName {
    override def onStart(): Unit = {
      if (debugMode) log.i("[onStart]maybe call repeat, but no side effect:")
      runner.onStart()
    }

    override def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit = {
      // if (out ne env.out) env.out.fillWith(out, fullVerify = false) // 暂不要，以提高效率。
      env.tracker.onTaskProgress(name, env.trat, (step + sub) / sum, out, desc)
    }

    override def onComplete(out: Out): Unit = {
      if (out ne env.out) env.out.fillWith(out)
      doSth()
      runner.onWorkDone()
    }

    override def onUpdate(out: Out): Unit = onComplete(out)

    override def onAbort(trigger: String): Unit = {
      runner.onAbort(trigger)
      runner.onWorkEnd()
    }

    override def onFailed(name: String, e: Exception): Unit = {
      runner.onFailed(e, name)
      runner.onWorkEnd()
    }
  }

  private[reflow] implicit class FeedbackJoin(fb: Feedback = null) {
    def join(env: Env, runner: Runner, doSth: => Unit): Feedback = {
      val feedback = new Feedback.Observable
      feedback.addObservers(new SubReflowFeedback(env, runner, () => doSth))
      if (fb.nonNull) feedback.addObservers(fb)
      feedback
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //************************************ Reporter ************************************//

  /**
    * 该结构的目标是保证进度反馈的递增性。同时保留关键点，丢弃密集冗余。
    * 注意：事件到达本类，已经是单线程操作了。
    */
  private class Reporter(feedback: Feedback, sum: Int)(implicit logTag: LogTag) {
    private var _step: Int = _
    private var _sub: Float = _
    private var _stateResetted = true

    private[Tracker] def reportOnStart(): Unit = {
      if (debugMode) {
        log.i("[reportOnStart]_stateResetted:%b.", _stateResetted)
        assert(_stateResetted)
        _step = -1
        _sub = 0
      }
      Assist.eatExceptions(feedback.onStart())
    }

    private[Tracker] def reportOnProgress(name: String, step: Int, sub: Float, out: Out, desc: String): Unit = {
      if (debugMode) {
        log.i("[reportOnProgress]name:%s, step:%d, sub:%f, sum:%d, out:%s, desc:%s.", name.s, step, sub, sum, out, desc.s)
        assert(sub >= _sub, s"调用没有同步？`$name`。")
        if (_stateResetted && sub == 0) {
          assert(step == _step + 1)
          _step = step
          _stateResetted = false
        } else assert(step == _step)
        if (sub > _sub) {
          _sub = sub
          // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
          if (sub == 1) {
            _sub = 0
            _stateResetted = true
          }
        }
      }
      eatExceptions(feedback.onProgress(name, out, step, sum, sub, desc))
    }

    private[Tracker] def reportOnComplete(name: String, out: Out): Unit = {
      if (debugMode) {
        log.i("[reportOnComplete]name:%s, step:%d, sub:%f, sum:%d, _stateResetted:%b, out:%s.", name.s, _step, _sub, sum, _stateResetted, out)
        assert(_stateResetted && _step == sum - 1 && _sub == 0)
      }
      eatExceptions(feedback.onComplete(out))
    }

    private[Tracker] def reportOnUpdate(out: Out): Unit = eatExceptions(feedback.onUpdate(out))

    private[Tracker] def reportOnAbort(trigger: String): Unit = eatExceptions(feedback.onAbort(trigger))

    private[Tracker] def reportOnFailed(name: String, e: Exception): Unit = eatExceptions(feedback.onFailed(name, e))
  }
}
