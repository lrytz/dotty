package dotty.tools
package dotc
package reporting

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.util.SourcePosition

import java.util.regex.PatternSyntaxException
import scala.annotation.internal.sharable
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

enum MessageFilter:
  def matches(message: Diagnostic): Boolean = this match {
    case Any => true
    case Deprecated => message.isInstanceOf[Diagnostic.DeprecationWarning]
    case Feature => message.isInstanceOf[Diagnostic.FeatureWarning]
    case MessagePattern(pattern) =>
      val noHighlight = message.msg.rawMessage.replaceAll("\\e\\[[\\d;]*[^\\d;]","")
      pattern.findFirstIn(noHighlight).nonEmpty
    case None => false
  }
  case Any, Deprecated, Feature, None
  case MessagePattern(pattern: Regex)

enum Action:
  case Error, Warning, Info, Silent

final case class WConf(confs: List[(List[MessageFilter], Action)]):
  def action(message: Diagnostic): Action = confs.collectFirst {
    case (filters, action) if filters.forall(_.matches(message)) => action
  }.getOrElse(Action.Warning)

@sharable object WConf:
  import Action._
  import MessageFilter._

  private type Conf = (List[MessageFilter], Action)

  def parseAction(s: String): Either[List[String], Action] = s match {
    case "error" | "e"            => Right(Error)
    case "warning" | "w"          => Right(Warning)
    case "info" | "i"             => Right(Info)
    case "silent" | "s"           => Right(Silent)
    case _                        => Left(List(s"unknown action: `$s`"))
  }

  private def regex(s: String) =
    try Right(s.r)
    catch { case e: PatternSyntaxException => Left(s"invalid pattern `$s`: ${e.getMessage}") }

  val splitter = raw"([^=]+)=(.+)".r

  def parseFilter(s: String): Either[String, MessageFilter] = s match {
    case "any" => Right(Any)
    case splitter(filter, conf) => filter match {
      case "msg" => regex(conf).map(MessagePattern.apply)
      case "cat" =>
        conf match {
          case "deprecation" => Right(Deprecated)
          case "feature"     => Right(Feature)
          case _             => Left(s"unknown category: $conf")
        }
      case _ => Left(s"unknown filter: $filter")
    }
    case _ => Left(s"unknown filter: $s")
  }

  private var parsedCache: (List[String], WConf) = null
  def parsed(using Context): WConf =
    val setting = ctx.settings.Wconf.value
    if parsedCache == null || parsedCache._1 != setting then
      val conf = fromSettings(setting)
      parsedCache = (setting, conf.getOrElse(WConf(Nil)))
      conf.swap.foreach(msgs =>
        val multiHelp =
          if (setting.sizeIs > 1)
            """
              |Note: for multiple filters, use `-Wconf:filter1:action1,filter2:action2`
              |      or alternatively          `-Wconf:filter1:action1 -Wconf:filter2:action2`""".stripMargin
          else ""
        report.warning(s"Failed to parse `-Wconf` configuration: ${ctx.settings.Wconf.value.mkString(",")}\n${msgs.mkString("\n")}$multiHelp"))
    parsedCache._2

  def fromSettings(settings: List[String]): Either[List[String], WConf] =
    if (settings.isEmpty) Right(WConf(Nil))
    else {
      val parsedConfs: List[Either[List[String], (List[MessageFilter], Action)]] = settings.map(conf => {
        val parts = conf.split("[&:]") // TODO: don't split on escaped \&
        val (ms, fs) = parts.view.init.map(parseFilter).toList.partitionMap(identity)
        if (ms.nonEmpty) Left(ms)
        else if (fs.isEmpty) Left(List("no filters or no action defined"))
        else parseAction(parts.last).map((fs, _))
      })
      val (ms, fs) = parsedConfs.partitionMap(identity)
      if (ms.nonEmpty) Left(ms.flatten)
      else Right(WConf(fs))
    }

case class Suppression(annotPos: SourcePosition, filters: List[MessageFilter], start: Int, end: Int):
  private[this] var _used = false
  def used: Boolean = _used
  def markUsed(): Unit = { _used = true }

  def matches(dia: Diagnostic): Boolean = {
    val pos = dia.pos
    pos.exists && start <= pos.start && pos.end <= end && filters.forall(_.matches(dia))
  }
