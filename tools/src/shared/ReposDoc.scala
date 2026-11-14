package marola.tools.shared

/** REPOS.md: the routing table's only copy, with the wiring block the devkit's `wiring` writes
  * between its markers (MIP-0076 §5.4).
  */
object ReposDoc:
  val Path = "docs/2-Building-marola/REPOS.md"
  val WiringStart = "<!-- wiring:start -->"
  val WiringEnd = "<!-- wiring:end -->"
