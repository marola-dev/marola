package marola.lore

import marola.model.Coordinates
import java.time.LocalDate

class SeaLoreSpec extends munit.FunSuite:

  private val entries = SeaLore.loadDefault()
  private val campeche = Coordinates(-27.6733, -48.4700)
  private val southBrazil = SeaLore.regionTagsFor(campeche)

  test("the bundled sea_lore.json loads, and every entry has a non-empty source URL") {
    assert(entries.size >= 8, s"only ${entries.size} entries")
    entries.foreach(e => assert(e.source.startsWith("http"), s"${e.id} has no source URL"))
    assert(entries.exists(_.kind == LoreKind.Secret) && entries.exists(_.kind == LoreKind.Creature))
  }

  test("same day + beach → same entry; next day → (eventually) a different one; beaches differ") {
    val day = LocalDate.of(2026, 9, 5)
    val a1 = SeaLore.pick(entries, day, "Praia do Campeche", southBrazil)
    val a2 = SeaLore.pick(entries, day, "Praia do Campeche", southBrazil)
    assertEquals(a1, a2)
    val laterDays = (1 to 10).flatMap(d =>
      SeaLore.pick(entries, day.plusDays(d), "Praia do Campeche", southBrazil)
    )
    assert(laterDays.exists(_ != a1.get), "the pick never rotated over ten days")
    val other = (1 to 10).flatMap(i => SeaLore.pick(entries, day, s"Beach $i", southBrazil))
    assert(other.exists(_ != a1.get), "neighbouring beaches all got the same entry")
  }

  test("season filter: no right-whale entry in February, and it is eligible in August") {
    val feb =
      (1 to 60).flatMap(i => SeaLore.pick(entries, LocalDate.of(2026, 2, 1), s"B$i", southBrazil))
    assert(!feb.exists(_.id == "right-whales-santa-catarina"))
    val aug =
      (1 to 60).flatMap(i => SeaLore.pick(entries, LocalDate.of(2026, 8, 1), s"B$i", southBrazil))
    assert(aug.exists(_.id == "right-whales-santa-catarina"))
  }

  test("region filter: outside south Brazil only global entries are picked") {
    val rio = SeaLore.regionTagsFor(Coordinates(-22.9878, -43.1913))
    assert(rio.contains("BR-S")) // Rio is inside the (deliberately generous) south/south-east box
    val lisbon = SeaLore.regionTagsFor(Coordinates(38.7, -9.1))
    assertEquals(lisbon, Set("global"))
    val picks =
      (1 to 60).flatMap(i => SeaLore.pick(entries, LocalDate.of(2026, 8, 1), s"B$i", lisbon))
    assert(picks.forall(_.regions.contains("global")))
  }

  test("parse drops entries without a source or with an unknown kind") {
    val json = """[
      {"id":"a","kind":"secret","text":"t","source":"","regions":["global"],"months":null},
      {"id":"b","kind":"rumour","text":"t","source":"http://x","regions":["global"],"months":null},
      {"id":"c","kind":"creature","text":"t","source":"http://x","regions":["global"],"months":[1,2]}
    ]"""
    val parsed = SeaLore.parse(json)
    assertEquals(parsed.map(_.id), List("c"))
    assertEquals(parsed.head.months, Some(Set(1, 2)))
  }

end SeaLoreSpec
