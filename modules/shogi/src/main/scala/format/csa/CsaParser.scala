package shogi
package format
package csa

import variant.Variant

import scala.util.parsing.combinator._
import scalaz.Validation.FlatMap._
import scalaz.Validation.{ success => succezz }

// https://gist.github.com/Marken-Foo/b1047990ee0c65537582ebe591e2b6d7
object CsaParser extends scalaz.syntax.ToTraverseOps {

  // Helper strings for regex, so we don't have to repeat ourselves that much
  val colorsS     = """\+|-"""
  val positionS   = """[1-9][1-9]"""
  val dropOriginS = """00"""
  val piecesS     = """OU|HI|RY|KA|UM|KI|GI|NG|KE|NK|KY|NY|FU|TO"""
  val handPiecesS = """HI|KA|KI|GI|KE|KY|FU"""

  val moveOrDropRegex =
    raw"""($colorsS)?($positionS|$dropOriginS)($positionS)($piecesS)""".r

  case class StrMove(
      move: String,
      comments: List[String],
      timeSpent: Option[Centis] = None
  )

  def full(csa: String): Valid[ParsedNotation] =
    try {
      val preprocessed = augmentString(csa).linesIterator
        .map(l => if ((l lift 0) != Some('\'')) l.replace(",", "\n").trim else l.trim) // keep ',' in comments
        .filter(l => l.nonEmpty && l != "'")
        .mkString("\n")
        .replace("‑", "-")
        .replace("–", "-")
        .replace('　', ' ')
        .replace("：", ":")
      for {
        splitted <- splitHeaderAndMoves(preprocessed)
        headerStr = splitted._1
        movesStr  = splitted._2
        splitted3 <- splitMetaAndBoard(headerStr)
        metaStr  = splitted3._1
        boardStr = splitted3._2
        preTags     <- TagParser(metaStr)
        parsedMoves <- MovesParser(movesStr)
        strMoves          = parsedMoves._1
        terminationOption = parsedMoves._2
        init      <- getComments(headerStr)
        situation <- CsaParserHelper.parseSituation(boardStr, variant.Standard)
        termTags  = terminationOption.filterNot(_ => preTags.exists(_.Termination)).foldLeft(preTags)(_ + _)
        boardTags = termTags + Tag(_.FEN, Forsyth.exportSituation(situation))
        tags = CsaParserHelper
          .createResult(
            boardTags,
            Color((strMoves.size + { if (situation.color == Gote) 1 else 0 }) % 2 == 0)
          )
          .filterNot(_ => preTags.exists(_.Result))
          .foldLeft(boardTags)(_ + _)
        parsedMoves <- objMoves(strMoves, tags.variant | Variant.default)
        _ <-
          if (csa.isEmpty || parsedMoves.value.nonEmpty || tags.exists(_.FEN)) succezz(true)
          else "No moves nor initial position".failureNel
      } yield ParsedNotation(init, tags, parsedMoves)
    } catch {
      case _: StackOverflowError =>
        println(csa)
        sys error "### StackOverflowError ### in CSA parser"
    }

  def objMoves(strMoves: List[StrMove], variant: Variant): Valid[ParsedMoves] = {
    strMoves.map { case StrMove(moveStr, comments, timeSpent) =>
      (
        MoveParser(moveStr, variant) map { m =>
          m withComments comments withTimeSpent timeSpent
        }
      ): Valid[ParsedMove]
    }.sequence map ParsedMoves.apply
  }

  def cleanComments(comments: List[String]) = comments.map(_.trim.take(2000)).filter(_.nonEmpty)

  trait Logging { self: Parsers =>
    protected val loggingEnabled = false
    protected def as[T](msg: String)(p: => Parser[T]): Parser[T] =
      if (loggingEnabled) log(p)(msg) else p
  }

  object MovesParser extends RegexParsers with Logging {

    override val whiteSpace = """(\s|\t|\r?\n)+""".r

    def apply(csaMoves: String): Valid[(List[StrMove], Option[Tag])] = {
      parseAll(strMoves, csaMoves) match {
        case Success((moves, termination), _) =>
          succezz(
            (
              moves,
              termination map { r =>
                Tag(_.Termination, r)
              }
            )
          )
        case err => "Cannot parse moves from csa: %s\n%s".format(err.toString, csaMoves).failureNel
      }
    }

    def strMoves: Parser[(List[StrMove], Option[String])] =
      as("moves") {
        (strMove *) ~ (termination *) ~ (commentary *) ^^ { case parsedMoves ~ term ~ coms =>
          (updateLastComments(parsedMoves, cleanComments(coms)), term.headOption)
        }
      }

    def strMove: Parser[StrMove] =
      as("move") {
        (commentary *) ~>
          (moveOrDropRegex ~ opt(clock) ~ rep(commentary)) <~
          (moveExtras *) ^^ { case move ~ clk ~ comments =>
            StrMove(move, cleanComments(comments), clk.flatten)
          }
      }

    private val clockSecondsRegex = """(\d++)""".r

    private def readCentis(seconds: String): Option[Centis] =
      seconds.toDoubleOption match {
        case Some(s) => Centis(BigDecimal(s * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt).some
        case _       => none
      }

    private def parseClock(str: String): Option[Centis] = {
      str match {
        case clockSecondsRegex(seconds) => readCentis(seconds)
        case _                          => None
      }
    }

    private def updateLastComments(moves: List[StrMove], comments: List[String]): List[StrMove] = {
      val index = moves.size - 1
      (moves lift index).fold(moves) { move =>
        moves.updated(index, move.copy(comments = move.comments ::: comments))
      }
    }

    def clock: Parser[Option[Centis]] =
      as("clock") {
        """T""".r ~>
          clockSecondsRegex ^^ { case spent =>
            parseClock(spent)
          }
      }

    def moveExtras: Parser[Unit] =
      as("moveExtras") {
        commentary.^^^(())
      }

    def commentary: Parser[String] =
      as("commentary") {
        """'""" ~> """.+""".r
      }

    def termination: Parser[String] =
      as("termination") {
        "%" ~> termValue ~ opt(clock) ^^ { case term ~ _ =>
          term
        }
      }

    val termValue: Parser[String] =
      "CHUDAN" | "TORYO" | "JISHOGI" | "SENNICHITE" | "TSUMI" | "TIME_UP" | "ILLEGAL_MOVE" | "+ILLEGAL_ACTION" | "-ILLEGAL_ACTION" | "KACHI" | "HIKIWAKE" | "FUZUMI" | "ERROR"
  }

  object MoveParser extends RegexParsers with Logging {

    val MoveRegex =
      raw"""^(?:${colorsS})?($positionS)($positionS)($piecesS)""".r
    val DropRegex = raw"""^(?:${colorsS})?(?:$dropOriginS)($positionS)($handPiecesS)""".r

    override def skipWhitespace = false

    def apply(str: String, variant: Variant): Valid[ParsedMove] = {
      str match {
        case MoveRegex(origS, destS, roleS) => {
          for {
            role <- variant.rolesByCsa get roleS toValid s"Uknown role in move: $str"
            dest <- Pos.numberAllKeys get destS toValid s"Cannot parse destination sqaure in move: $str"
            orig <- Pos.numberAllKeys get origS toValid s"Cannot parse origin sqaure in move: $str"
          } yield CsaStd(
              dest = dest,
              role = role,
              orig = orig,
              metas = Metas(
                check = false,
                checkmate = false,
                comments = Nil,
                glyphs = Glyphs.empty,
                variations = Nil,
                timeSpent = None,
                timeTotal = None
              )
            )
        }
        case DropRegex(posS, roleS) =>
          for {
            role <- variant.rolesByCsa get roleS toValid s"Uknown role in drop: $str"
            pos <- Pos.numberAllKeys get posS toValid s"Cannot parse destination sqaure in drop: $str"
          } yield Drop(
              role = role,
              pos = pos,
              metas = Metas(
                check = false,
                checkmate = false,
                comments = Nil,
                glyphs = Glyphs.empty,
                variations = Nil,
                timeSpent = None,
                timeTotal = None
              )
            )
        case _ => "Cannot parse move/drop: %s\n".format(str).failureNel
      }
    }
  }

  object TagParser extends RegexParsers with Logging {

    def apply(csa: String): Valid[Tags] =
      parseAll(all, csa) match {
        case f: Failure       => "Cannot parse csa tags: %s\n%s".format(f.toString, csa).failureNel
        case Success(tags, _) => succezz(Tags(tags.filter(_.value.nonEmpty)))
        case err              => "Cannot parse csa tags: %s\n%s".format(err.toString, csa).failureNel
      }

    def all: Parser[List[Tag]] =
      as("all") {
        rep(tags) <~ """(.|\n)*""".r
      }

    def tags: Parser[Tag] = tag | playerTag

    def tag: Parser[Tag] =
      "$" ~>
        """\w+""".r ~ ":" ~ """.*""".r ^^ { case name ~ _ ~ value =>
          Tag(normalizeCsaName(name), value)
        }

    def playerTag: Parser[Tag] =
      """N""" ~>
        """[\+|-].*""".r ^^ { case line =>
          Tag(normalizeCsaName(line.slice(0, 1)), line.drop(1))
        }
  }

  private def normalizeCsaName(str: String): String =
    Tag.csaNameToTag.get(str).fold(str)(_.lowercase)

  private def getComments(csa: String): Valid[InitialPosition] =
    augmentString(csa).linesIterator.to(List).map(_.trim).filter(_.nonEmpty) filter { line =>
      line.startsWith("'")
    } match {
      case (comms) => succezz(InitialPosition(comms.map(_.drop(1).trim)))
    }

  private def splitHeaderAndMoves(csa: String): Valid[(String, String)] =
    augmentString(csa).linesIterator.to(List).map(_.trim).filter(_.nonEmpty) span { line =>
      !(moveOrDropRegex.matches(line))
    } match {
      case (headerLines, moveLines) => succezz(headerLines.mkString("\n") -> moveLines.mkString("\n"))
    }

  private def splitMetaAndBoard(csa: String): Valid[(String, String)] =
    augmentString(csa).linesIterator
      .to(List)
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("'")) partition { line =>
      !((line startsWith "P") || (line == "+") || (line == "-"))
    } match {
      case (metaLines, boardLines) => succezz(metaLines.mkString("\n") -> boardLines.mkString("\n"))
    }
}