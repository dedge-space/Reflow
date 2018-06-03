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

package hobby.wei.c.tool

import hobby.chenai.nakam.basis.TAG
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.Breaks._

/**
  * 用于多个线程竞争去执行某个任务，但这个任务只需要任意一个线程执行即可（即：主要是做事，只要有人做就ok），其它线程不必等待或阻塞。
  * 但同时也要避免遗漏：{{{
  * 当执行任务的线程A认为做完了准备收工的时候，又来了新任务，但此时A还没切换标识（即：A说我正做着呢），
  * 导致其它线程认为有人在做而略过，而A接下来又收工了的情况。
  * }}}
  * 用法示例：{{{
  * val snatcher = new Snatcher()
  * if (snatcher.snatch()) {
  *   breakable {
  *     while (true) {
  *       // do something ...
  *       if (!snatcher.glance()) break
  *     }
  *   }
  * }
  * }}}
  * 或：{{{
  * val snatcher = new Snatcher()
  * snatcher.tryOn {
  *   // do something ...
  * }
  * }}}
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 24/01/2018
  */
class Snatcher {
  private val scheduling = new AtomicBoolean(false)
  private val signature = new AtomicBoolean(false)

  /**
    * 线程尝试抢占执行权并执行某任务。
    *
    * @return `true` 抢占成功并执行任务，`false`抢占失败，未执行任务。
    */
  def tryOn(doSomething: => Unit): Boolean = {
    if (snatch()) {
      breakable {
        while (true) {
          doSomething
          if (!glance()) break
        }
      }
      true
    } else false
  }

  /**
    * 线程尝试抢占执行权。
    *
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def snatch(): Boolean = {
    signature.set(true) // 必须放在前面。标识新的调度请求，防止遗漏。
    if (scheduling.compareAndSet(false, true)) {
      signature.set(false)
      true
    } else false
  }

  /**
    * 之前抢占成功（`snatch()`返回`true`）的线程，释放（重置）标识，并再次尝试抢占执行权。
    *
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def glance(): Boolean = {
    // 必须放在sSignature前面，确保不会有某个瞬间丢失调度(外部线程拿不到锁，而本线程认为没有任务了)。
    scheduling.set(false)
    // 再看看是不是又插入了新任务，并重新竞争锁定。
    // 如果不要sSignature的判断而简单再来一次是不是就解决了问题呢？
    // 不能。这个再来一次的问题会递归。
    if (signature.get() && scheduling.compareAndSet(false, true)) {
      signature.set(false) // 等竞争到了再置为false.
      true // continue
    } else false // break
  }
}

object Snatcher {
  /**
    * 为避免多线程的阻塞，提高运行效率，可使用本组件将`action`队列化（非`顺序化`）。
    * <p>
    * 如果没有`顺序化`需求，可略过后面的说明。但如果有，务必请注意：<br>
    * 本组件并不能保证入队后的[`action`s]的顺序与入队前想要的一致，这不是本组件的缺陷，而是同步锁固有的性质导致了
    * 必然存在这样的问题：`任何同步锁的互斥范围都不可能超过其能够包裹的代码块的范围`。即使本组件的入队操作使用了`公平锁`，也
    * 无法保证外层的顺序需求。要实现顺序性，客户代码有两个选择：<br>
    * 1. 外层代码必须根据具体的业务逻辑另行实现能够保证顺序的`互斥同步`逻辑，并在同步块内执行`queueAction()`操作；
    * 2. 在`queueAction()`的参数`action`所表示的函数体内实现[让输出具有`顺序性`]逻辑。
    * <p>
    * 重申：本组件`能且只能`实现：`避免多线程阻塞，提高执行效率`。
    *
    * @param fluentMode 流畅模式。启用后，在拥挤（队列不空）的情况下，设置了`flag`的`action`将会被丢弃而不执行（除非是最后一个）。默认`不启用`。
    */
  class ActionQueue(val fluentMode: Boolean = false) extends TAG.ClassName {
    private lazy val queue = new ConcurrentLinkedQueue[Action[_]]
    private lazy val snatcher = new Snatcher

    private case class Action[T](necessity: () => T, action: T => Unit, canAbandon: Boolean) {
      type A = T

      def execN(): A = necessity()

      def execA(args: A): Unit = action(args)
    }

    def queueAction(action: => Unit): Unit = queueAction()((() => action) ()) { _ => }

    /**
      * 执行`action`或将其放进队列。
      *
      * @param canAbandon 是否可以被丢弃（需配合`fluentMode`使用）。默认为`false`。
      * @param necessity  必须要执行的，不可以`abandon`的。本函数的返回值将作为`action`的输入。
      * @param action     要执行的代码。
      */
    def queueAction[T](canAbandon: Boolean = false)(necessity: => T)(action: T => Unit): Unit = {
      def hasMore = !queue.isEmpty

      val elem = Action(() => necessity, action, canAbandon)
      while (!(queue offer elem)) Thread.`yield`()

      snatcher.tryOn {
        // 第一次也要检查，虽然前面入队了。因为很可能在当前线程抢占到的时候，自己入队的已经被前一个线程消化掉而退出了。
        while (hasMore) {
          val elem = queue.remove()
          val p: elem.A = elem.execN()
          if (fluentMode && elem.canAbandon) { // 设置了`abandon`标识
            if (hasMore) { // 可以抛弃
            } else elem.execA(p)
          } else elem.execA(p)
        }
      }
    }
  }
}
