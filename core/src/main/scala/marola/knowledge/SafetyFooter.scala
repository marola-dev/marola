package marola.knowledge

/**
 * The emergency footer marola appends to any answer grounded in a `knowledge/safety/` document
 * (MIP-0022).
 */
object SafetyFooter:

  enum Lang derives CanEqual:
    case PtBr, En

  def render(lang: Lang): String = lang match
    case Lang.PtBr =>
      "⚠️ Em emergência na água: acione os guarda-vidas ou ligue 193 (Bombeiros) / 192 (SAMU).\n" +
        "Esta resposta não substitui socorro profissional."
    case Lang.En =>
      "⚠️ In a water emergency in Brazil: alert the lifeguards or call 193 (fire/rescue) / " +
        "192 (SAMU ambulance).\nThis answer is not a substitute for professional help."

  /** Appends the footer after `text` iff `safety`; returns `text` unchanged otherwise. */
  def append(text: String, safety: Boolean, lang: Lang = Lang.PtBr): String =
    if !safety then text else text + "\n\n" + render(lang)
