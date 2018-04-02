/*
 * Copyright (C) 2018-present, Wei Chou(weichou2010@gmail.com)
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

import hobby.wei.c.reflow.Reflow.Period

import scala.collection.immutable
import scala.language.implicitConversions

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 28/03/2018
  */
object implicits {
  lazy val P_HIGH = Reflow.P_HIGH
  lazy val P_NORMAL = Reflow.P_NORMAL
  lazy val P_LOW = Reflow.P_LOW

  lazy val TRANSIENT = Period.TRANSIENT
  lazy val SHORT = Period.SHORT
  lazy val LONG = Period.LONG
  lazy val INFINITE = Period.INFINITE

  lazy val SINGLE_THREAD = Config.SINGLE_THREAD

  // def方法不能直接起作用，这里转换为函数值。
  implicit lazy val + = kce2Bdr _
  implicit lazy val - = trans2Bdr _
  implicit lazy val * = kceKv2Bdr _
  implicit lazy val / = strKv2Bdr _

  implicit def kce2Bdr(kce: Kce[_ <: AnyRef]): Helper.Kces.Builder = Helper.Kces + kce

  implicit def kce2Ok(kce: Kce[_ <: AnyRef]): immutable.Set[Kce[_ <: AnyRef]] = kce ok()

  implicit def kceBdr2Ok(kb: Helper.Kces.Builder): immutable.Set[Kce[_ <: AnyRef]] = kb ok()

  implicit def trans2Bdr(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): Helper.Transformers.Builder = Helper.Transformers + trans

  implicit def trans2Ok(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = trans ok()

  implicit def transBdr2Ok(tb: Helper.Transformers.Builder): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = tb ok()

  implicit def kceKv2Bdr[V <: AnyRef](kv: (Kce[V], V)): In.Builder = In + (kv._1.key, kv._2)

  implicit def kceKv2Ok[V <: AnyRef](kv: (Kce[V], V)): In = kv ok()

  implicit def strKv2Bdr[V](kv: (String, V)): In.Builder = In + (kv._1, kv._2)

  implicit def strKv2Ok[V](kv: (String, V)): In = kv ok()

  implicit def inBdr2Ok(ib: In.Builder): In = ib ok()
}