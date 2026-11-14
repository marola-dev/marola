import scala.collection.immutable.{SortedMap, SortedSet}

class MipGraphTest extends munit.FunSuite:
  import MipGraph.*

  private def mips(ms: Mip*): Mips = SortedMap.from(ms.map(m => m.num -> m))

  test("parses"):
    val mip = parseMip(
      "# MIP-0099: A sample title\n\n| | |\n|---|---|\n| **Status** | Draft |\n| **Blocked by** | MIP-0010, MIP-0025 |\n"
    ).get
    assertEquals((mip.num, mip.title), (99, "A sample title"))
    assertEquals(mip.blockedBy, Seq(10, 25))
    val none = parseMip("# MIP-0098: Another\n\n| | |\n|---|---|\n| **Blocked by** | none |\n")
    assertEquals(none.get.blockedBy, Seq.empty)
    val noRow = parseMip("# MIP-0097: No row\n\n| | |\n|---|---|\n| **Status** | Draft |\n")
    assertEquals(noRow.get.blockedBy, Seq.empty)
    val status = parseMip("# MIP-0096: S\n| **Status** | Implemented, verified (x) |\n")
    assertEquals(status.get.status, "Implemented")

  test("renders"):
    val two = mips(Mip(10, "Ledger", "Implemented", Nil), Mip(32, "Benchmark", "Draft", Seq(10)))
    val block = graphBlock(two)
    assert(block.startsWith(StartMark) && block.endsWith(EndMark))
    assert(block.contains("](../img/archify/mip-graph.png)"))
    val figureText = renderArchify(two).get
    val fig = ujson.read(figureText)
    assertEquals(fig("connections")(0)("from").str, "M0010")
    assertEquals(fig("connections")(0)("to").str, "M0032")
    assertEquals(fig("components")(0)("sublabel").str, "Implemented")
    assertEquals(fig("components")(1)("sublabel").str, "Draft")
    assertEquals(
      Mip(74, "T", "Proposed / Accepted / Superseded by ADR-NNNN", Nil).statusWord,
      "Draft"
    )
    assertEquals(fig("components")(1)("pos"), ujson.Arr(40, 150))
    assert(!figureText.contains("Ledger"))
    assertEquals(reachable(two, 32, true), SortedSet(10))

    val zero = mips(Mip(1, "A", "Draft", Nil), Mip(2, "B", "Draft", Nil))
    assert(renderArchify(zero).isEmpty && graphBlock(zero).contains("nothing to graph yet"))

    val orphans = two + (1 -> Mip(1, "Orphan", "Draft", Nil))
    assert(graphBlock(orphans).contains("not graphed: MIP-0001."))
    assert(!renderArchify(orphans).get.contains("M0001"))

  test("generate and check"):
    val dir = os.temp.dir()
    val readme = dir / "README.md"
    os.write(readme, "before\n<!-- mip-graph:start -->\nold\n<!-- mip-graph:end -->\nafter\n")
    os.write(dir / "MIP-0010-a.md", "# MIP-0010: A\n")
    os.write(dir / "MIP-0032-b.md", "# MIP-0032: B\n| **Blocked by** | MIP-0010 |\n")
    val figure = dir / "mip-graph.json"
    assertEquals(generate(dir, readme, figure, check = true), 1)
    assertEquals(generate(dir, readme, figure, check = false), 0)
    assertEquals(generate(dir, readme, figure, check = true), 0)
    assert(os.read(figure).contains("\"M0032\""))
    val text = os.read(readme)
    assert(
      text.startsWith("before\n") && text.endsWith("after\n") && text.contains("mip-graph.png")
    )

  test("the committed graph is current"):
    val dir = Util.repoRoot / "docs" / "MIPs"
    val figure = Util.repoRoot / "docs" / "archify" / "docs" / "mip-graph.json"
    assertEquals(generate(dir, dir / "README.md", figure, check = true), 0)
