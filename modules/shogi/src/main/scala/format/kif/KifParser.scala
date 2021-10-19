package shogi
package format
package kif

import variant.Variant

import scala.util.parsing.combinator._
import cats.data.Validated
import cats.data.Validated.{ invalid, valid }
import cats.implicits._

// We are keeping the original interface of the parser
// Instead of being strict with what is a valid kif
// we are gonna try to be rather benevolent.
// Both half-width and full-width numbers are accepted (not in clock times)
// All reasonable position formats (1一, 1a, 11) will be accepted
// Pieces can either be kanji(1 or 2) or english letters (sfen)

// https://gist.github.com/Marken-Foo/7694548af1f562ecd01fba6b60a9c96a
object KifParser {

  // Helper strings for regex, so we don't have to repeat ourselves that much
  val colorsS        = """▲|△|☗|☖"""
  val numbersS       = """[1-9１-９一二三四五六七八九十百][0-9０-９一二三四五六七八九十百]*"""
  val positionS      = """[1-9１-９一二三四五六七八九][1-9a-i１-９一二三四五六七八九]|同"""
  val piecesJPS      = """玉|王|飛|龍|角|馬|金|銀|成銀|桂|成桂|香|成香|歩|と|竜|全|圭|今|杏|仝|个|兵"""
  val handPiecesJPS  = """飛|角|金|銀|桂|香|歩|兵"""
  val piecesENGS     = """K|R|\+R|B|\+B|G|S|\+G|N|\+N|L|\+L|P|\+P"""
  val handPiecesENGS = """R|B|G|S|N|L|P"""
  val promotionS     = """不成|成|\+"""
  val dropS          = """打"""
  val parsS          = """\(|（|\)|）"""

  val moveOrDropRegex =
    raw"""(${colorsS})?(${positionS})\s?(${piecesJPS}|${piecesENGS})(((${promotionS})?(${parsS})?(${positionS})(${parsS})?)|(${dropS}))""".r

  val moveOrDropLineRegex =
    raw"""(${numbersS}[\s\.。]{1,})?(${colorsS})?(${positionS})\s?(${piecesJPS}|${piecesENGS})(((${promotionS})?(${parsS})?${positionS}(${parsS})?)|${dropS})""".r.unanchored

  val commentRegex =
    raw"""\*|＊""".r

  case class StrMove(
      turnNumber: Option[Int],
      move: String,
      comments: List[String],
      timeSpent: Option[Centis] = None,
      timeTotal: Option[Centis] = None
  )

  case class StrVariation(
      variationStart: Int,
      moves: List[StrMove],
      variations: List[StrVariation]
  )

  def full(kif: String): Validated[String, ParsedNotation] =
    try {
      val preprocessed = augmentString(cleanKif(kif)).linesIterator
        .collect {
          case l if !commentRegex.matches(l.trim.take(1)) =>
            l.split("#|&").headOption.getOrElse("").trim
          case l => l.trim
        } // remove # or & comments, but keep them in * comments
        .filterNot(l => l.isEmpty || l.startsWith("まで") || commentRegex.matches(l))
        .mkString("\n")
      for {
        splitted <- splitHeaderAndRest(preprocessed)
        headerStr = splitted._1
        restStr   = splitted._2
        splitted2 <- splitMovesAndVariations(restStr)
        movesStr     = splitted2._1
        variationStr = splitted2._2
        splitted3 <- splitMetaAndBoard(headerStr)
        metaStr  = splitted3._1
        boardStr = splitted3._2
        preTags <- TagParser(metaStr)
        variant = preTags.variant | Variant.default
        parsedMoves <- MovesParser(movesStr)
        strMoves          = parsedMoves._1
        terminationOption = parsedMoves._2
        init             <- getComments(headerStr)
        parsedVariations <- VariationParser(variationStr)
        variations = createVariations(parsedVariations)
        situation <- KifParserHelper.parseSituation(boardStr, preTags(_.Handicap), variant)
        tags = createTags(preTags, situation, strMoves.size, terminationOption)
        parsedMoves <- objMoves(strMoves, variant, variations)
        _ <-
          if (kif.isEmpty || parsedMoves.value.nonEmpty || tags.knownTypes.value.nonEmpty)
            valid(true)
          else invalid("No moves, non-standard starting position or valid tags provided")
      } yield ParsedNotation(init, tags, parsedMoves)
    } catch {
      case _: StackOverflowError =>
        println(kif)
        sys error "### StackOverflowError ### in KIF parser"
    }

  def objMoves(
      strMoves: List[StrMove],
      variant: Variant,
      variations: List[StrVariation],
      startDest: Option[Pos] = None,
      startNum: Int = 1
  ): Validated[String, ParsedMoves] = {
    // No need to store 0s that mean nothing
    val uselessTimes =
      strMoves.forall(m => m.timeSpent.fold(true)(_ == Centis(0)) && m.timeTotal.fold(true)(_ == Centis(0)))

    var lastDest: Option[Pos]        = startDest
    var bMoveNumber                  = startNum
    var res: List[Validated[String, ParsedMove]] = List()

    for (StrMove(moveNumber, moveStr, comments, timeSpent, timeTotal) <- strMoves) {
      val move: Validated[String, ParsedMove] = MoveParser(moveStr, lastDest, variant) map { m =>
        val m1 = m withComments comments withVariations {
          variations
            .filter(_.variationStart == moveNumber.getOrElse(bMoveNumber))
            .map { v =>
              objMoves(v.moves, variant, v.variations, lastDest, bMoveNumber + 1) getOrElse ParsedMoves.empty
            }
            .filter(_.value.nonEmpty)
        }
        if (uselessTimes) m1
        else
          m1 withTimeSpent timeSpent withTimeTotal timeTotal
      }
      move map { m: ParsedMove =>
        lastDest = m.getDest.some
      }
      bMoveNumber += 1
      res = move :: res
    }
    res.reverse.sequence map ParsedMoves.apply
  }

  def createVariations(vs: List[StrVariation]): List[StrVariation] = {
    def getChildren(parent: StrVariation, rest: List[StrVariation]): StrVariation = {
      // variationStart is increasing as long as it relates to the current variation
      val ch = rest
        .takeWhile(_.variationStart > parent.variationStart)
        .zipWithIndex
        .foldLeft(List[(StrVariation, Int)]()) { case (acc, cur) =>
          if (acc.headOption.fold(false)(_._1.variationStart < cur._1.variationStart)) acc
          else cur :: acc
        }
        .reverse
      val res = ch.map { case (k, i) =>
        getChildren(k, rest.drop(i + 1))
      }
      StrVariation(parent.variationStart, parent.moves, res)
    }
    // Obtain first depth variations - that's what we want to use in objMoves
    val ch = vs.zipWithIndex
      .foldLeft(List[(StrVariation, Int)]()) { case (acc, cur) =>
        if (
          acc.headOption.fold(false)(v =>
            (v._1.variationStart < cur._1.variationStart) && ((v._1.variationStart + v._1.moves.size) > cur._1.variationStart)
          )
        ) acc
        else cur :: acc
      }
      .reverse

    ch.map { case (k, i) =>
      getChildren(k, vs.drop(i + 1))
    }
  }

  def createTags(
      tags: Tags,
      sit: Situation,
      nbMoves: Int,
      moveTermTag: Option[Tag]
  ): Tags = {
    val fenTag = Forsyth.exportSituation(sit).some.collect {
      case sfen if !Forsyth.compareTruncated(sfen, Forsyth.initial) => Tag(_.FEN, sfen)
    }
    val termTag = (tags(_.Termination) orElse moveTermTag.map(_.value)).map(t => Tag(_.Termination, t))
    val resultTag = KifParserHelper
      .createResult(
        termTag,
        Color((nbMoves + { if (sit.color == Gote) 1 else 0 }) % 2 == 0)
      )

    List(fenTag, resultTag, termTag).flatten.foldLeft(tags)(_ + _)
  }

  object VariationParser extends RegexParsers with Logging {

    override val whiteSpace = """(\s|\t|\r?\n)+""".r

    def apply(kifVariations: String): Validated[String, List[StrVariation]] = {
      parseAll(variations, kifVariations.trim) match {
        case Success(vars, _) => valid(vars)
        case _                => invalid("Cannot parse variations")
      }
    }

    def variations: Parser[List[StrVariation]] = rep(variation)

    def variation: Parser[StrVariation] =
      as("variation") {
        header ~ rep(move) ^^ { case h ~ m =>
          StrVariation(h, m, Nil)
        }
      }

    def header: Parser[Int] =
      """.+""".r ^^ { case num =>
        (raw"""${numbersS}""".r findFirstIn num).map(KifUtils.kanjiToInt _).getOrElse(0)
      }

    // todo - don't repeat this just use MovesParser
    def move: Parser[StrMove] =
      as("move") {
        (commentary *) ~>
          (opt(number) ~ moveOrDropRegex ~ opt(clock) ~ rep(commentary)) <~
          (moveExtras *) ^^ { case num ~ moveStr ~ _ ~ comments =>
            StrMove(num, moveStr, cleanComments(comments))
          }
      }

    def number: Parser[Int] = raw"""${numbersS}[\s\.。]{1,}""".r ^^ { case n =>
      KifUtils.kanjiToInt(n.filterNot(c => (c == '.' || c == '。')).trim)
    }

    def clock: Parser[String] =
      as("clock") {
        """[\(（][0-9０-９\/\s:／]{1,}[\)）]\+?""".r
      }

    def moveExtras: Parser[Unit] =
      as("moveExtras") {
        commentary.^^^(())
      }

    def commentary: Parser[String] =
      as("commentary") {
        commentRegex ~> """.+""".r
      }

  }

  trait Logging { self: Parsers =>
    protected val loggingEnabled = false
    protected def as[T](msg: String)(p: => Parser[T]): Parser[T] =
      if (loggingEnabled) log(p)(msg) else p
  }

  object MovesParser extends RegexParsers with Logging {

    override val whiteSpace = """(\s|\t|\r?\n)+""".r

    def apply(kifMoves: String): Validated[String, (List[StrMove], Option[Tag])] = {
      parseAll(strMoves, kifMoves) match {
        case Success((moves, termination), _) =>
          valid(
            (
              moves,
              termination map { r =>
                Tag(_.Termination, r)
              }
            )
          )
        case err => invalid("Cannot parse moves: %s\n%s".format(err.toString, kifMoves))
      }
    }

    def strMoves: Parser[(List[StrMove], Option[String])] =
      as("moves") {
        (strMove *) ~ (termination *) ~ (commentary *) ^^ { case parsedMoves ~ term ~ coms =>
          (updateLastComments(parsedMoves, coms), term.headOption)
        }
      }

    def strMove: Parser[StrMove] =
      as("move") {
        (commentary *) ~>
          (opt(number) ~ moveOrDropRegex ~ opt(clock) ~ rep(commentary)) <~
          (moveExtras *) ^^ { case num ~ moveStr ~ clk ~ comments =>
            StrMove(num, moveStr, cleanComments(comments), clk.flatMap(_._1), clk.flatMap(_._2))
          }
      }

    def number: Parser[Int] = raw"""${numbersS}[\s\.。]{1,}""".r ^^ { case n =>
      KifUtils.kanjiToInt(n.filterNot(c => (c == '.' || c == '。')).trim)
    }

    private val clockMinuteSecondRegex     = """(\d++):(\d+(?:\.\d+)?)""".r
    private val clockHourMinuteSecondRegex = """(\d++):(\d++)[:\.](\d+(?:\.\d+)?)""".r

    private def readCentis(hours: String, minutes: String, seconds: String): Option[Centis] =
      for {
        h <- hours.toIntOption
        m <- minutes.toIntOption
        cs <- seconds.toDoubleOption match {
          case Some(s) => Some(BigDecimal(s * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt)
          case _       => none
        }
      } yield Centis(h * 360000 + m * 6000 + cs)

    private def parseClock(str: String): Option[Centis] = {
      str match {
        case clockMinuteSecondRegex(minutes, seconds)            => readCentis("0", minutes, seconds)
        case clockHourMinuteSecondRegex(hours, minutes, seconds) => readCentis(hours, minutes, seconds)
        case _                                                   => None
      }
    }

    private def updateLastComments(moves: List[StrMove], comments: List[String]): List[StrMove] = {
      val index = moves.size - 1
      (moves lift index).fold(moves) { move =>
        moves.updated(index, move.copy(comments = move.comments ::: comments))
      }
    }

    def clock: Parser[(Option[Centis], Option[Centis])] =
      as("clock") {
        """[\(（]\s*""".r ~>
          clockMinuteSecondRegex ~ opt("/") ~ opt(clockHourMinuteSecondRegex) <~
          """\s*[\)）]\+?""".r ^^ { case spent ~ _ ~ total =>
            (parseClock(spent), total.flatMap(parseClock(_)))
          }
      }

    def moveExtras: Parser[Unit] =
      as("moveExtras") {
        commentary.^^^(())
      }

    def commentary: Parser[String] =
      as("commentary") {
        commentRegex ~> """.+""".r
      }

    def termination: Parser[String] =
      as("termination") {
        opt(number) ~ termValue ~ opt(clock) ^^ { case _ ~ term ~ _ =>
          term
        }
      }

    val termValue: Parser[String] = "中断" | "投了" | "持将棋" | "千日手" | "詰み" | "切れ負け" | "反則勝ち" | "入玉勝ち" | "Time-up"
  }

  object MoveParser extends RegexParsers with Logging {

    val MoveRegex =
      raw"""(?:${colorsS})?(${positionS})\s?(${piecesJPS}|${piecesENGS})(${promotionS})?(?:(?:${parsS})?(${positionS})(?:${parsS})?)?""".r
    val DropRegex = raw"""(?:${colorsS})?(${positionS})\s?(${handPiecesJPS}|${handPiecesENGS})${dropS}""".r

    override def skipWhitespace = false

    def apply(str: String, lastDest: Option[Pos], variant: Variant): Validated[String, ParsedMove] = {
      str.map(KifUtils toDigit _) match {
        case MoveRegex(destS, roleS, promS, origS) =>
          for {
            role <- variant.rolesByEverything get roleS toValid s"Unknown role in move: $str"
            destOpt = if (destS == "同") lastDest else (Pos.numberAllKeys get destS)
            dest <- destOpt toValid s"Cannot parse destination square in move: $str"
            orig <- Pos.numberAllKeys get origS toValid s"Cannot parse origin square in move: $str"
          } yield KifStd(
            dest = dest,
            role = role,
            orig = orig,
            promotion = if (promS == "成" || promS == "+") true else false,
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
        case DropRegex(posS, roleS) =>
          for {
            role <- variant.rolesByEverything get roleS toValid s"Unknown role in drop: $str"
            pos  <- Pos.numberAllKeys get posS toValid s"Cannot parse destination square in drop: $str"
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
        case _ => invalid("Cannot parse move/drop: %s\n".format(str))
      }
    }
  }

  object TagParser extends RegexParsers with Logging {

    def apply(kif: String): Validated[String, Tags] =
      parseAll(all, kif) match {
        case f: Failure       => invalid("Cannot parse KIF tags: %s\n%s".format(f.toString, kif))
        case Success(tags, _) => valid(Tags(tags.filter(_.value.nonEmpty)))
        case err              => invalid("Cannot parse KIF tags: %s\n%s".format(err.toString, kif))
      }

    def all: Parser[List[Tag]] =
      as("all") {
        rep(tag) <~ """(.|\n)*""".r
      }

    def tag: Parser[Tag] =
      """.+(:).*""".r ^^ { case line =>
        val s = line.split(":", 2).map(_.trim).toList
        Tag(normalizeKifName(s.head), s.lift(1).getOrElse(""))
      }
  }
  def normalizeKifName(str: String): String =
    Tag.kifNameToTag.get(str).fold(str)(_.lowercase)

  private def cleanKif(kif: String): String =
    kif
      .replace("‑", "-")
      .replace("–", "-")
      .replace('　', ' ')
      .replace("：", ":")

  private def cleanComments(comments: List[String]) =
    comments.map(_.trim.take(2000)).filter(_.nonEmpty)

  private def getComments(kif: String): Validated[String, InitialPosition] =
    augmentString(kif).linesIterator.to(List).map(_.trim).filter(_.nonEmpty) filter { line =>
      line.startsWith("*") || line.startsWith("＊")
    } match {
      case (comms) => valid(InitialPosition(comms.map(_.drop(1).trim)))
    }

  private def splitHeaderAndRest(kif: String): Validated[String, (String, String)] =
    augmentString(kif).linesIterator.to(List).map(_.trim).filter(_.nonEmpty) span { line =>
      !(moveOrDropLineRegex.matches(line))
    } match {
      case (headerLines, moveLines) => valid(headerLines.mkString("\n") -> moveLines.mkString("\n"))
    }

  private def splitMovesAndVariations(kif: String): Validated[String, (String, String)] =
    augmentString(kif).linesIterator.to(List).map(_.trim).filter(_.nonEmpty) span { line =>
      !line.startsWith("変化")
    } match {
      case (moveLines, variantsLines) => valid(moveLines.mkString("\n") -> variantsLines.mkString("\n"))
    }

  private def splitMetaAndBoard(kif: String): Validated[String, (String, String)] =
    augmentString(kif).linesIterator
      .to(List)
      .map(_.trim)
      .filter(l => l.nonEmpty && !(l.startsWith("*") || l.startsWith("＊"))) partition { line =>
      (line contains ":") && !(line.tail startsWith "手の持駒")
    } match {
      case (metaLines, boardLines) => valid(metaLines.mkString("\n") -> boardLines.mkString("\n"))
    }
}
