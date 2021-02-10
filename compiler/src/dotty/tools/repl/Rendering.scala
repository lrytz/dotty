package dotty.tools
package repl

import java.io.{ StringWriter, PrintWriter }
import java.lang.{ ClassLoader, ExceptionInInitializerError }
import java.lang.reflect.InvocationTargetException

import dotc.ast.tpd
import dotc.core.Contexts._
import dotc.core.Denotations.Denotation
import dotc.core.Flags
import dotc.core.Flags._
import dotc.core.Symbols.{Symbol, defn}
import dotc.core.StdNames.{nme, str}
import dotc.core.NameOps._
import dotc.printing.ReplPrinter
import dotc.reporting.{MessageRendering, Message, Diagnostic}
import dotc.util.SourcePosition

/** This rendering object uses `ClassLoader`s to accomplish crossing the 4th
 *  wall (i.e. fetching back values from the compiled class files put into a
 *  specific class loader capable of loading from memory) and rendering them.
 *
 *  @pre this object should be paired with a compiler session, i.e. when
 *       `ReplDriver#resetToInitial` is called, the accompanying instance of
 *       `Rendering` is no longer valid.
 */
private[repl] class Rendering(parentClassLoader: Option[ClassLoader] = None) {

  import Rendering._

  private val MaxStringElements: Int = 1000  // no need to mkString billions of elements

  /** A `MessageRenderer` for the REPL without file positions */
  private val messageRenderer = new MessageRendering {
    override def posStr(pos: SourcePosition, diagnosticLevel: String, message: Message)(using Context): String = ""
  }

  private var myClassLoader: ClassLoader = _

  private var myReplStringOf: Object => String = _


  /** Class loader used to load compiled code */
  private[repl] def classLoader()(using Context) =
    if (myClassLoader != null) myClassLoader
    else {
      val parent = parentClassLoader.getOrElse {
        val compilerClasspath = ctx.platform.classPath(using ctx).asURLs
        new java.net.URLClassLoader(compilerClasspath.toArray, null)
      }

      myClassLoader = new AbstractFileClassLoader(ctx.settings.outputDir.value, parent)
      myReplStringOf = {
        // We need to use the ScalaRunTime class coming from the scala-library
        // on the user classpath, and not the one available in the current
        // classloader, so we use reflection instead of simply calling
        // `ScalaRunTime.replStringOf`.
        val scalaRuntime = Class.forName("scala.runtime.ScalaRunTime", true, myClassLoader)
        val meth = scalaRuntime.getMethod("replStringOf", classOf[Object], classOf[Int])

        (value: Object) => meth.invoke(null, value, Integer.valueOf(MaxStringElements)).asInstanceOf[String]
      }
      myClassLoader
    }

  /** Used to elide output in replStringOf. TODO: Implement setting scala.repl.maxprintstring as in Scala 2 */
  private[repl] def truncate(str: String): String =
    if str.length > MaxStringElements then str.take(MaxStringElements - 3) + "..."
    else str

  /** Return a String representation of a value we got from `classLoader()`. */
  private[repl] def replStringOf(value: Object)(using Context): String = {

    assert(myReplStringOf != null,
      "replStringOf should only be called on values creating using `classLoader()`, but `classLoader()` has not been called so far")
    truncate(myReplStringOf(value))
  }

  /** Load the value of the symbol using reflection.
   *
   *  Calling this method evaluates the expression using reflection
   */
  private def valueOf(sym: Symbol)(using Context): Option[String] = {
    val objectName = sym.owner.fullName.encode.toString.stripSuffix("$")
    val resObj: Class[?] = Class.forName(objectName, true, classLoader())
    val value =
      resObj
        .getDeclaredMethods.find(_.getName == sym.name.encode.toString)
        .map(_.invoke(null))
    val string = value.map(replStringOf(_).trim)
    if (!sym.is(Flags.Method) && sym.info == defn.UnitType)
      None
    else
      string.map { s =>
        if (s.startsWith(str.REPL_SESSION_LINE))
          s.drop(str.REPL_SESSION_LINE.length).dropWhile(c => c.isDigit || c == '$')
        else
          s
      }
  }

  /** Formats errors using the `messageRenderer` */
  def formatError(dia: Diagnostic)(implicit state: State): Diagnostic =
    new Diagnostic(
      messageRenderer.messageAndPos(dia.msg, dia.pos, messageRenderer.diagnosticLevel(dia))(using state.context),
      dia.pos,
      dia.level
    )

  def renderTypeDef(d: Denotation)(using Context): Diagnostic =
    infoDiagnostic("// defined " ++ d.symbol.showUser, d)

  def renderTypeAlias(d: Denotation)(using Context): Diagnostic =
    infoDiagnostic("// defined alias " ++ d.symbol.showUser, d)

  /** Render method definition result */
  def renderMethod(d: Denotation)(using Context): Diagnostic =
    infoDiagnostic(d.symbol.showUser, d)

  /** Render value definition result */
  def renderVal(d: Denotation)(using Context): Option[Diagnostic] =
    val dcl = d.symbol.showUser
    def msg(s: String) = infoDiagnostic(s, d)
    try
      if (d.symbol.is(Flags.Lazy)) Some(msg(dcl))
      else valueOf(d.symbol).map(value => msg(s"$dcl = $value"))
    catch case e: InvocationTargetException => Some(msg(renderError(e, d)))
  end renderVal

  /** Force module initialization in the absence of members. */
  def forceModule(sym: Symbol)(using Context): Seq[Diagnostic] =
    def load() =
      val objectName = sym.fullName.encode.toString
      Class.forName(objectName, true, classLoader())
      Nil
    try load() catch case e: ExceptionInInitializerError => List(infoDiagnostic(renderError(e, sym.denot), sym.denot))

  /** Render the stack trace of the underlying exception. */
  private def renderError(ite: InvocationTargetException | ExceptionInInitializerError, d: Denotation)(using Context): String =
    import dotty.tools.dotc.util.StackTraceOps._
    val cause = ite.getCause match
      case e: ExceptionInInitializerError => e.getCause
      case e => e
    def isWrapperCode(ste: StackTraceElement) =
      ste.getClassName == d.symbol.owner.name.show
      && (ste.getMethodName == nme.STATIC_CONSTRUCTOR.show || ste.getMethodName == nme.CONSTRUCTOR.show)

    cause.formatStackTracePrefix(!isWrapperCode(_))
  end renderError

  private def infoDiagnostic(msg: String, d: Denotation)(using Context): Diagnostic =
    new Diagnostic.Info(msg, d.symbol.sourcePos)

}

object Rendering {

  extension (s: Symbol)
    def showUser(using Context): String = {
      val printer = new ReplPrinter(ctx)
      val text = printer.dclText(s)
      text.mkString(ctx.settings.pageWidth.value, ctx.settings.printLines.value)
    }

}
