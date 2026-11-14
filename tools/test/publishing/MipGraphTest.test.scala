package marola.tools.publishing

import marola.tools.shared.Util

import scala.collection.immutable.{SortedMap, SortedSet}

class MipGraphTest extends munit.FunSuite:
  import MipGraph.*
  import MipReadme.*
  import MipDependencies.reachable

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
    assertEquals(status.get.status, MipStatus.Implemented)

  test("renders"):
    val two = mips(
      Mip(10, "Ledger", MipStatus.Implemented, Nil),
      Mip(32, "Benchmark", MipStatus.Draft, Seq(10))
    )
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
      Mip(74, "T", MipStatus.parse("Proposed / Accepted / Superseded by ADR-NNNN"), Nil).statusWord,
      "Draft"
    )
    assertEquals(fig("components")(1)("pos"), ujson.Arr(40, 150))
    assert(!figureText.contains("Ledger"))
    assertEquals(reachable(two, 32, true), SortedSet(10))

    val zero = mips(Mip(1, "A", MipStatus.Draft, Nil), Mip(2, "B", MipStatus.Draft, Nil))
    assert(renderArchify(zero).isEmpty && graphBlock(zero).contains("nothing to graph yet"))

    val orphans = two + (1 -> Mip(1, "Orphan", MipStatus.Draft, Nil))
    assert(graphBlock(orphans).contains("not graphed: MIP-0001."))
    assert(!renderArchify(orphans).get.contains("M0001"))

  test("generate and check"):
    val dir = os.temp.dir()
    val readme = dir / "README.md"
    os.write(readme, "before\n<!-- mip-graph:start -->\nold\n<!-- mip-graph:end -->\nafter\n")
    os.write(dir / "MIP-0010-a.md", "# MIP-0010: A\n")
    os.write(dir / "MIP-0032-b.md", "# MIP-0032: B\n| **Blocked by** | MIP-0010 |\n")
    val figure = dir / "mip-graph.json"
    assertEquals(generate(Config(dir, readme, figure), check = true), 1)
    assertEquals(generate(Config(dir, readme, figure), check = false), 0)
    assertEquals(generate(Config(dir, readme, figure), check = true), 0)
    assert(os.read(figure).contains("\"M0032\""))
    val text = os.read(readme)
    assert(
      text.startsWith("before\n") && text.endsWith("after\n") && text.contains("mip-graph.png")
    )

  test("the committed graph is current"):
    val dir = Util.repoRoot / "docs" / "MIPs"
    val figure = Util.repoRoot / "docs" / "archify" / "docs" / "mip-graph.json"
    assertEquals(generate(Config(dir, dir / "README.md", figure), check = true), 0)

  test("statuses read the first word of their row"):
    assertEquals(MipStatus.parse("Partially implemented"), MipStatus.PartiallyImplemented)
    assertEquals(MipStatus.parse("implemented"), MipStatus.Implemented)
    assertEquals(MipStatus.parse("Superseded by MIP-0099"), MipStatus.Superseded)
    assertEquals(MipStatus.parse("Withdrawn"), MipStatus.Unknown("Withdrawn"))
    assertEquals(MipStatus.Unknown("Withdrawn").word, "Draft")

  test("dependencies, layout and the parallel verdict"):
    val chain = mips(
      Mip(1, "A", MipStatus.Draft, Nil),
      Mip(2, "B", MipStatus.Draft, Seq(1)),
      Mip(3, "C", MipStatus.Draft, Seq(2, 99)),
      Mip(4, "D", MipStatus.Draft, Nil)
    )
    assertEquals(MipDependencies.edges(chain).toSeq, Seq(Edge(1, 2), Edge(2, 3)))
    assertEquals(MipDependencies.connected(chain), SortedSet(1, 2, 3))
    assertEquals(reachable(chain, 3, true), SortedSet(1, 2, 99), "an unknown blocker is named")
    assertEquals(reachable(chain, 1, false), SortedSet(2, 3))
    assertEquals(
      MipDependencies.layout(chain).map(n => (n.mip.num, n.col, n.row)),
      Seq((1, 0, 0), (2, 0, 1), (3, 0, 2))
    )
    assertEquals((PlacedNode(chain(1), 1, 2).x, PlacedNode(chain(1), 1, 2).y), (300, 260))
    val blocked = ParallelVerdict.of(chain, 1, 3, SortedSet.empty, SortedSet.empty)
    assert(blocked.conflict && blocked.exitCode == 1)
    val clash = ParallelVerdict.of(chain, 1, 4, SortedSet("cli/a", "core/b"), SortedSet("cli/a"))
    assertEquals(clash.lines(2), "  §5 source-path overlap: yes — cli/a")
    assertEquals(clash.exitCode, 1)
    val safe = ParallelVerdict.of(chain, 1, 4, SortedSet("cli/a"), SortedSet.empty)
    assertEquals(safe.lines.last, "  -> parallel-safe on both checks")
    assertEquals(safe.exitCode, 0)

  test("the README block is replaced, or appended"):
    assertEquals(withBlock(s"a\n$StartMark\nold\n$EndMark\nb", "NEW"), "a\nNEW\nb")
    assertEquals(withBlock("a", "NEW"), "a\n\nNEW\n")
    assertEquals(withBlock("a\n", "NEW"), "a\n\nNEW\n")
