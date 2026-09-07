package marola.knowledge

class SafetyFooterSpec extends munit.FunSuite:

  test("append leaves non-safety text unchanged") {
    assertEquals(
      SafetyFooter.append("swim parallel to the shore", safety = false),
      "swim parallel to the shore"
    )
  }

  test("append adds the pt-BR footer with 193 and 192 by default when safety = true") {
    val out = SafetyFooter.append("swim parallel to the shore", safety = true)
    assert(out.startsWith("swim parallel to the shore\n\n"))
    assert(out.contains("193"))
    assert(out.contains("192"))
  }

  test("append honours the requested language") {
    val en = SafetyFooter.append("text", safety = true, SafetyFooter.Lang.En)
    assert(en.contains("In a water emergency"))
    val ptBr = SafetyFooter.append("text", safety = true, SafetyFooter.Lang.PtBr)
    assert(ptBr.contains("emergência na água"))
  }

end SafetyFooterSpec
