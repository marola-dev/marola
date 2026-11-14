package marola.tools.docs

class StripScriptsTest extends munit.FunSuite:
  import StripScripts.*
  import SiteModel.*

  test("strips"):
    val page =
      """<html><head><script type="text/javascript" src="https://d3js.org/d3.v6.min.js"></script>""" +
        """<script src="https://scastie.scala-lang.org/embedded.js"></script>""" +
        """<script src="scripts/ux.js"></script></head>""" +
        """<body class="theme"><script>var pathToRoot = "../";</script>hi</body></html>"""
    val Stripped(out, removed) = ApiDocsPage.strip(page)
    assertEquals(removed, 2, "both third-party scripts are removed")
    assert(!out.contains("d3js.org"), "no https:// script src survives")
    assert(out.contains("""src="scripts/ux.js""""), "the doc's own scripts are kept")
    assert(!out.contains("var pathToRoot"), "the inline pathToRoot script is gone")
    assert(out.contains("""data-path-to-root="../""""), "pathToRoot is kept as data")
    assert(out.contains("""class="theme""""), "existing body attributes survive")
    assertEquals("path-to-root.js".r.findAllIn(out).length, 1, "the shim is linked once")
    assertEquals(ApiDocsPage.strip("<html><body>plain</body></html>").removed, 0)
    assertEquals(ApiDocsPage.strip("""<script src="http://x.example/a.js"></script>""").removed, 1)

  test("check tree"):
    val dir = os.temp.dir()
    os.write(dir / "clean.html", "<html><body>no scripts</body></html>")
    assertEquals(check(Seq(dir.toString), os.pwd), 0)
    os.write(
      dir / "doc.html",
      """<html><body><code>EXTERNAL_SCRIPT = &lt;script src="https://x/a.js"&gt;</code>""" +
        """<p>strips &lt;script src=&quot;https://...&quot;&gt; tags</p></body></html>"""
    )
    assertEquals(check(Seq(dir.toString), os.pwd), 0, "docs about script tags are not one")
    os.write(
      dir / "real.html",
      """<html><head><script src="https://d3js.org/d3.v6.min.js"></script></head></html>"""
    )
    assertEquals(check(Seq(dir.toString), os.pwd), 1, "a real external script is caught")
