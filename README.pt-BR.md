<h1 align="center">🌊 marola</h1>

<p align="center"><b>Um guia amigável para o mar perto de você, feito em código aberto, como ciência cidadã.</b></p>

<p align="center"><a href="./README.md">🇬🇧 Read in English</a> · <a href="https://marola.dev/">🗺️ Abrir o mapa ao vivo</a></p>

## O que é o marola? (a versão curta)

Imagine um amigo que conhece muito bem o mar. Você pergunta *"dá pra nadar amanhã? que horas?"*, e
ele olha as ondas, o vento, a temperatura da água, se a água está própria para banho, a maré, e
até se tem água-viva ou baleia por perto. Depois ele te diz qual é o melhor horário, e **por quê**.

Esse amigo é o marola. Você já pode usar hoje em **[marola.dev](https://marola.dev/)**: um mapa
das praias de Florianópolis, do Rio de Janeiro e de Salvador, cada uma com uma nota para hoje e
para amanhã.

<p align="center"><a href="https://marola.dev/"><img src="./docs/img/marola-web-view.png" alt="marola.dev: melhor horário por praia, em ordem, com a balneabilidade de um ponto de coleta" width="720" /></a></p>

Algumas promessas que o marola cumpre:

- **Ele nunca inventa.** Todo número vem de dados reais e públicos. Tudo o que pode te colocar em
  risco (água imprópria, mar agitado) é decidido por regras simples que qualquer pessoa pode ler,
  e não pelo palpite de uma IA. A IA só ajuda a explicar em palavras.
- **É gratuito e roda no seu próprio computador.** Não precisa de conta nem de serviço pago.
- **Ele avisa quando não sabe.** "Sem dados" aparece como "sem dados", nunca é escondido.

## Por que é código aberto: ciência cidadã 🔬

O marola é um projeto de **ciência cidadã**. O mar é de todos, e o conhecimento sobre ele também
deveria ser.

Os órgãos públicos já medem muita coisa: balneabilidade, ondas, vento, tempo. Só que esses dados
estão espalhados em sites e boletins diferentes, são técnicos e difíceis de usar na hora de ir à
praia. O marola junta essas informações, confere, explica em linguagem simples e devolve tudo ao
público.

E o caminho também é de volta: quem nada, surfa, pesca e mergulha todo dia vê coisas que nenhum
satélite vê. Essas observações podem conferir e melhorar as previsões.

Por isso tudo aqui é aberto: o código, as fontes de dados, as regras e cada decisão de projeto.
Qualquer pessoa pode ler, conferir e ajudar a melhorar.

## Onde começa, e aonde quer chegar

**Primeiro caso de uso: o agente de natação.** *"Qual o melhor horário amanhã para nadar aqui
perto?"* Isso já funciona: as praias vêm do OpenStreetMap, a previsão do mar e do tempo vem do
Open-Meteo, e a balneabilidade oficial vem dos órgãos ambientais estaduais (IMA/SC, INEA e
INEMA). Tudo vira uma nota de 0 a 100, com os motivos explicados.

**A ambição maior: a camada de inteligência do oceano para o litoral.** Nadar é só a primeira
pergunta. Os mesmos dados, e muitos outros, podem ajudar a proteger pessoas e lugares:

- 🌊 **Alertas de risco costeiro.** Começando pela *ressaca*, que erode as praias do Brasil, com
  um "não entre" firme quando o mar fica perigoso
  ([MIP-0062](./docs/MIPs/MIP-0062-ressaca-hazard.md)).
- 🛰️ **Previsão de desastres com os mesmos modelos das grandes agências.** Os dados de ondas do
  marola já incluem o **WAVEWATCH III**, o modelo de ondas da NOAA (a agência americana de
  oceanos e atmosfera), através do GFS-Wave do NCEP. O plano é mostrar vários modelos lado a lado,
  dizer abertamente quando eles discordam e, um dia, rodar um modelo de ondas detalhado para as
  nossas próprias baías ([MIP-0051](./docs/MIPs/MIP-0051-wave-model-ensemble.md),
  [MIP-0038](./docs/MIPs/MIP-0038-forecast-model-spread.md),
  [MIP-0052](./docs/MIPs/MIP-0052-wave-model-compute.md)).
- 🧪 **Um arquivo aberto da balneabilidade das praias brasileiras**, versionado para que qualquer
  pessoa possa estudar ([MIP-0056](./docs/MIPs/MIP-0056-oods-open-ocean-data-store.md)).
- 🪼 **Relatos de quem está na praia.** Água-viva, baleia e, mais adiante, fotos de como o mar
  está agora, para que observações reais confiram as previsões.
- 🏄 **O resto do mar.** Surfe, mergulho e pesca, como novas perguntas sobre os mesmos dados.

Essas ideias estão em estágios diferentes. Cada uma é escrita antes como um documento público de
projeto (uma "MIP"), para que você veja exatamente o que já está pronto e o que ainda é plano:
[`docs/MIPs/`](./docs/MIPs/README.md) (em inglês).

## Como você pode ajudar (sem programar)

- **Use o mapa** em [marola.dev](https://marola.dev/) e conte quando ele errar. Isso é um dado
  valioso.
- **Conte o que você vê na água**, como água-viva ou baleia, ou uma praia que está faltando.
- **Compartilhe conhecimento local**: segurança no mar, vida marinha, condições da sua praia. Tudo
  o que o marola explica vem de uma nota com fonte em [marola-corpus](https://github.com/marola-dev/marola-corpus).
- **Abra uma issue**, em português ou em inglês:
  [github.com/marola-dev/marola/issues](https://github.com/marola-dev/marola/issues).

---

## Para quem programa

O marola é escrito em [Scala](https://www.scala-lang.org/), uma linguagem de programação criada na
[EPFL](https://www.epfl.ch/) (a Escola Politécnica Federal de Lausanne, na Suíça) pelo laboratório
de Martin Odersky, e mantida hoje pelo [Scala Center](https://scala.epfl.ch/) da EPFL junto com a
VirtusLab e a Akka (antiga Lightbend). O marola usa Scala 3 com [Kyo](https://getkyo.io/) na JVM,
e um modelo local gratuito via [Ollama](https://ollama.com/). A nota e o veto de segurança são Scala determinístico; o modelo
apenas interpreta e redige, e nunca derruba um veto. Para rodar em cinco minutos:

```bash
nix develop                                                        # JDK 25, sbt, just, ollama (veja flake.nix)
just run -- --brief --lat -27.6733 --lon -48.4700                  # caminho mais rápido: lista em ordem, sem LLM
just ollama-up                                                     # inicia o `ollama serve` e baixa o llama3.2
just run -- --summarize --lat -27.6733 --lon -48.4700              # lista + resumo do LLM + revisão
just ask "o que fazer se eu for pego por uma corrente de retorno?" # resposta com fontes
```

Sem conta na nuvem, sem chave de API. O resto da documentação técnica está em inglês:
[`README.md`](./README.md#for-developers), [`docs/1-Using-marola/RUN-LOCALLY.md`](./docs/1-Using-marola/RUN-LOCALLY.md),
[`PHILOSOPHY.md`](./PHILOSOPHY.md) e [`CONTRIBUTING.md`](./CONTRIBUTING.md). Leia também o
[Código de Conduta](./CODE_OF_CONDUCT.md).

## Agradecimentos

O marola existe graças ao trabalho de muita gente: os colaboradores do
[OpenStreetMap](https://www.openstreetmap.org/copyright) (cada praia do mapa é deles, sob a ODbL),
o [Open-Meteo](https://open-meteo.com/) (as previsões do mar e do tempo), o
[Leaflet](https://leafletjs.com/) (o mapa), o [Ollama](https://ollama.com/) e o
[Kyo](https://getkyo.io/). Os dados de balneabilidade vêm dos boletins do INEA (Rio de Janeiro), do
INEMA (Bahia) e do IMA/SC (Santa Catarina).

## Contato

Para qualquer pedido sobre o marola, escreva para [admin@marola.dev](mailto:admin@marola.dev).
Vulnerabilidades vão pelo canal privado descrito no [`SECURITY.md`](./SECURITY.md).

## Licença

[MIT](./LICENSE), © 2026 colaboradores do marola.
