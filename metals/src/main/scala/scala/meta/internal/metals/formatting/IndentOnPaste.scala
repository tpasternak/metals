package scala.meta.internal.metals.formatting
import scala.util.matching.Regex

import scala.meta.internal.mtags.MtagsEnrichments._

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

object IndentOnPaste extends RangeFormatter {

  private val noEndClause = raw"((<!\bend\b\s*?)\b(if|while|for|match|try))"
  private val ifThenClause = raw"(\bif\s+(?!.*?\bthen\b.*?$$)[^\s]*?)"
  private val keywordClause = raw"\b(then|else|do|catch|finally|yield|case)"
  private val openBraceClause = raw"""(^.*\{[^}"']*$$)"""
  private val extensionClause = raw"""extension\s*((\(|\[).*(\)|\]))+"""
  val increaseIndentPatternRegex: Regex =
    raw"""($noEndClause|$ifThenClause|$keywordClause|$extensionClause|=|=>|<-|=>>|:)\s*?$$|$openBraceClause""".r
  val indentRegex: Regex = raw"\S".r

  private def codeStartPosition(line: String): Option[Int] =
    indentRegex.findFirstMatchIn(line).map(_.start)

  private def increaseIndentation(line: String) =
    increaseIndentPatternRegex.findFirstIn(line).nonEmpty

  private def stringRepeat(s: String, n: Int): String =
    ("%0" + n + "d").format(0).replace("0", s)

  // converts spaces into tabs and vice-versa, normalizing the lengths of indentations
  private def normalizeSpacesAndTabs(
      line: String,
      codeStartPos: Int,
      blank: String,
      tabSize: Int
  ): String = {
    if (codeStartPos != 0) {
      val substrLength = math.min(line.length, codeStartPos)
      val indentation = line.substring(0, substrLength)
      val indentChars = indentation.split("")
      val pastedBlank = indentChars.head
      blank match {
        case "\t" if pastedBlank == blank => line
        case " " if pastedBlank == blank => line
        case "\t" if pastedBlank == " " =>
          val tabNum = math.ceil(pastedBlank.length / 2).toInt
          stringRepeat(blank, tabNum) ++ line.slice(codeStartPos, line.length)
        case " " if pastedBlank == "\t" =>
          stringRepeat(blank, tabSize) ++ line.slice(
            codeStartPos,
            line.length
          )
        case _ => line
      }
    } else line
  }

  override def contribute(
      rangeFormatterParams: RangeFormatterParams
  ): Option[List[TextEdit]] = {
    val formattingOptions = rangeFormatterParams.formattingOptions
    val startPos = rangeFormatterParams.startPos
    val endPos = rangeFormatterParams.endPos
    val splitLines = rangeFormatterParams.splitLines

    val insertSpaces = formattingOptions.isInsertSpaces
    val originalTabSize = formattingOptions.getTabSize
    val rangeStart = startPos.toLSP.getStart
    rangeStart.setCharacter(0)
    // we format full lines even if not everything was pasted
    val realEndColumn =
      if (endPos.endLine < splitLines.size) splitLines(endPos.endLine).size
      else endPos.endColumn
    val pastedRange =
      new Range(rangeStart, new Position(endPos.endLine, realEndColumn))
    val startLine = startPos.toLSP.getStart.getLine
    val endLine = endPos.toLSP.getEnd.getLine

    val pastedLines = splitLines.slice(startLine, endLine + 1)
    val pastedLinesWithIndex = pastedLines.zipWithIndex
    val (blank, tabSize) =
      if (insertSpaces) (" ", originalTabSize) else ("\t", 1)

    // These are the lines from the first pasted line, going above
    val prePastedLines = splitLines.take(startLine).reverse

    val currentIndentationLevel = (for {
      line <- prePastedLines.find(t => {
        val trimmed = t.trim()
        trimmed.nonEmpty && !trimmed.startsWith("|")
      }) // Find first line non empty (aka code) that is not a piped multi-string

      indentation <- codeStartPosition(line) // get indentation spaces
      nextIncrease = increaseIndentation(
        line
      ) // check if the next line needs to increase indentation
    } yield {
      if (nextIncrease)
        indentation + tabSize
      else indentation
    }).getOrElse(0)

    val codeLinesIdxs = (for {
      (text, idx) <- pastedLinesWithIndex if text.trim().nonEmpty
    } yield idx).toList

    /**
     * Computing correct line indentation from second pasted line going on
     * assuming that from the second line they have correct relative indentation to themselves.
     * The first line instead can be pasted in different spots,
     * so its indentation gets computed separately
     */
    val newLinesOpt = for {
      secondLineIdx <- codeLinesIdxs.drop(1).headOption
      preNormalizeCodeStartPosition <- codeStartPosition(
        pastedLines(secondLineIdx)
      )
      convertedLines = pastedLines.map(
        normalizeSpacesAndTabs(_, preNormalizeCodeStartPosition, blank, tabSize)
      )
      pastedIndentation <- codeStartPosition(convertedLines(secondLineIdx))
      headIdx <- codeLinesIdxs.headOption
      headLine = convertedLines(headIdx)
      indentTailLines = increaseIndentation(headLine)
      block = if (indentTailLines) 1 else 0
      blockIndent = block * tabSize
    } yield for {
      line <- convertedLines.drop(headIdx + 1)
      pastedLineIndentation <- codeStartPosition(line)
    } yield {
      val diff = currentIndentationLevel + blockIndent - pastedIndentation

      if (diff < 0) {
        if (pastedLineIndentation < -diff) {
          stringRepeat(blank, currentIndentationLevel + blockIndent) ++ line
            .slice(pastedLineIndentation, line.length)
        } else line.slice(-diff, line.length)
      } else if (diff > 0) stringRepeat(blank, diff) ++ line
      else line
    }

    lazy val indentedHead: Option[String] = for {
      headIdx <- codeLinesIdxs.headOption
      head = blank * currentIndentationLevel ++ pastedLines(headIdx).trim()
    } yield head

    val newLines = for {
      newLines <- newLinesOpt
      head <- indentedHead
    } yield (head +: newLines).toList

    /**
     * The previous code, starting from the second line of code going on
     * doesn't compute single lines of code, so this little snippet is to handle
     * the case when the user pastes only one line of code.
     */
    lazy val singleCodeLinePasted = (for {
      headIdx <- codeLinesIdxs.headOption
      line = pastedLines(headIdx)
      codeStartChar <- codeStartPosition(line)
    } yield {
      val firstPastedChar = startPos.toLSP.getStart.getCharacter
      firstPastedChar <= codeStartChar || codeLinesIdxs.length == 1 && pastedLines.length > 1
    }).getOrElse(false)

    lazy val singleLineOption =
      if (singleCodeLinePasted) indentedHead.map(List(_)) else None

    newLines
      .orElse(singleLineOption)
      .map(lines =>
        new TextEdit(
          pastedRange,
          lines.mkString(System.lineSeparator)
        ) :: Nil
      )
  }
}