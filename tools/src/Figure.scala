import mainargs.{arg, main, ParserForMethods}

// One Archify source to one PNG in picasso's look (just figure): Archify validates and renders,
// docs/archify/picasso.css goes over the page, and headless Chrome measures the drawing, then
// takes its picture cropped to it. ARCHIFY is the pinned checkout, CHROME the browser,
// CHROME_FLAGS extra flags. Paths are relative to the working directory.
//
//     marola-tools figure SRC.json OUT.png
object Figure:
  val Fonts =
    """<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fjalla+One&family=Inter:wght@400;600&display=block">"""

  // The bounding box of what is visible, in viewBox units, written into the title.
  val Measure =
    """<script>addEventListener("load",async()=>{await document.fonts.ready;const s=document.querySelector(".diagram-container svg"),m=s.getScreenCTM().inverse();let a=[1e9,1e9,-1e9,-1e9];for(const e of s.querySelectorAll("rect,text,path,line,polyline,polygon,circle,ellipse,image")){if(e.closest("defs")||getComputedStyle(e).visibility!=="visible")continue;const r=e.getBoundingClientRect();if(r.width<1&&r.height<1||r.width>=innerWidth*.98&&r.height>=innerHeight*.98)continue;const p=new DOMPoint(r.left,r.top).matrixTransform(m),q=new DOMPoint(r.right,r.bottom).matrixTransform(m);a=[Math.min(a[0],p.x),Math.min(a[1],p.y),Math.max(a[2],q.x),Math.max(a[3],q.y)]}document.title="BBOX "+a.map(Math.round).join(" ")})</script>"""

  val Pad = 24

  def sizeStyle(width: String, height: String): String =
    s"<style>.diagram-container svg{width:${width}px!important;height:${height}px!important;left:0!important;top:0!important;margin:0!important;transform:none!important}</style>"

  /** Archify's page without its scripts, with the fonts, picasso.css and `extra` in its head and
    * the drawing's viewBox set to `viewBox`.
    */
  def frame(page: String, css: String, viewBox: String, extra: String): String =
    val noScripts = """(?s)<script\b.*?</script>""".r.replaceAllIn(page, "")
    val headed = noScripts.replaceFirst(
      "</head>",
      java.util.regex.Matcher.quoteReplacement(s"$Fonts<style>$css</style>$extra</head>")
    )
    headed.replaceFirst(
      """<svg viewBox="0 0 [0-9.]+ [0-9.]+"""",
      java.util.regex.Matcher.quoteReplacement(s"""<svg viewBox="$viewBox"""")
    )

  /** The width and height of the page's first `<svg viewBox="0 0 W H">`. */
  def viewBoxSize(page: String): Option[(String, String)] =
    """<svg viewBox="[^"]*"""".r.findFirstIn(page).flatMap { tag =>
      """.*0 0 ([0-9.]+) ([0-9.]+)"""".r.findFirstMatchIn(tag).map(m => (m.group(1), m.group(2)))
    }

  /** `${w%.*}`: a size without its decimals. */
  def whole(size: String): String =
    val dot = size.lastIndexOf('.')
    if dot < 0 then size else size.take(dot)

  /** The BBOX the measuring page wrote into its title: x0 y0 x1 y1. */
  def bbox(dom: String): Option[Seq[Int]] =
    """BBOX [-0-9 ]*""".r.findFirstIn(dom).flatMap { found =>
      val numbers =
        found.drop("BBOX ".length).trim.split(" +").filter(_.nonEmpty).take(4).map(_.toIntOption)
      Option.when(numbers.forall(_.isDefined))(numbers.flatten.toSeq.padTo(4, 0))
    }

  /** The crop: the box grown by Pad on every side, as x0 y0 width height. */
  def crop(box: Seq[Int]): (Int, Int, Int, Int) =
    val Seq(x0, y0, x1, y1) = box: @unchecked
    val (left, top) = (x0 - Pad, y0 - Pad)
    (left, top, x1 - left + Pad, y1 - top + Pad)

  /** What deliver.json says went wrong: each diagnostic's message, or else its `error`. */
  def deliverErrors(json: String): Seq[String] =
    def raw(value: ujson.Value) = value.strOpt.getOrElse(ujson.write(value, indent = 2))
    scala.util.Try(ujson.read(json)).toOption.toSeq.flatMap { result =>
      val diagnostics = result.objOpt.flatMap(_.get("diagnostics")).toSeq.flatMap {
        case ujson.Arr(items) => items.toSeq
        case ujson.Obj(items) => items.values.toSeq
        case _                => Nil
      }
      val messages = diagnostics.flatMap(_.objOpt).flatMap(_.get("message")).filter(truthy).map(raw)
      if messages.nonEmpty then messages
      else Seq(raw(result.objOpt.flatMap(_.get("error")).getOrElse(ujson.Null)))
    }

  private def truthy(value: ujson.Value) = value != ujson.Null && value != ujson.False

  private def basenamePng(png: String): String =
    val name = png.split('/').filter(_.nonEmpty).lastOption.getOrElse(png)
    if name != ".png" && name.endsWith(".png") then name.dropRight(4) else name

  // `realpath -m`: absolute, its directory's symlinks resolved, the file itself may not exist yet.
  private def realpath(path: os.Path): String =
    scala.util
      .Try((os.Path(path.toNIO.getParent.toRealPath()) / path.last).toString)
      .getOrElse(path.toString)

  def draw(src: String, png: String, env: Map[String, String], cwd: os.Path): Int =
    val archify = env.get("ARCHIFY").filter(_.nonEmpty).getOrElse(".tmp/archify")
    val chrome = env.get("CHROME").filter(_.nonEmpty).getOrElse("google-chrome")
    val chromeFlags = env.get("CHROME_FLAGS").toSeq.flatMap(_.split("""\s+""")).filter(_.nonEmpty)
    val outRel = s".tmp/figure/${basenamePng(png)}"
    val out = os.Path(outRel, cwd)
    os.remove.all(out)
    os.makeDir.all(out)
    os.makeDir.all(os.Path(png, cwd) / os.up)

    val proc = Proc.exact(env)
    def shoot(width: String, height: String, stdout: os.ProcessOutput, args: String*): Proc.Result =
      val command = Seq(
        "timeout",
        "120",
        chrome,
        "--headless",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-dark-mode",
        "--force-device-scale-factor=2",
        s"--window-size=$width,$height",
        "--virtual-time-budget=5000"
      ) ++ chromeFlags ++ args
      proc.call(cwd, command, stdout, os.Pipe)

    val diagramType = scala.util
      .Try(ujson.read(os.read(os.Path(src, cwd))))
      .toOption
      .map(source => source.objOpt.flatMap(_.get("diagram_type")).getOrElse(ujson.Null))
      .map(value => value.strOpt.getOrElse(ujson.write(value)))
      .getOrElse("")
    val deliver = Proc
      .exact(env + ("ARCHIFY_UPDATE_CHECK_DISABLED" -> "1"))
      .call(
        cwd,
        Seq(
          "node",
          s"$archify/archify/bin/archify.mjs",
          "deliver",
          diagramType,
          src,
          s"$outRel/page.html",
          "--quality",
          "standard",
          "--json"
        ),
        stdout = out / "deliver.json"
      )
    if deliver.exitCode != 0 then
      val report = out / "deliver.json"
      if os.exists(report) then deliverErrors(os.read(report)).foreach(System.err.println)
      1
    else
      val page = os.read(out / "page.html")
      val css = Proc.chomp(os.read(cwd / "docs" / "archify" / "picasso.css"))
      viewBoxSize(page) match
        case None =>
          System.err.println(s"figure: no <svg viewBox=\"0 0 W H\"> in $outRel/page.html")
          1
        case Some((w, h)) =>
          os.write(
            out / "measure.html",
            frame(page, css, s"0 0 $w $h", sizeStyle(whole(w), whole(h)) + Measure)
          )
          val dom = shoot(
            whole(w),
            whole(h),
            os.Pipe,
            "--dump-dom",
            s"file://$cwd/$outRel/measure.html"
          ).out
          bbox(dom) match
            case None      => 1
            case Some(box) =>
              val (left, top, width, height) = crop(box)
              os.write(
                out / "frame.html",
                frame(page, css, s"$left $top $width $height", sizeStyle(s"$width", s"$height"))
              )
              val shot = shoot(
                s"$width",
                s"$height",
                os.Inherit,
                s"--screenshot=${realpath(os.Path(png, cwd))}",
                s"file://$cwd/$outRel/frame.html"
              )
              if shot.exitCode != 0 then shot.exitCode
              else
                println(s"figure: $png (${width}x$height)")
                0

  @main(doc = "Draw one Archify source as a PNG in picasso's look")
  def run(
      @arg(positional = true, doc = "the Archify source, docs/archify/<slug>.json") src: String,
      @arg(positional = true, doc = "the PNG to write") png: String
  ): Int = draw(src, png, sys.env, os.pwd)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "figure", args)
