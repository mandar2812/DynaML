/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.dynaml.repl

import java.io.{InputStream, OutputStream}
import java.nio.file.NoSuchFileException

import coursier.core.{ModuleName, Organization}
import coursierapi.Dependency
import ammonite.interp.{CodeWrapper, Interpreter, Parsers, Preprocessor}
import ammonite.repl.Repl
import ammonite.interp.api.AmmoniteExit
import ammonite.runtime.Storage
import ammonite.util.Util.CodeSource
import ammonite.util.Name._
import ammonite.util._
import fastparse.internal.Util.literalize
import io.github.mandar2812.dynaml.repl.Router.{ArgSig, EntryPoint}

import scala.io.{Codec, Source}
import scala.annotation.{StaticAnnotation, tailrec}
import scala.collection.mutable
import scala.language.experimental.macros
//import scala.reflect.macros.blackbox.Context
import sourcecode.Compat.Context

import ammonite.runtime.ImportHook
/**
  * Customised version of the Ammonite REPL
  * @author mandar2812 date 1/6/16.
  * */
class DynaMLRepl(
  input: InputStream,
  output: OutputStream,
  error: OutputStream,
  storage: Storage,
  basePredefs: Seq[PredefInfo],
  customPredefs: Seq[PredefInfo],
  wd: os.Path,
  welcomeBanner: Option[String],
  replArgs: IndexedSeq[Bind[_]] = Vector.empty,
  initialColors: Colors = Colors.Default,
  replCodeWrapper: CodeWrapper,
  scriptCodeWrapper: CodeWrapper,
  alreadyLoadedDependencies: Seq[coursierapi.Dependency],
  importHooks: Map[Seq[String], ImportHook],
  initialClassLoader: ClassLoader = 
    classOf[ammonite.repl.api.ReplAPI].getClassLoader,
  classPathWhitelist: Set[Seq[String]])
  extends Repl(
    input, output, error, storage,
    basePredefs, customPredefs, wd, welcomeBanner,
    replArgs, initialColors,
    replCodeWrapper, scriptCodeWrapper, 
    alreadyLoadedDependencies, importHooks, 
    initialClassLoader, classPathWhitelist) { repl =>

  override val prompt = Ref("DynaML>")


}

object Defaults {

  val dynaml_install_dir = System.getenv("DYNAML_HOME")

  val root_dir = if (dynaml_install_dir != null) dynaml_install_dir else "."

  val welcomeBanner = {
    def ammoniteVersion = ammonite.Constants.version
    def scalaVersion = scala.util.Properties.versionNumberString
    def javaVersion = System.getProperty("java.version")
    def version = BuildInfo.version
    def banner = Source.fromFile(root_dir+"/conf/banner.txt").getLines.mkString("\n")

    Util.normalizeNewlines(
      banner+s"""\nWelcome to DynaML $version \nInteractive Scala shell for Machine Learning Research
                |
          |Currently running on:
                |(Scala $scalaVersion Java $javaVersion)
                |""".stripMargin
    )
  }


   // Need to import stuff from ammonite.ops manually, rather than from the
  // ammonite.ops.Extensions bundle, because otherwise they result in ambiguous
  // imports if someone else imports maunally
  val predefString = s"""
  |import ammonite.ops.{
  |  PipeableImplicit,
  |  FilterMapExtImplicit,
  |  FilterMapArraysImplicit,
  |  FilterMapIteratorsImplicit,
  |  FilterMapGeneratorsImplicit,
  |  SeqFactoryFunc,
  |  RegexContextMaker,
  |  Callable1Implicit
  |}
  |import ammonite.runtime.tools._
  |import ammonite.repl.tools._
  |import ammonite.interp.api.IvyConstructor.{ArtifactIdExt, GroupIdExt}
  |import ammonite.interp.api.InterpBridge.value.exit
  |""".stripMargin

val replPredef = """
  |import ammonite.repl.ReplBridge.value.{
  |  codeColorsImplicit,
  |  tprintColorsImplicit,
  |  pprinterImplicit,
  |  show,
  |  typeOf
  |}
""".stripMargin

def ammoniteHome = os.Path(System.getProperty("user.home"))/".ammonite"

def alreadyLoadedDependencies(
    resourceName: String = "amm-dependencies.txt"
  ): Seq[Dependency] = {

    var is: InputStream = null

    try {
      is = Thread.currentThread().getContextClassLoader.getResourceAsStream(resourceName)
      if (is == null)
        throw new Exception(s"Resource $resourceName not found")
      scala.io.Source.fromInputStream(is)(Codec.UTF8)
        .mkString
        .split('\n')
        .filter(_.nonEmpty)
        .map(l => l.split(':') match {
          case Array(org, name, ver) =>
            Dependency.of(org, name, ver)
          case other =>
            throw new Exception(s"Cannot parse line '$other' from resource $resourceName")
        })
    } finally {
      if (is != null)
        is.close()
    }
  }

  def dynaMlPredef = Source.fromFile(root_dir+"/conf/DynaMLInit.scala").getLines.mkString("\n")

}




object Compat{
  def copyAnnotatedType(c: Context)
    (tpe: c.universe.AnnotatedType,
      newAnnots: List[c.universe.Annotation]) = {
    import c.universe.compat._

    c.universe.AnnotatedType(newAnnots, tpe.underlying)
  }
}

object Cli{
  case class Arg[T, V](
    name: String,
    shortName: Option[Char],
    doc: String,
    action: (T, V) => T)(
    implicit val reader: scopt.Read[V]) {
    def runAction(t: T, s: String) = action(t, reader.reads(s))
  }
  case class Config(predefCode: String = "",
                    defaultPredef: Boolean = true,
                    homePredef: Boolean = true,
                    wd: os.Path = os.pwd,
                    welcomeBanner: Option[String] = Some(Defaults.welcomeBanner),
                    verboseOutput: Boolean = true,
                    remoteLogging: Boolean = true,
                    watch: Boolean = false,
                    bsp: Boolean = false,
                    code: Option[String] = None,
                    home: os.Path = Defaults.ammoniteHome,
                    predefFile: Option[os.Path] = None,
                    help: Boolean = false,
                    colored: Option[Boolean] = None,
                    classBased: Boolean = false,
                    thin: Boolean = false)


  import ammonite.repl.tools.Util.pathScoptRead
  val genericSignature = Seq(
    Arg[Config, String](
      "predef-code", None,
      "Any commands you want to execute at the start of the REPL session",
      (c, v) => c.copy(predefCode = v)
    ),

    Arg[Config, String](
      "code", Some('c'),
      "Pass in code to be run immediately in the REPL",
      (c, v) => c.copy(code = Some(v))
    ),
    Arg[Config, os.Path](
      "home", Some('h'),
      "The home directory of the REPL; where it looks for config and caches",
      (c, v) => c.copy(home = v)
    ),
    Arg[Config, os.Path](
      "predef", Some('p'),
      """Lets you load your predef from a custom location, rather than the
        |default location in your Ammonite home""".stripMargin,
      (c, v) => c.copy(predefFile = Some(v))
    ),
    Arg[Config, Unit](
      "no-home-predef", None,
      """Disables the default behavior of loading predef files from your
        |~/.ammonite/predef.sc, predefScript.sc, or predefShared.sc. You can
        |choose an additional predef to use using `--predef
        |""".stripMargin,
      (c, v) => c.copy(homePredef = false)
    ),

    Arg[Config, Unit](
      "no-default-predef", None,
      """Disable the default predef and run Ammonite with the minimal predef
        |possible
        |""".stripMargin,
      (c, v) => c.copy(defaultPredef = false)
    ),

    Arg[Config, Unit](
      "silent", Some('s'),
      """Make ivy logs go silent instead of printing though failures will
        |still throw exception""".stripMargin,
      (c, v) => c.copy(verboseOutput = false)
    ),
    Arg[Config, Unit](
      "help", None,
      """Print this message""".stripMargin,
      (c, v) => c.copy(help = true)
    ),
    Arg[Config, Boolean](
      "color", None,
      """Enable or disable colored output; by default colors are enabled
        |in both REPL and scripts if the console is interactive, and disabled
        |otherwise""".stripMargin,
      (c, v) => c.copy(colored = Some(v))
    ),
    Arg[Config, Unit](
      "watch", Some('w'),
      "Watch and re-run your scripts when they change",
      (c, v) => c.copy(watch = true)
    ),
    Arg[Config, Unit](
      "bsp", None,
      "Run a BSP server against the passed scripts",
      (c, v) => c.copy(bsp = true)
    ),
    Arg[Config, Unit](
      "thin", None,
      """Hide parts of the core of Ammonite and some of its dependencies. By default, the core of
        |Ammonite and all of its dependencies can be seen by users from the Ammonite session. This
        |option mitigates that via class loader isolation.
        |""".stripMargin,
      (c, v) => c.copy(thin=true)
    )
  )
  val replSignature = Seq(
    Arg[Config, String](
      "banner", Some('b'),
      "Customize the welcome banner that gets shown when Ammonite starts",
      (c, v) => c.copy(welcomeBanner = if (v.nonEmpty) Some(v) else None)
    ),
    Arg[Config, Unit](
      "no-remote-logging", None,
      """Disable remote logging of the number of times a REPL starts and runs
        |commands
        |""".stripMargin,
      (c, v) => c.copy(remoteLogging= false)
    ),
    Arg[Config, Unit](
      "class-based", None,
      """Wrap user code in classes rather than singletons, typically for Java serialization
        |friendliness.
        |""".stripMargin,
      (c, v) => c.copy(classBased=true)
    )

  )

  val ammoniteArgSignature = genericSignature ++ replSignature

  def showArg(arg: Arg[_, _]) =
    "  " + arg.shortName.fold("")("-" + _ + ", ") + "--" + arg.name

  def formatBlock(args: Seq[Arg[_, _]], leftMargin: Int) = {

    for(arg <- args) yield {
      showArg(arg).padTo(leftMargin, ' ').mkString +
      Predef.augmentString(arg.doc).lines.mkString(Util.newLine + " " * leftMargin)
    }
  }
  def ammoniteHelp = {
    val leftMargin = ammoniteArgSignature.map(showArg(_).length).max + 2


    s"""Ammonite REPL & Script-Runner, ${ammonite.Constants.version}
       |usage: amm [ammonite-options] [script-file [script-options]]
       |
       |${formatBlock(genericSignature, leftMargin).mkString(Util.newLine)}
       |
       |REPL-specific args:
       |${formatBlock(replSignature, leftMargin).mkString(Util.newLine)}
    """.stripMargin
  }

  def groupArgs[T](flatArgs: List[String],
                   args: Seq[Arg[T, _]],
                   initial: T): Either[String, (T, List[String])] = {

    val argsMap0: Seq[(String, Arg[T, _])] = args
      .flatMap{x => Seq(x.name -> x) ++ x.shortName.map(_.toString -> x)}

    val argsMap = argsMap0.toMap

    @tailrec def rec(keywordTokens: List[String],
                     current: T): Either[String, (T, List[String])] = {
      keywordTokens match{
        case head :: rest if head(0) == '-' =>
          val realName = if(head(1) == '-') head.drop(2) else head.drop(1)

          argsMap.get(realName) match {
            case Some(cliArg) =>
              if (cliArg.reader == scopt.Read.unitRead) {
                rec(rest, cliArg.runAction(current, ""))
              } else rest match{
                case next :: rest2 => rec(rest2, cliArg.runAction(current, next))
                case Nil => Left(s"Expected a value after argument $head")
              }

            case None => Right((current, keywordTokens))
          }

        case _ => Right((current, keywordTokens))

      }
    }
    rec(flatArgs, initial)
  }
}

/**
  * More or less a minimal version of Autowire's Server that lets you generate
  * a set of "routes" from the methods defined in an object, and call them
  * using passing in name/args/kwargs via Java reflection, without having to
  * generate/compile code or use Scala reflection. This saves us spinning up
  * the Scala compiler and greatly reduces the startup time of cached scripts.
  */
object Router{
  /**
    * Allows you to query how many things are overridden by the enclosing owner.
    */
  case class Overrides(value: Int)
  object Overrides{
    def apply()(implicit c: Overrides) = c.value
    implicit def generate: Overrides = macro impl
    def impl(c: Context): c.Tree = {
      import c.universe._
      q"new _root_.io.github.mandar2812.dynaml.repl.Router.Overrides(${c.internal.enclosingOwner.overrides.length})"
    }
  }

  class doc(s: String) extends StaticAnnotation
  class main extends StaticAnnotation
  def generateRoutes[T]: Seq[Router.EntryPoint[T]] = macro generateRoutesImpl[T]
  def generateRoutesImpl[T: c.WeakTypeTag](c: Context): c.Expr[Seq[EntryPoint[T]]] = {
    import c.universe._
    val r = new Router(c)
    val allRoutes = r.getAllRoutesForClass(
      weakTypeOf[T].asInstanceOf[r.c.Type]
    ).asInstanceOf[Iterable[c.Tree]]

    c.Expr[Seq[EntryPoint[T]]](q"_root_.scala.Seq(..$allRoutes)")
  }

  /**
    * Models what is known by the router about a single argument: that it has
    * a [[name]], a human-readable [[typeString]] describing what the type is
    * (just for logging and reading, not a replacement for a `TypeTag`) and
    * possible a function that can compute its default value
    */
  case class ArgSig[T](name: String,
                       typeString: String,
                       doc: Option[String],
                       default: Option[T => Any])

  def stripDashes(s: String) = {
    if (s.startsWith("--")) s.drop(2)
    else if (s.startsWith("-")) s.drop(1)
    else s
  }
  /**
    * What is known about a single endpoint for our routes. It has a [[name]],
    * [[argSignatures]] for each argument, and a macro-generated [[invoke0]]
    * that performs all the necessary argument parsing and de-serialization.
    *
    * Realistically, you will probably spend most of your time calling [[invoke]]
    * instead, which provides a nicer API to call it that mimmicks the API of
    * calling a Scala method.
    */
  case class EntryPoint[T](name: String,
                           argSignatures: Seq[ArgSig[T]],
                           doc: Option[String],
                           varargs: Boolean,
                           invoke0: (T, Map[String, String], Seq[String]) => Result[Any],
                           overrides: Int){
    def invoke(target: T, groupedArgs: Seq[(String, Option[String])]): Result[Any] = {
      var remainingArgSignatures = argSignatures.toList


      val accumulatedKeywords = mutable.Map.empty[ArgSig[T], mutable.Buffer[String]]
      val keywordableArgs = if (varargs) argSignatures.dropRight(1) else argSignatures

      for(arg <- keywordableArgs) accumulatedKeywords(arg) = mutable.Buffer.empty

      val leftoverArgs = mutable.Buffer.empty[String]

      val lookupArgSig = argSignatures.map(x => (x.name, x)).toMap

      var incomplete: Option[ArgSig[T]] = None

      for(group <- groupedArgs){

        group match{
          case (value, None) =>
            if (value.startsWith("-") && !varargs){
              lookupArgSig.get(stripDashes(value)) match{
                case None => leftoverArgs.append(value)
                case Some(sig) => incomplete = Some(sig)
              }

            } else remainingArgSignatures match {
              case Nil => leftoverArgs.append(value)
              case last :: Nil if varargs => leftoverArgs.append(value)
              case next :: rest =>
                accumulatedKeywords(next).append(value)
                remainingArgSignatures = rest
            }
          case (rawKey, Some(value)) =>
            val key = stripDashes(rawKey)
            lookupArgSig.get(key) match{
              case Some(x) if accumulatedKeywords.contains(x) =>
                if (accumulatedKeywords(x).nonEmpty && varargs){
                  leftoverArgs.append(rawKey, value)
                }else{
                  accumulatedKeywords(x).append(value)
                  remainingArgSignatures = remainingArgSignatures.filter(_.name != key)
                }
              case _ =>
                leftoverArgs.append(rawKey, value)
            }
        }
      }

      val missing0 = remainingArgSignatures.filter(_.default.isEmpty)
      val missing = if(varargs) {
        missing0.filter(_ != argSignatures.last)
      } else {
        missing0.filter(x => incomplete != Some(x))
      }
      val duplicates = accumulatedKeywords.toSeq.collect {
        case (k, l) if l.lengthCompare(1) > 0 =>
          k -> l.toSeq
      }

      if (
        incomplete.nonEmpty ||
          missing.nonEmpty ||
          duplicates.nonEmpty ||
          (leftoverArgs.nonEmpty && !varargs)
      ){
        Result.Error.MismatchedArguments(
          missing = missing,
          unknown = leftoverArgs.toSeq,
          duplicate = duplicates,
          incomplete = incomplete

        )
      } else {
        val mapping = accumulatedKeywords
          .iterator
          .collect{case (k, b) if b.lengthCompare(1) == 0 => (k.name, b.head)}
          .toMap

        try invoke0(target, mapping, leftoverArgs.toSeq)
        catch{case e: Throwable =>
          Result.Error.Exception(e)
        }
      }
    }
  }

  def tryEither[T](t: => T, error: Throwable => Result.ParamError) = {
    try Right(t)
    catch{ case e: Throwable => Left(error(e))}
  }
  def readVarargs[T](arg: ArgSig[_],
                     values: Seq[String],
                     thunk: String => T) = {
    val attempts =
      for(item <- values)
        yield tryEither(thunk(item), Result.ParamError.Invalid(arg, item, _))


    val bad = attempts.collect{ case Left(x) => x}
    if (bad.nonEmpty) Left(bad)
    else Right(attempts.collect{case Right(x) => x})
  }
  def read[T](dict: Map[String, String],
              default: => Option[Any],
              arg: ArgSig[_],
              thunk: String => T): FailMaybe = {
    dict.get(arg.name) match{
      case None =>
        tryEither(default.get, Result.ParamError.DefaultFailed(arg, _)).left.map(Seq(_))

      case Some(x) =>
        tryEither(thunk(x), Result.ParamError.Invalid(arg, x, _)).left.map(Seq(_))
    }
  }

  /**
    * Represents what comes out of an attempt to invoke an [[EntryPoint]].
    * Could succeed with a value, but could fail in many different ways.
    */
  sealed trait Result[+T]
  object Result{

    /**
      * Invoking the [[EntryPoint]] was totally successful, and returned a
      * result
      */
    case class Success[T](value: T) extends Result[T]

    /**
      * Invoking the [[EntryPoint]] was not successful
      */
    sealed trait Error extends Result[Nothing]
    object Error{

      /**
        * Invoking the [[EntryPoint]] failed with an exception while executing
        * code within it.
        */
      case class Exception(t: Throwable) extends Error

      /**
        * Invoking the [[EntryPoint]] failed because the arguments provided
        * did not line up with the arguments expected
        */
      case class MismatchedArguments(missing: Seq[ArgSig[_]],
                                     unknown: Seq[String],
                                     duplicate: Seq[(ArgSig[_], Seq[String])],
                                     incomplete: Option[ArgSig[_]]) extends Error
      /**
        * Invoking the [[EntryPoint]] failed because there were problems
        * deserializing/parsing individual arguments
        */
      case class InvalidArguments(values: Seq[ParamError]) extends Error
    }

    sealed trait ParamError
    object ParamError{
      /**
        * Something went wrong trying to de-serialize the input parameter;
        * the thrown exception is stored in [[ex]]
        */
      case class Invalid(arg: ArgSig[_], value: String, ex: Throwable) extends ParamError
      /**
        * Something went wrong trying to evaluate the default value
        * for this input parameter
        */
      case class DefaultFailed(arg: ArgSig[_], ex: Throwable) extends ParamError
    }
  }


  type FailMaybe = Either[Seq[Result.ParamError], Any]
  type FailAll = Either[Seq[Result.ParamError], Seq[Any]]

  def validate(args: Seq[FailMaybe]): Result[Seq[Any]] = {
    val lefts = args.collect{case Left(x) => x}.flatten

    if (lefts.nonEmpty) Result.Error.InvalidArguments(lefts)
    else {
      val rights = args.collect{case Right(x) => x}
      Result.Success(rights)
    }
  }

  def makeReadCall[T: scopt.Read](dict: Map[String, String],
                                     default: => Option[Any],
                                     arg: ArgSig[_]) = {
    read[T](dict, default, arg, implicitly[scopt.Read[T]].reads(_))
  }
  def makeReadVarargsCall[T: scopt.Read](arg: ArgSig[_],
                                         values: Seq[String]) = {
    readVarargs[T](arg, values, implicitly[scopt.Read[T]].reads(_))
  }
}

class Router [C <: Context](val c: C) {
  import c.universe._
  def getValsOrMeths(curCls: Type): Iterable[MethodSymbol] = {
    def isAMemberOfAnyRef(member: Symbol) = {
      // AnyRef is an alias symbol, we go to the real "owner" of these methods
      val anyRefSym = c.mirror.universe.definitions.ObjectClass
      member.owner == anyRefSym
    }
    val extractableMembers = for {
      member <- curCls.members.toList.reverse
      if !isAMemberOfAnyRef(member)
      if !member.isSynthetic
      if member.isPublic
      if member.isTerm
      memTerm = member.asTerm
      if memTerm.isMethod
      if !memTerm.isModule
    } yield memTerm.asMethod

    extractableMembers flatMap { case memTerm =>
      if (memTerm.isSetter || memTerm.isConstructor || memTerm.isGetter) Nil
      else Seq(memTerm)

    }
  }



  def extractMethod(meth: MethodSymbol, curCls: c.universe.Type): c.universe.Tree = {
    val baseArgSym = TermName(c.freshName())
    val flattenedArgLists = meth.paramss.flatten
    def hasDefault(i: Int) = {
      val defaultName = s"${meth.name}$$default$$${i + 1}"
      if (curCls.members.exists(_.name.toString == defaultName)) Some(defaultName)
      else None
    }
    val argListSymbol = q"${c.fresh[TermName]("argsList")}"
    val extrasSymbol = q"${c.fresh[TermName]("extras")}"
    val defaults = for ((arg, i) <- flattenedArgLists.zipWithIndex) yield {
      val arg = TermName(c.freshName())
      hasDefault(i).map(defaultName => q"($arg: $curCls) => $arg.${newTermName(defaultName)}")
    }

    def getDocAnnotation(annotations: List[Annotation]) = {
      val (docTrees, remaining) = annotations.partition(_.tpe =:= typeOf[Router.doc])
      val docValues = for {
        doc <- docTrees
        if doc.scalaArgs.head.isInstanceOf[Literal]
        l =  doc.scalaArgs.head.asInstanceOf[Literal]
        if l.value.value.isInstanceOf[String]
      } yield l.value.value.asInstanceOf[String]
      (remaining, docValues.headOption)
    }

    def unwrapVarargType(arg: Symbol) = {
      val vararg = arg.typeSignature.typeSymbol == definitions.RepeatedParamClass
      val unwrappedType =
        if (!vararg) arg.typeSignature
        else arg.typeSignature.asInstanceOf[TypeRef].args(0)

      (vararg, unwrappedType)
    }


    val (_, methodDoc) = getDocAnnotation(meth.annotations)
    val readArgSigs = for(
      ((arg, defaultOpt), i) <- flattenedArgLists.zip(defaults).zipWithIndex
    ) yield {

      val (vararg, varargUnwrappedType) = unwrapVarargType(arg)

      val default =
        if (vararg) q"scala.Some(scala.Nil)"
        else defaultOpt match {
          case Some(defaultExpr) => q"scala.Some($defaultExpr($baseArgSym))"
          case None => q"scala.None"
        }

      val (docUnwrappedType, docOpt) = varargUnwrappedType match{
        case t: AnnotatedType =>

          val (remaining, docValue) = getDocAnnotation(t.annotations)
          if (remaining.isEmpty) (t.underlying, docValue)
          else (Compat.copyAnnotatedType(c)(t, remaining), docValue)

        case t => (t, None)
      }

      val docTree = docOpt match{
        case None => q"scala.None"
        case Some(s) => q"scala.Some($s)"
      }
      val argSig = q"""
        _root_.io.github.mandar2812.dynaml.repl.Router.ArgSig(
          ${arg.name.toString},
          ${docUnwrappedType.toString + (if(vararg) "*" else "")},
          $docTree,
          $defaultOpt
        )
      """

      val reader =
        if(vararg) q"""
          _root_.io.github.mandar2812.dynaml.repl.Router.makeReadVarargsCall[$docUnwrappedType](
            $argSig,
            $extrasSymbol
          )
        """ else q"""
        _root_.io.github.mandar2812.dynaml.repl.Router.makeReadCall[$docUnwrappedType](
          $argListSymbol,
          $default,
          $argSig
        )
        """
      c.internal.setPos(reader, meth.pos)
      (reader, argSig, vararg)
    }

    val (readArgs, argSigs, varargs) = readArgSigs.unzip3
    val (argNames, argNameCasts) = flattenedArgLists.map { arg =>
      val (vararg, unwrappedType) = unwrapVarargType(arg)
      (
        pq"${arg.name.toTermName}",
        if (!vararg) q"${arg.name.toTermName}.asInstanceOf[$unwrappedType]"
        else q"${arg.name.toTermName}.asInstanceOf[Seq[$unwrappedType]]: _*"

      )
    }.unzip


    q"""
    _root_.io.github.mandar2812.dynaml.repl.Router.EntryPoint(
      ${meth.name.toString},
      scala.Seq(..$argSigs),
      ${methodDoc match{
        case None => q"scala.None"
        case Some(s) => q"scala.Some($s)"
      }},
      ${varargs.contains(true)},
      ($baseArgSym: $curCls, $argListSymbol: Map[String, String], $extrasSymbol: Seq[String]) =>
        _root_.io.github.mandar2812.dynaml.repl.Router.validate(Seq(..$readArgs)) match{
          case _root_.io.github.mandar2812.dynaml.repl.Router.Result.Success(List(..$argNames)) =>
            _root_.io.github.mandar2812.dynaml.repl.Router.Result.Success(
              $baseArgSym.${meth.name.toTermName}(..$argNameCasts)
            )
          case x: _root_.io.github.mandar2812.dynaml.repl.Router.Result.Error => x
        },
      _root_.io.github.mandar2812.dynaml.repl.Router.Overrides()
    )
    """
  }

  def hasMainAnnotation(t: MethodSymbol) = {
    t.annotations.exists(_.tpe =:= typeOf[Router.main])
  }
  def getAllRoutesForClass(curCls: Type,
                           pred: MethodSymbol => Boolean = hasMainAnnotation)
                            : Iterable[c.universe.Tree] = {
    for{
      t <- getValsOrMeths(curCls)
      if pred(t)
    } yield {
      extractMethod(t, curCls)
    }
  }
}


/**
  * Logic around using Ammonite as a script-runner; invoking scripts via the
  * macro-generated [[Router]], and pretty-printing any output or error messages
  */
  object Scripts {
    def groupArgs(flatArgs: List[String]): Seq[(String, Option[String])] = {
      var keywordTokens = flatArgs
      var scriptArgs = Vector.empty[(String, Option[String])]
  
      while(keywordTokens.nonEmpty) keywordTokens match{
        case List(head, next, rest@_*) if head.startsWith("-") =>
          scriptArgs = scriptArgs :+ (head, Some(next))
          keywordTokens = rest.toList
        case List(head, rest@_*) =>
          scriptArgs = scriptArgs :+ (head, None)
          keywordTokens = rest.toList
  
      }
      scriptArgs
    }
  
    def runScript(wd: os.Path,
                  path: os.Path,
                  interp: ammonite.interp.Interpreter,
                  scriptArgs: Seq[(String, Option[String])] = Nil) = {
      interp.watch(path)
      val (pkg, wrapper) = Util.pathToPackageWrapper(Seq(), path relativeTo wd)
  
      for{
        scriptTxt <- try Res.Success(Util.normalizeNewlines(os.read(path))) catch{
          case e: NoSuchFileException => Res.Failure("Script file not found: " + path)
        }
  
        processed <- interp.processModule(
          scriptTxt,
          CodeSource(wrapper, pkg, Seq(Name("ammonite"), Name("$file")), Some(path)),
          autoImport = true,
          // Not sure why we need to wrap this in a separate `$routes` object,
          // but if we don't do it for some reason the `generateRoutes` macro
          // does not see the annotations on the methods of the outer-wrapper.
          // It can inspect the type and its methods fine, it's just the
          // `methodsymbol.annotations` ends up being empty.
          extraCode = Util.normalizeNewlines(
            s"""
            |val $$routesOuter = this
            |object $$routes
            |extends scala.Function0[scala.Seq[_root_.io.github.mandar2812.dynaml.repl.Router.EntryPoint[$$routesOuter.type]]]{
            |  def apply() = _root_.io.github.mandar2812.dynaml.repl.Router.generateRoutes[$$routesOuter.type]
            |}
            """.stripMargin
          ),
          hardcoded = true
        )
  
        routeClsName <- processed.blockInfo.lastOption match{
          case Some(meta) => Res.Success(meta.id.wrapperPath)
          case None => Res.Skip
        }
  
        mainCls =
          interp
            .evalClassloader
            .loadClass(processed.blockInfo.last.id.wrapperPath + "$")
  
        routesCls =
          interp
            .evalClassloader
            .loadClass(routeClsName + "$$routes$")
  
        scriptMains =
          routesCls
              .getField("MODULE$")
              .get(null)
              .asInstanceOf[() => Seq[Router.EntryPoint[Any]]]
              .apply()
  
  
        mainObj = mainCls.getField("MODULE$").get(null)
  
        res <- Util.withContextClassloader(interp.evalClassloader){
          scriptMains match {
            // If there are no @main methods, there's nothing to do
            case Seq() =>
              if (scriptArgs.isEmpty) Res.Success(())
              else {
                val scriptArgString =
                  scriptArgs.flatMap{case (a, b) => Seq(a) ++ b}.map(literalize(_))
                            .mkString(" ")
  
                Res.Failure("Script " + path.last + " does not take arguments: " + scriptArgString)
              }
  
            // If there's one @main method, we run it with all args
            case Seq(main) => runMainMethod(mainObj, main, scriptArgs)
  
            // If there are multiple @main methods, we use the first arg to decide
            // which method to run, and pass the rest to that main method
            case mainMethods =>
              val suffix = formatMainMethods(mainObj, mainMethods)
              scriptArgs match{
                case Seq() =>
                  Res.Failure(
                    s"Need to specify a subcommand to call when running " + path.last + suffix
                  )
                case Seq((head, Some(_)), tail @ _*) =>
                  Res.Failure(
                    "To select a subcommand to run, you don't need --s." + Util.newLine +
                    s"Did you mean `${head.drop(2)}` instead of `$head`?"
                  )
                case Seq((head, None), tail @ _*) =>
                  mainMethods.find(_.name == head) match{
                    case None =>
                      Res.Failure(
                        s"Unable to find subcommand: " + backtickWrap(head) + suffix
                      )
                    case Some(main) =>
                      runMainMethod(mainObj, main, tail)
                  }
              }
          }
        }
      } yield res
    }
    def formatMainMethods[T](base: T, mainMethods: Seq[Router.EntryPoint[T]]) = {
      if (mainMethods.isEmpty) ""
      else{
        val leftColWidth = getLeftColWidth(mainMethods.flatMap(_.argSignatures))
  
        val methods =
          for(main <- mainMethods)
          yield formatMainMethodSignature(base, main, 2, leftColWidth)
  
        Util.normalizeNewlines(
          s"""
             |
             |Available subcommands:
             |
             |${methods.mkString(Util.newLine)}""".stripMargin
        )
      }
    }
    def getLeftColWidth[T](items: Seq[ArgSig[T]]) = {
      items.map(_.name.length + 2) match{
        case Nil => 0
        case x => x.max
      }
    }
    def formatMainMethodSignature[T](base: T,
                                     main: Router.EntryPoint[T],
                                     leftIndent: Int,
                                     leftColWidth: Int) = {
      // +2 for space on right of left col
      val args = main.argSignatures.map(renderArg(base, _, leftColWidth + leftIndent + 2 + 2, 80))
  
      val leftIndentStr = " " * leftIndent
      val argStrings =
        for((lhs, rhs) <- args)
          yield {
            val lhsPadded = lhs.padTo(leftColWidth, ' ')
            val rhsPadded = Predef.augmentString(rhs).lines.mkString(Util.newLine)
             s"$leftIndentStr  $lhsPadded  $rhsPadded"
          }
      val mainDocSuffix = main.doc match{
        case Some(d) => Util.newLine + leftIndentStr + softWrap(d, leftIndent, 80)
        case None => ""
      }
  
      s"""$leftIndentStr${main.name}$mainDocSuffix
         |${argStrings.map(_ + Util.newLine).mkString}""".stripMargin
    }
    def runMainMethod[T](base: T,
                         mainMethod: Router.EntryPoint[T],
                         scriptArgs: Seq[(String, Option[String])]): Res[Any] = {
      val leftColWidth = getLeftColWidth(mainMethod.argSignatures)
  
      def expectedMsg = formatMainMethodSignature(base: T, mainMethod, 0, leftColWidth)
  
      def pluralize(s: String, n: Int) = {
        if (n == 1) s else s + "s"
      }
  
      mainMethod.invoke(base, scriptArgs) match{
        case Router.Result.Success(x) => Res.Success(x)
        case Router.Result.Error.Exception(x: AmmoniteExit) => Res.Success(x.value)
        case Router.Result.Error.Exception(x) => Res.Exception(x, "")
        case Router.Result.Error.MismatchedArguments(missing, unknown, duplicate, incomplete) =>
          val missingStr =
            if (missing.isEmpty) ""
            else {
              val chunks =
                for (x <- missing)
                yield "--" + x.name + ": " + x.typeString
  
              val argumentsStr = pluralize("argument", chunks.length)
              s"Missing $argumentsStr: (${chunks.mkString(", ")})" + Util.newLine
            }
  
  
          val unknownStr =
            if (unknown.isEmpty) ""
            else {
              val argumentsStr = pluralize("argument", unknown.length)
              s"Unknown $argumentsStr: " + unknown.map(literalize(_)).mkString(" ") + Util.newLine
            }
  
          val duplicateStr =
            if (duplicate.isEmpty) ""
            else {
              val lines =
                for ((sig, options) <- duplicate)
                yield {
                  s"Duplicate arguments for (--${sig.name}: ${sig.typeString}): " +
                  options.map(literalize(_)).mkString(" ") + Util.newLine
                }
  
              lines.mkString
  
            }
          val incompleteStr = incomplete match{
            case None => ""
            case Some(sig) =>
              s"Option (--${sig.name}: ${sig.typeString}) is missing a corresponding value" +
              Util.newLine
  
            }
  
          Res.Failure(
            Util.normalizeNewlines(
              s"""$missingStr$unknownStr$duplicateStr$incompleteStr
                 |Arguments provided did not match expected signature:
                 |
                 |$expectedMsg
                 |""".stripMargin
            )
          )
  
        case Router.Result.Error.InvalidArguments(x) =>
          val argumentsStr = pluralize("argument", x.length)
          val thingies = x.map{
            case Router.Result.ParamError.Invalid(p, v, ex) =>
              val literalV = literalize(v)
              val rendered = {renderArgShort(p)}
              s"$rendered: ${p.typeString} = $literalV failed to parse with $ex"
            case Router.Result.ParamError.DefaultFailed(p, ex) =>
              s"${renderArgShort(p)}'s default value failed to evaluate with $ex"
          }
  
          Res.Failure(
            Util.normalizeNewlines(
              s"""The following $argumentsStr failed to parse:
                |
                |${thingies.mkString(Util.newLine)}
                |
                |expected signature:
                |
                |$expectedMsg
              """.stripMargin
            )
          )
      }
    }
  
    def softWrap(s: String, leftOffset: Int, maxWidth: Int) = {
      val oneLine = Predef.augmentString(s).lines.mkString(" ").split(' ')
  
      lazy val indent = " " * leftOffset
  
      val output = new StringBuilder(oneLine.head)
      var currentLineWidth = oneLine.head.length
      for(chunk <- oneLine.tail){
        val addedWidth = currentLineWidth + chunk.length + 1
        if (addedWidth > maxWidth){
          output.append(Util.newLine + indent)
          output.append(chunk)
          currentLineWidth = chunk.length
        } else{
          currentLineWidth = addedWidth
          output.append(' ')
          output.append(chunk)
        }
      }
      output.mkString
    }
    def renderArgShort[T](arg: ArgSig[T]) = "--" + backtickWrap(arg.name)
    def renderArg[T](base: T,
                     arg: ArgSig[T],
                     leftOffset: Int,
                     wrappedWidth: Int): (String, String) = {
      val suffix = arg.default match{
        case Some(f) => " (default " + f(base) + ")"
        case None => ""
      }
      val docSuffix = arg.doc match{
        case Some(d) => ": " + d
        case None => ""
      }
      val wrapped = softWrap(
        arg.typeString + suffix + docSuffix,
        leftOffset,
        wrappedWidth - leftOffset
      )
      (renderArgShort(arg), wrapped)
    }
  
  
    def mainMethodDetails[T](ep: EntryPoint[T]) = {
      ep.argSignatures.collect{
        case ArgSig(name, tpe, Some(doc), default) =>
          Util.newLine + name + " // " + doc
      }.mkString
    }
  
  }

/**
  * Give Ammonite the ability to read (linux) system proxy environment variables
  * and convert them into java proxy properties. Which allows Ammonite to work
  * through proxy automatically, instead of setting `System.properties` manually.
  *
  * See issue 460.
  *
  * Parameter pattern:
  * https://docs.oracle.com/javase/7/docs/api/java/net/doc-files/net-properties.html
  *
  * Created by cuz on 17-5-21.
  */
  private[dynaml] object ProxyFromEnv {
    private lazy val KeyPattern ="""([\w\d]+)_proxy""".r
    private lazy val UrlPattern ="""([\w\d]+://)?(.+@)?([\w\d\.\-]+):(\d+)/?""".r
  
    /**
      * Get current proxy environment variables.
      */
    private def getEnvs =
      sys.env.map { case (k, v) => (k.toLowerCase, v.toLowerCase) }
        .filterKeys(_.endsWith("proxy"))
        .toMap
  
    /**
      * Convert single proxy environment variable to corresponding system proxy properties.
      */
    private def envToProps(env: (String, String)): Map[String, String] = env match {
      case ("no_proxy", noProxySeq) =>
        val converted = noProxySeq.split(""",""").mkString("|")
        //https uses the same as http's. Ftp need not to be set here.
        Map("http.nonProxyHosts" -> converted)
  
      case (KeyPattern(proto), UrlPattern(_, cred, host, port)) =>
        val propHost = s"$proto.proxyHost" -> host
        val propPort = s"$proto.proxyPort" -> port
        val propCred = if (cred.isDefined) {
          val credPair = cred.dropRight(1).split(":")
          val propUser = s"$proto.proxyUser" -> credPair.head
          val propPassword = credPair.drop(1).map(s"$proto.proxyPassword" -> _)
          Seq(propUser) ++ propPassword
        } else Nil
        Seq(propHost, propPort) ++ propCred toMap
      case bad => Map.empty
    }
  
  
    /**
      * Set system proxy properties from environment variables.
      * Existing properties will not be overwritten.
      */
    def setPropProxyFromEnv(envs: Map[String, String] = this.getEnvs): Unit = {
      val sysProps = sys.props
      val proxyProps = envs.flatMap { env =>
        val props = envToProps(env)
        props
      }.filter(p => !sysProps.exists(sp => sp._1 == p._1))
      sysProps ++= proxyProps
    }
  
    /**
      * helper implicit conversion: add isDefined method to String.
      */
    implicit private class StringIsDefined(s: String) {
      def isDefined: Boolean = s != null && s.length > 0
    }
  
  }