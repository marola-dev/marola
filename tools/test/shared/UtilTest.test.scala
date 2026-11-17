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

  test("readUtf8 keeps the message a person needs, UTF-8 or OS"):
    val dir = os.temp.dir()
    os.write.over(dir / "good.txt", "olá mar".getBytes("UTF-8"))
    assertEquals(Util.readUtf8(dir / "good.txt"), Right("olá mar"))
    os.write.over(dir / "bad.txt", Array[Byte](0x6f, (0xff & 0xff).toByte, 0x6f))
    assertEquals(Util.readUtf8(dir / "bad.txt"), Left(Util.NotUtf8))
    assertEquals(Util.readUtf8(dir / "gone.txt"), Left("No such file or directory (os error 2)"))

  test("hasExtension: a leading dot starts a name"):
    assertEquals(Util.hasExtension(os.RelPath("README.md").resolveFrom(os.pwd), "md"), true)
    assertEquals(Util.hasExtension(os.RelPath("README.md").resolveFrom(os.pwd), "MD"), false)
    assertEquals(Util.hasExtension(os.RelPath(".md").resolveFrom(os.pwd), "md"), false)
    assertEquals(Util.hasExtension(os.RelPath("CITATION").resolveFrom(os.pwd), "cff"), false)

  test("trimUnicode trims what String.trim misses"):
    assertEquals(Util.trimUnicode("  value  "), "value")
    assertEquals(Util.trimUnicode("\u00a0mar\u2003"), "mar")

  test("an I/O error reads as the OS words it, errno included"):
    import java.io.IOException
    import java.nio.file.{NoSuchFileException, NotDirectoryException}
    assertEquals(Util.osError(NoSuchFileException("x")), "No such file or directory (os error 2)")
    assertEquals(Util.osError(NotDirectoryException("x")), "Not a directory (os error 20)")
    assertEquals(Util.osError(new IOException("broken pipe")), "broken pipe")

  test("formatNumber keeps whole numbers bare, and others as the JVM printed them"):
    assertEquals(Util.formatNumber(3.0), "3")
    assertEquals(Util.formatNumber(3.5), "3.5")
    assertEquals(Util.formatNumber(1e16), "1.0E16")

  test("pyrepr quotes a value the way Python's repr quoted it"):
    assertEquals(Util.pyrepr(Some(ujson.Str("it's"))), "\"it's\"")
    assertEquals(Util.pyrepr(Some(ujson.Str("plain"))), "'plain'")
    assertEquals(Util.pyrepr(None), "None")
    assertEquals(Util.pyrepr(Some(ujson.Num(3))), "3")
