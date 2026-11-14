package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.{Invocation, Jq, Proc}

import mainargs.{arg, main, ParserForMethods}

// One Archify source to one PNG in picasso's look (just figure): Archify validates and renders,
// docs/archify/picasso.css goes over the page, and headless Chrome measures the drawing, then
// takes its picture cropped to it. ARCHIFY is the pinned checkout, CHROME the browser,
// CHROME_FLAGS extra flags. Paths are relative to the working directory.
//
//     marola-tools figure SRC.json OUT.png
//
// Trap: `just _figure-lock` hashes this file, not ContentModel.scala, so what changes the pixels
// (the fonts, the measuring script, the pad, the frame) stays here.
object Figure:
  val Fonts =
    """<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fjalla+One&family=Inter:wght@400;600&display=block">"""

  // The bounding box of what is visible, in viewBox units, written into the title.
  val Measure =
    """<script>addEventListener("load",async()=>{await document.fonts.ready;const s=document.querySelector(".diagram-container svg"),m=s.getScreenCTM().inverse();let a=[1e9,1e9,-1e9,-1e9];for(const e of s.querySelectorAll("rect,text,path,line,polyline,polygon,circle,ellipse,image")){if(e.closest("defs")||getComputedStyle(e).visibility!=="visible")continue;const r=e.getBoundingClientRect();if(r.width<1&&r.height<1||r.width>=innerWidth*.98&&r.height>=innerHeight*.98)continue;const p=new DOMPoint(r.left,r.top).matrixTransform(m),q=new DOMPoint(r.right,r.bottom).matrixTransform(m);a=[Math.min(a[0],p.x),Math.min(a[1],p.y),Math.max(a[2],q.x),Math.max(a[3],q.y)]}document.title="BBOX "+a.map(Math.round).join(" ")})</script>"""

  val Pad = 24

  /** picasso's look, put over Archify's page. */
  val Look = "docs/archify/picasso.css"
  private val NoUpdateCheck = "ARCHIFY_UPDATE_CHECK_DISABLED"

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

  /** `${w%.*}`: a size without its decimals. */
  def whole(size: String): String =
    val dot = size.lastIndexOf('.')
    if dot < 0 then size else size.take(dot)

  /** The crop: the box grown by Pad on every side. */
  def crop(box: BBox): CropBox = box.grow(Pad)

  /** The size in the page's first `<svg viewBox="0 0 W H">`. */
  def viewBoxSize(page: String): Option[DrawingSize] =
    """<svg viewBox="[^"]*"""".r.findFirstIn(page).flatMap { tag =>
      """.*0 0 ([0-9.]+) ([0-9.]+)"""".r
        .findFirstMatchIn(tag)
        .map(m => DrawingSize(m.group(1), m.group(2)))
    }

  /** The BBOX the measuring page wrote into its title: x0 y0 x1 y1, a missing one 0. */
  def bbox(dom: String): Option[BBox] =
    """BBOX [-0-9 ]*""".r.findFirstIn(dom).flatMap { found =>
      val numbers =
        found.drop("BBOX ".length).trim.split(" +").filter(_.nonEmpty).take(4).map(_.toIntOption)
      Option.when(numbers.forall(_.isDefined)) {
        val Seq(x0, y0, x1, y1) = numbers.flatten.toSeq.padTo(4, 0): @unchecked
        BBox(x0, y0, x1, y1)
      }
    }

  /** deliver.json's diagnostics and error, a value that is not a string written as JSON. */
  def deliverReport(json: String): Option[DeliverReport] =
    def raw(value: ujson.Value) = value.strOpt.getOrElse(ujson.write(value, indent = 2))
    scala.util.Try(ujson.read(json)).toOption.map { result =>
      val diagnostics = Jq.items(Jq.field(result, "diagnostics"))
      val messages =
        diagnostics.flatMap(_.objOpt).flatMap(_.get("message")).filter(Jq.truthy).map(raw)
      DeliverReport(messages, raw(Jq.field(result, "error")))
    }

  /** What deliver.json says went wrong: each diagnostic's message, or else its `error`. */
  def deliverErrors(json: String): Seq[String] = deliverReport(json).toSeq.flatMap(_.errors)

  /** The source's `diagram_type`, empty when the source is not JSON. */
  def archifySource(json: String): ArchifySource =
    ArchifySource(
      scala.util
        .Try(ujson.read(json))
        .toOption
        .map(source => source.objOpt.flatMap(_.get("diagram_type")).getOrElse(ujson.Null))
        .map(value => value.strOpt.getOrElse(ujson.write(value)))
        .getOrElse("")
    )

  // `realpath -m`: absolute, its directory's symlinks resolved, the file itself may not exist yet.
  private def realpath(path: os.Path): String =
    scala.util
      .Try((os.Path(path.toNIO.getParent.toRealPath()) / path.last).toString)
      .getOrElse(path.toString)

  def draw(src: String, png: String, env: Map[String, String], cwd: os.Path): Int =
    val tools = FigureTools.from(env)
    val outRel = FigureWork.of(png).dir
    val out = os.Path(outRel, cwd)
    os.remove.all(out)
    os.makeDir.all(out)
    os.makeDir.all(os.Path(png, cwd) / os.up)

    val proc = Proc.exact(env)
    def shoot(width: String, height: String, stdout: os.ProcessOutput, args: String*): Proc.Result =
      val command = Seq(
        "timeout",
        "120",
        tools.chrome,
        "--headless",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-dark-mode",
        "--force-device-scale-factor=2",
        s"--window-size=$width,$height",
        "--virtual-time-budget=5000"
      ) ++ tools.chromeFlags ++ args
      proc.call(cwd, command, stdout, os.Pipe)

    val source = archifySource(scala.util.Try(os.read(os.Path(src, cwd))).getOrElse(""))
    val deliver = Proc
      .exact(env + (NoUpdateCheck -> "1"))
      .call(
        cwd,
        Seq(
          "node",
          s"${tools.archify}/archify/bin/archify.mjs",
          "deliver",
          source.diagramType,
          src,
          s"$outRel/${FigureWork.Page}",
          "--quality",
          "standard",
          "--json"
        ),
        stdout = out / FigureWork.Deliver
      )
    if deliver.exitCode != 0 then
      val report = out / FigureWork.Deliver
      if os.exists(report) then deliverErrors(os.read(report)).foreach(System.err.println)
      1
    else
      val page = os.read(out / FigureWork.Page)
      val css = Proc.chomp(os.read(cwd / os.RelPath(Look)))
      viewBoxSize(page) match
        case None =>
          System.err.println(s"figure: no <svg viewBox=\"0 0 W H\"> in $outRel/${FigureWork.Page}")
          1
        case Some(DrawingSize(w, h)) =>
          os.write(
            out / FigureWork.Measure,
            frame(page, css, s"0 0 $w $h", sizeStyle(whole(w), whole(h)) + Measure)
          )
          val dom = shoot(
            whole(w),
            whole(h),
            os.Pipe,
            "--dump-dom",
            s"file://$cwd/$outRel/${FigureWork.Measure}"
          ).out
          bbox(dom) match
            case None      => 1
            case Some(box) =>
              val cropped = crop(box)
              val (width, height) = (cropped.width, cropped.height)
              os.write(
                out / FigureWork.Frame,
                frame(page, css, cropped.viewBox, sizeStyle(s"$width", s"$height"))
              )
              val shot = shoot(
                s"$width",
                s"$height",
                os.Inherit,
                s"--screenshot=${realpath(os.Path(png, cwd))}",
                s"file://$cwd/$outRel/${FigureWork.Frame}"
              )
              if shot.exitCode != 0 then shot.exitCode
              else
                println(s"figure: $png (${width}x$height)")
                0

  @main(doc = "Draw one Archify source as a PNG in picasso's look")
  def run(
      @arg(positional = true, doc = "the Archify source, docs/archify/<slug>.json") src: String,
      @arg(positional = true, doc = "the PNG to write") png: String
  ): Int =
    val invocation = Invocation.current()
    draw(src, png, invocation.env, invocation.cwd)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "figure", args)
