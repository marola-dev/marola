package marola.tools.docs

class FigureTest extends munit.FunSuite:
  import Figure.*

  private val page =
    """<html><head><title>x</title><script>
      |var a = 1;
      |</script></head><body><div class="diagram-container"><svg viewBox="0 0 640.5 480" width="1"><script src="x.js"></script></svg></div></body></html>
      |""".stripMargin

  test("frame drops the scripts, adds the look to the head and sets the viewBox"):
    assertEquals(
      frame(page, "a{b:$1}", "1 2 3 4", "<x>"),
      s"""<html><head><title>x</title>$Fonts<style>a{b:$$1}</style><x></head><body><div class="diagram-container"><svg viewBox="1 2 3 4" width="1"></svg></div></body></html>
         |""".stripMargin
    )

  test("the drawing's size, its whole part, and the crop around the measured box"):
    assertEquals(viewBoxSize(page), Some(DrawingSize("640.5", "480")))
    assertEquals(viewBoxSize("<svg width=\"1\">"), None)
    assertEquals(whole("640.5"), "640")
    assertEquals(whole("480"), "480")
    assertEquals(bbox("<title>BBOX 10 -20 300 200</title>"), Some(BBox(10, -20, 300, 200)))
    assertEquals(bbox("<title>nothing</title>"), None)
    assertEquals(crop(BBox(10, -20, 300, 200)), CropBox(-14, -44, 338, 268))
    assert(
      sizeStyle("1", "2").startsWith(
        "<style>.diagram-container svg{width:1px!important;height:2px!important;"
      )
    )

  test("deliver.json's errors: each diagnostic's message, else its error"):
    assertEquals(
      deliverErrors("""{"diagnostics":[{"message":"a"},{"message":null},{"message":"b"}]}"""),
      Seq("a", "b")
    )
    assertEquals(deliverErrors("""{"diagnostics":[],"error":"bad type"}"""), Seq("bad type"))
    assertEquals(deliverErrors("""{"ok":true}"""), Seq("null"))
    assertEquals(deliverErrors("not json"), Nil)
    assertEquals(
      deliverReport("""{"diagnostics":{"a":{"message":false}},"error":{"k":1}}"""),
      Some(DeliverReport(Nil, "{\n  \"k\": 1\n}"))
    )

  test("a report's errors are its messages, else its error"):
    assertEquals(DeliverReport(Seq("a"), "e").errors, Seq("a"))
    assertEquals(DeliverReport(Nil, "e").errors, Seq("e"))

  test("a box missing numbers is padded with 0; one that is not a number is none"):
    assertEquals(bbox("BBOX 1 2"), Some(BBox(1, 2, 0, 0)))
    assertEquals(bbox("BBOX 1 - 2"), None)

  test("a box grown by a pad, and its viewBox"):
    assertEquals(BBox(0, 0, 10, 20).grow(5), CropBox(-5, -5, 20, 30))
    assertEquals(CropBox(-5, -5, 20, 30).viewBox, "-5 -5 20 30")

  test("the source's diagram type, verbatim"):
    assertEquals(archifySource("""{"diagram_type":"flow"}"""), ArchifySource("flow"))
    assertEquals(archifySource("""{"diagram_type":3}"""), ArchifySource("3"))
    assertEquals(archifySource("""{}"""), ArchifySource("null"))
    assertEquals(archifySource(""), ArchifySource(""))

  test("the tools from the environment, and the work directory from the PNG"):
    assertEquals(
      FigureTools.from(Map("CHROME" -> "", "CHROME_FLAGS" -> " --a  --b ")),
      FigureTools(".tmp/archify", "google-chrome", Seq("--a", "--b"))
    )
    assertEquals(FigureTools.from(Map("ARCHIFY" -> "x", "CHROME" -> "c")).archify, "x")
    assertEquals(FigureWork.of("docs/img/x.png").dir, ".tmp/figure/x")
    assertEquals(FigureWork.of("a/.png"), FigureWork(".png"))
    assertEquals(FigureWork.of("a/b.svg/"), FigureWork("b.svg"))

  test("draw: Archify, a measuring shot, then the cropped picture"):
    val t = os.temp.dir(prefix = "figure")
    try
      os.write(t / "docs" / "archify" / "picasso.css", "svg{fill:red}\n\n", createFolders = true)
      os.write(t / "docs" / "archify" / "x.json", """{"diagram_type":"architecture"}""")
      os.write(
        t / "bin" / "node",
        s"""#!/bin/sh
           |echo "node $$*" >>"${t / "log"}"
           |cat >"$$5" <<'PAGE'
           |${page.trim}
           |PAGE
           |echo '{"ok":true}'
           |""".stripMargin,
        createFolders = true,
        perms = "rwxr-xr-x"
      )
      os.write(
        t / "bin" / "chrome",
        s"""#!/bin/sh
           |echo "chrome $$*" >>"${t / "log"}"
           |for a; do case "$$a" in --dump-dom) echo '<title>BBOX 10 20 300 200</title>' ;; --screenshot=*) echo png >"$${a#--screenshot=}" ;; esac; done
           |""".stripMargin,
        perms = "rwxr-xr-x"
      )
      val env = Map(
        "PATH" -> s"${t / "bin"}:${sys.env("PATH")}",
        "CHROME" -> (t / "bin" / "chrome").toString,
        "CHROME_FLAGS" -> "--no-sandbox  --x"
      )
      assertEquals(draw("docs/archify/x.json", "docs/img/x.png", env, t), 0)
      assert(os.isFile(t / "docs" / "img" / "x.png"))
      val out = t / ".tmp" / "figure" / "x"
      assertEquals(
        os.read(out / "measure.html"),
        frame(page.trim + "\n", "svg{fill:red}", "0 0 640.5 480", sizeStyle("640", "480") + Measure)
      )
      assertEquals(
        os.read(out / "frame.html"),
        frame(page.trim + "\n", "svg{fill:red}", "-14 -4 338 228", sizeStyle("338", "228"))
      )
      val common =
        "--headless --disable-gpu --hide-scrollbars --force-dark-mode --force-device-scale-factor=2"
      assertEquals(
        os.read.lines(t / "log").toSeq,
        Seq(
          "node .tmp/archify/archify/bin/archify.mjs deliver architecture docs/archify/x.json .tmp/figure/x/page.html --quality standard --json",
          s"chrome $common --window-size=640,480 --virtual-time-budget=5000 --no-sandbox --x --dump-dom file://$t/.tmp/figure/x/measure.html",
          s"chrome $common --window-size=338,228 --virtual-time-budget=5000 --no-sandbox --x --screenshot=${t.toNIO.toRealPath()}/docs/img/x.png file://$t/.tmp/figure/x/frame.html"
        )
      )
    finally os.remove.all(t)

  test("a failed deliver prints its diagnostics and exits 1"):
    val t = os.temp.dir(prefix = "figure")
    try
      os.write(t / "x.json", """{"diagram_type":"architecture"}""")
      os.write(
        t / "bin" / "node",
        "#!/bin/sh\necho '{\"diagnostics\":[{\"message\":\"no nodes\"}]}'\nexit 3\n",
        createFolders = true,
        perms = "rwxr-xr-x"
      )
      assertEquals(draw("x.json", "x.png", Map("PATH" -> s"${t / "bin"}:${sys.env("PATH")}"), t), 1)
    finally os.remove.all(t)
