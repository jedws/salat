/**
* Copyright (c) 2010, 2011 Novus Partners, Inc. <http://novus.com>
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* For questions and comments about this product, please see the project page at:
*
* http://github.com/novus/salat
*
*/
package com.novus.salat.test

import com.novus.salat._
import scala.tools.nsc.util.ScalaClassLoader
import com.mongodb.casbah.Imports._
import com.novus.salat.util.MapPrettyPrinter
import com.novus.salat.test.model.{Ida, Alice, Walrus}

class CustomContextSpec extends SalatSpec {

  "Salat context" should {

    "allow creation of a custom context" in {
      // create custom context
      val ctx = new Context {
        val name = Some("CustomContextSpec-1")
      }
      ctx.name must beSome("CustomContextSpec-1")
      // this custom context is using the default classloader
      ctx.classLoaders must haveSize(1)
      ctx.classLoaders(0) must_== ctx.getClass.getClassLoader

      // members of com.novus.salat.test.model can be resolved as expected
      ctx.classLoaders(0) must_== Alice.getClass.getClassLoader
      getClassNamed(Alice.getClass.getName)(ctx) must beSome(Alice.getClass)
    }

    "provide flexible classloader handling" in {

      // TODO: somewhat contrived but...
      val TestClassName = "com.novus.salat.test.CustomContextSpec$$anonfun$1$$anonfun$apply$15$$anon$2$Ida"

      val customCl = new ScalaClassLoader() {
        case class Ida(lake: Int = 10, drowned: Boolean = true)
        override def findClass(name: String): Class[_] = if (name == TestClassName) {
          log.info("CustomContextSpec: custom classloader returning Class[_] %s for %s", classOf[Ida], name)
          classOf[Ida]
        }
        else throw new ClassNotFoundException
      }
      val testClassForName = Class.forName(TestClassName, true, customCl)

      "allow registration of custom classloaders that precede the default classloader" in {
        val CustomContextName = "CustomContextSpec-2"
        val ctx = new Context {
          val name = Some(CustomContextName)
        }

        ctx.name must beSome(CustomContextName)
        ctx.classLoaders must haveSize(1)

        ctx.registerClassLoader(customCl)
        ctx.classLoaders must haveSize(2)

        val custom = ctx.classLoaders(0)
        val default = ctx.classLoaders(1)

        custom must_== customCl
        default must_== ctx.getClass.getClassLoader

        // we can resolve a class from the default classloader
        getClassNamed(Alice.getClass.getName)(ctx) must beSome(Alice.getClass)
        // we can resolve an imaginary text class from the custom classloader
        getClassNamed(TestClassName)(ctx) must beSome(testClassForName)

        // But from where?  Now try to force resolution from specific class loader and see what happens
        getClassNamed(Alice.getClass.getName)(new Context() {
          val name = Some("custom only")
          classLoaders = Seq(custom)
        }) must beNone
        getClassNamed(Alice.getClass.getName)(new Context() {
          val name = Some("default only")
          classLoaders = Seq(default)
        }) must beSome(Alice.getClass)
        getClassNamed(TestClassName)(new Context() {
          val name = Some("custom only")
          classLoaders = Seq(custom)
        }) must beSome(testClassForName)
        // well, with a full-on classloader mock, this might be possible - let's just settle for, it resolves in both classloaders
        // but by because custom precedes default it is obviously resolving from the custom
//        getClassNamed(TestClassName)(Seq(default)) must beNone
      }

      "allow creation of a context overrides defaults to specify its own classloader" in {
        val CustomContextName = "CustomContextSpec-3"
        val ctx = new Context {
          val name = Some(CustomContextName)
          classLoaders = Seq(customCl)

          override def registerClassLoader(cl: ClassLoader) = {
            log.info("This is my custom context and I would prefer not to register your classloader, sir")
            // do nothing
          }
        }

        ctx.name must beSome(CustomContextName)
        ctx.classLoaders must haveSize(1)
        ctx.classLoaders(0) must_== customCl
        ctx.classLoaders must not contain(ctx.getClass.getClassLoader)

        // try to register another classloader and confirm it didn't work, because we overrode the default impl
        val customCl2: ClassLoader = new ScalaClassLoader() {
          // some impl
        }
        ctx.registerClassLoader(customCl2)
        ctx.classLoaders must haveSize(1)
        ctx.classLoaders(0) must_== customCl
        ctx.classLoaders must not contain(customCl2)
        ctx.classLoaders must not contain(ctx.getClass.getClassLoader)

        // resolving a class from the default classloader doesn't work
        getClassNamed(Alice.getClass.getName)(ctx) must beNone
        // resolving a class from our custom classloader does
        getClassNamed(TestClassName)(ctx) must not beEmpty
      }

      "percolate a custom context down the entire chain" in {
        val CustomContextName = "CustomContextSpec-4"
        implicit val ctx = new Context {
          val name = Some(CustomContextName)
        }

        ctx.name must beSome(CustomContextName)
        ctx.classLoaders must haveSize(1)

        ctx.registerClassLoader(customCl)
        ctx.classLoaders must haveSize(2)

        // we can resolve a class from the default classloader and get a working grater for it
        getClassNamed(Walrus.getClass.getName) must beSome(Walrus.getClass)
        val w = Walrus(Seq("a", "b", "C"))
        val dbo: MongoDBObject = grater[Walrus[String]].asDBObject(w)
        dbo.get("manyThings") must beSome[AnyRef]
        val w_* = grater[Walrus[String]].asObject(dbo)
        w_* must_== w

        // we can resolve a class from the custom classloader
        getClassNamed(TestClassName) must beSome(testClassForName)

        // TODO: try to get grubby hands on a grater
      }
    }

    "allow registering global key overrides" in {
      val lake = "lake"
      val swamp = "swamp"
      // create custom context
      implicit val ctx = new Context {
        val name = Some("CustomContextSpec-5")
      }
      ctx.name must beSome("CustomContextSpec-5")
      ctx.keyOverrides must beEmpty

      ctx.registerGlobalKeyOverride(remapThis = lake, toThisInstead = swamp)
      ctx.keyOverrides must haveSize(1)
      ctx.keyOverrides must havePair(lake, swamp)

      val i = Ida(lake = Some(BigDecimal("3.14")))
      val dbo: MongoDBObject = grater[Ida].asDBObject(i)
      log.info(MapPrettyPrinter(dbo))
      // our global key remap transformed "lake" to "swamp"
      dbo must havePair(swamp, 3.14)
      dbo must not have key(lake)

      val i_* = grater[Ida].asObject(dbo)
      i_* must_== i
    }

  }
}