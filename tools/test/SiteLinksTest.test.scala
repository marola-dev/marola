class SiteLinksTest extends munit.FunSuite:
  import SiteLinks.*

  private def write(path: os.Path, text: String) = os.write(path, text, createFolders = true)

  test("reads the 404 moves"):
    val html =
      "<script>\nvar moves = [\n  ['/repos/marola-ml/api/', '/5-Repos/marola-ml/api-docs/python/'],\n  ['/repos/', '/5-Repos/'],\n  ['/MIPs/', '/6-MIPs/'],\n  ['/api/', '/5-Repos/marola-app/api-docs/']\n].sort(function (a, b) { return b[0].length - a[0].length; });\n</script>"
    val pairs = forwardPrefixes(html)
    assertEquals(pairs.map(_._1), Vector("/repos/marola-ml/api/", "/repos/", "/MIPs/", "/api/"))
    assertEquals(pairs.find(_._1 == "/MIPs/").map(_._2), Some("/6-MIPs/"))
    val real = os.read(Util.repoRoot / "mkdocs" / "overrides" / "404.html")
    assert(forwardPrefixes(real).nonEmpty, "the committed 404.html still parses")

  test("resolves"):
    val site = os.temp.dir()
    write(site / "a" / "index.html", "<html>a</html>")
    write(site / "index.html", "<html>root</html>")
    write(site / "sitemap.xml", "<urlset></urlset>")
    assertEquals(resolve("/a/", site), Some((site / "a" / "index.html").toNIO))
    assert(resolve("/a", site).isDefined)
    assertEquals(resolve("/", site), Some((site / "index.html").toNIO))
    assertEquals(resolve("/sitemap.xml", site), Some((site / "sitemap.xml").toNIO))
    assertEquals(resolve("/missing/", site), None)
    assertEquals(resolve("/../../etc/passwd", site), None)

  test("hrefs"):
    val site = os.temp.dir()
    write(site / "a" / "index.html", "<html></html>")
    write(site / "a b" / "index.html", "<html></html>")
    write(
      site / "index.html",
      """<html><a href="https://docs.marola.dev/a/">a</a>""" +
        """<a href="https://docs.marola.dev/gone/">gone</a>""" +
        """<a href="https://docs.marola.dev/a%20b/">encoded space</a>""" +
        """<a href="https://docs.marola.dev.example/evil/">not this site</a>""" +
        """<a href="https://example.com/other/">other</a></html>"""
    )
    val (total, broken) = checkHrefs(site)
    assertEquals(total, 3, "the lookalike host is not this site")
    assertEquals(broken.length, 1)
    assert(broken.head.contains("gone"))

  test("old sitemap"):
    val site = os.temp.dir()
    write(site / "unchanged" / "index.html", "<html>still here</html>")
    write(
      site / "moved-old" / "index.html",
      """<meta http-equiv="refresh" content="0; url=../moved-new/">"""
    )
    write(site / "5-Repos" / "marola-site" / "index.html", "<html>new home</html>")
    val xml = Seq(
      "<urlset>",
      "<url><loc>https://docs.marola.dev/unchanged/</loc></url>",
      "<url><loc>https://docs.marola.dev/moved-old/</loc></url>",
      "<url><loc>https://docs.marola.dev/repos/marola-site/</loc></url>",
      "<url><loc>https://docs.marola.dev/repos/marola-ml/</loc></url>",
      "<url><loc>https://docs.marola.dev/gone/</loc></url>",
      "</urlset>"
    ).mkString
    val (total, dropped) = checkOldSitemap(site, xml, Seq("/repos/" -> "/5-Repos/"))
    assertEquals(total, 5)
    assertEquals(
      dropped,
      Vector("https://docs.marola.dev/repos/marola-ml/", "https://docs.marola.dev/gone/")
    )

  test("an empty sitemap fails"):
    val tmp = os.temp.dir()
    write(tmp / "site" / "index.html", "<html></html>")
    write(tmp / "garbage.xml", "<html>404 Not Found</html>")
    assertEquals(run((tmp / "site").toString, Some((tmp / "garbage.xml").toString)), 1)
    assertEquals(run((tmp / "site").toString, None), 0)
