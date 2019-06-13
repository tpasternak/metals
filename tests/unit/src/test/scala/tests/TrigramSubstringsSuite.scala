package tests

import scala.meta.internal.metals.TrigramSubstrings

object TrigramSubstringsSuite extends BaseSuite {
  def check(original: String, expected: String): Unit = {
    test(original) {
      val obtained = TrigramSubstrings(original).mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }

  def checkUppercase(original: String, expected: String): Unit = {
    test(original) {
      val uppercased = TrigramSubstrings.uppercased(original).toList
      val obtained = uppercased.mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }
  check(
    "abcd",
    """
      |abc
      |abd
      |acd
      |bcd
      |""".stripMargin
  )

  checkUppercase(
    "fsmbu",
    """|FSmbu
       |FsMbu
       |FsmBu
       |FsmbU
       |FSMbu
       |FSmBu
       |FSmbU
       |FsMBu
       |FsMbU
       |FsmBU
       |""".stripMargin
  )

}
