package marola.tools.shared

class UtilTest extends munit.FunSuite:
  import Util.*

  test("dumps matches python"):
    val v = ujson.Obj("a" -> 1, "b" -> ujson.Arr(1, "é"), "c" -> ujson.Obj(), "d" -> ujson.Arr())
    assertEquals(dumps(v, None, false), """{"a": 1, "b": [1, "é"], "c": {}, "d": []}""")
    assertEquals(dumps(v, None, true), "{\"a\": 1, \"b\": [1, \"\\u00e9\"], \"c\": {}, \"d\": []}")
    assertEquals(
      dumps(ujson.Obj("a" -> ujson.Arr(1, 2)), Some(1), true),
      "{\n \"a\": [\n  1,\n  2\n ]\n}"
    )
    assertEquals(dumps(ujson.Str("\ud83c\udf0a"), None, true), "\"\\ud83c\\udf0a\"")

  test("posix helpers"):
    import posix.*
    assertEquals(normpath("docs/../x.md"), "x.md")
    assertEquals(normpath("../a/./b"), "../a/b")
    assertEquals(normpath("a/../.."), "..")
    assertEquals(normpath(""), ".")
    assertEquals(dirname("docs/x.md"), "docs")
    assertEquals(dirname("README.md"), "")
    assertEquals(join("", "x"), "x")
    assertEquals(extension("a/b.PNG"), ".PNG")
    assertEquals(extension("a/.hidden"), "")
    assertEquals(relpath("5-Repos/x/index.md", "."), "5-Repos/x/index.md")
    assertEquals(relpath("index.md", "guide"), "../index.md")
    assertEquals(relpath("sub/index.md", "sub"), "index.md")

  test("fnmatch"):
    assert(fnmatchcase("benchmarks", "benchmarks"))
    assert(fnmatchcase("0002-x.md", "0002-*.md"))
    assert(!fnmatchcase("0001-x.md", "0002-*.md"))
    assert(fnmatchcase("a/b", "a*"))
    assert(fnmatchcase("x1", "x[0-9]"))
    assert(!fnmatchcase("x1", "x[!0-9]"))

  test("lines and urls"):
    assertEquals(splitlinesKeepends("a\nb\r\nc"), Vector("a\n", "b\r\n", "c"))
    assertEquals(splitlines("a\nb\r\nc\n"), Vector("a", "b", "c"))
    assertEquals(unquote("a%20b%C3%A9%zz"), "a bé%zz")
    assertEquals(urlPath("https://docs.marola.dev/a%20b/?x#y"), "/a%20b/")
    assertEquals(urlPath("https://docs.marola.dev"), "")
    assertEquals(urlencode(Seq("q" -> "a b/é")), "q=a+b%2F%C3%A9")
