/*
 * Copyright (C) 2018-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import hobby.wei.c.reflow.implicits.KceAdd

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 23/03/2018
  */
object kces {
  lazy val anyRef = new Kce[AnyRef]("anyr") {}
  lazy val int = new Kce[Integer]("int") {}
  lazy val str = new Kce[String]("str") {}
  lazy val outputstr = new Kce[String]("outputstr") {}
}

object trans {
  lazy val int2str = new Transformer[Integer, String]("int", "str") {
    override def transform(in: Option[Integer]) = in.map(String.valueOf)
  }
  lazy val str2int = new Transformer[String, Integer]("str", "int") {
    override def transform(in: Option[String]) = in.map(Integer.valueOf)
  }
}

object trats {
  import kces._

  lazy val int2str0 = new Trait.Adapter {
    override protected def period() = Reflow.Period.TRANSIENT

    override protected def requires() = int ok()

    override protected def outs() = str.ok()

    override protected def name() = "int2str0"

    override def newTask() = new Task() {
      override protected def doWork(): Unit = {
        output(str.key, String.valueOf(input(int.key).getOrElse(-1)))
      }
    }
  }

  lazy val int2str1 = new Trait.Adapter {
    override protected def period() = Reflow.Period.TRANSIENT

    override protected def requires() = int.ok()

    override protected def outs() = str.ok()

    override protected def name() = "int2str1"

    override def newTask() = new Task() {
      override protected def doWork(): Unit = {
        output(str.key, String.valueOf(input(int.key).getOrElse(-1)))
      }
    }
  }
}

object tasks {
}
