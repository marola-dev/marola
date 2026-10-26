# O marola hoje

O que já está no ar no [marola.dev](https://marola.dev/), o que vem depois e o que está em andamento em cada repositório. Saiu do [README](../../README.md) para ele caber em dez minutos de leitura.

## O mapa: o que já está no ar e o que vem aí

> ⚠️ **Frontend v2 em andamento.** A versão v2 do frontend do mapa está sendo escrita com
> [MapLibre](https://maplibre.org/) e Rust, e deve ficar pronta antes de 2027. A página que está
> hoje no marola.dev é a v1, com Mapbox GL JS. A semente da v2 fica em 🔒 `h0ffmann/smart-alerts`,
> um repositório fechado por enquanto, sob a licença MPL-2.0.
>
> <a href="https://github.com/h0ffmann/smart-alerts"><img src="https://img.shields.io/badge/h0ffmann%2Fsmart--alerts-private-555?logo=github&logoColor=white" alt="h0ffmann/smart-alerts: private repository" /></a>
> <a href="https://www.mozilla.org/MPL/2.0/"><img src="https://img.shields.io/badge/license-MPL--2.0-1682D4?logo=mozilla&logoColor=white" alt="license: MPL-2.0" /></a>

O [marola.dev](https://marola.dev/) tem uma barra de camadas no canto superior direito. Duas
camadas já estão no ar; as outras aparecem na barra, apagadas, como "em breve", ou estão escritas
como MIP, e cada uma só é ligada depois que os dados dela são conferidos no ar.

| Camada | Situação | O que mostra | Onde está o projeto |
|---|---|---|---|
| 🏖️ Praias | **No ar** | Todas as praias do OpenStreetMap em Florianópolis, no Rio de Janeiro e em Salvador, com nota para hoje e amanhã e o melhor horário | [MIP-0005](../MIPs/MIP-0005-map-and-static-site.md) |
| 💧 Balneabilidade | **No ar** | Os pontos de coleta oficiais do IMA/SC, do INEA (RJ) e do INEMA (BA), com a cor do último boletim | [MIP-0016](../MIPs/MIP-0016-water-quality-map-markers.md), [MIP-0031](../MIPs/MIP-0031-water-quality-inea-inema.md) |
| 🥾 Trilhas perto da costa | Planejada | Trilhas do OSM perto do mar, inclusive as longas, como a Transcarioca | [MIP-0030](../MIPs/MIP-0030-coastal-trails.md) |
| 🌬️ Vento e 🌊 ondas | Pronta, desligada | Partículas animadas, no estilo do Windy (`flow.js`), hoje interpoladas das leituras de cada praia; o próximo passo é um campo de verdade, além das praias | [marola-site#45](https://github.com/marola-dev/marola-site/issues/45) |
| 🛰️ Satélite | Pronta, desligada | Imagem de satélite em cor real da NASA GIBS, pública e sem chave; liga quando os tiles forem conferidos no ar | [marola-site#45](https://github.com/marola-dev/marola-site/issues/45) |
| 🌡️ Calor do mar | Pronta, desligada | Temperatura da superfície do mar, as anomalias (ondas de calor marinhas) e uma visão do El Niño, da NASA GIBS | [marola-site#45](https://github.com/marola-dev/marola-site/issues/45) |
| ⛰️ Profundidade | Planejada | Batimetria | [marola-site#45](https://github.com/marola-dev/marola-site/issues/45) |
| ⚠️ Riscos e desastres | Planejada | Alertas oficiais por estado (INMET, Marinha) e a *ressaca* | [MIP-0062](../MIPs/MIP-0062-ressaca-hazard.md), [MIP-0034](../MIPs/MIP-0034-rss-feeds-and-content-syndication.md), [marola#542](https://github.com/marola-dev/marola/pull/542), [marola-site#10](https://github.com/marola-dev/marola-site/issues/10) |
| 📷 Câmeras ao vivo | Mais adiante | "Como está o mar agora?", a partir de câmeras que as pessoas compartilham | [MIP-0006](../MIPs/MIP-0006-live-look-user-cameras.md) |

## Os repositórios, e o que está acontecendo em cada um

Situação em outubro de 2026; as issues e os PRs linkados mostram o estado de agora.

| Repositório | O que é | Em andamento |
|---|---|---|
| [marola](https://github.com/marola-dev/marola) (este) | O guarda-chuva: MIPs, lista de fases, jeito de trabalhar, [docs.marola.dev](https://docs.marola.dev/) | PRs de projeto para o contrato do lago (MIP-0075, [#686](https://github.com/marola-dev/marola/pull/686)), DOIs no Zenodo ([#683](https://github.com/marola-dev/marola/pull/683)), artistas locais ([#681](https://github.com/marola-dev/marola/pull/681)), alertas oficiais ([#542](https://github.com/marola-dev/marola/pull/542), [#669](https://github.com/marola-dev/marola/pull/669)) · [PRs](https://github.com/marola-dev/marola/pulls) |
| [marola-app](https://github.com/marola-dev/marola-app) | O produto, em Scala 3 com [Kyo](https://getkyo.io/) na JVM: pipeline, nota e veto de segurança, linha de comando, servidor MCP, a imagem que monta os dados do mapa | Boletins do INEMA para Salvador ([#62](https://github.com/marola-dev/marola-app/pull/62)), o módulo `oods` do lago ([#54](https://github.com/marola-dev/marola-app/pull/54)), revisão do Kyo 1.0.0-RC7 ([#56](https://github.com/marola-dev/marola-app/pull/56)), respostas do Overpass que expiram e viram lista de praias ([#60](https://github.com/marola-dev/marola-app/issues/60)) · [issues](https://github.com/marola-dev/marola-app/issues) |
| [marola-site](https://github.com/marola-dev/marola-site) | O mapa em [marola.dev](https://marola.dev/): Mapbox GL, sem servidor, refeito a cada 3 horas a partir da imagem do app; os órgãos do Rio e da Bahia são acessados por um proxy no Brasil | Florianópolis mostrando só 13 praias ([#73](https://github.com/marola-dev/marola-site/issues/73)), artistas locais no rodapé ([#69](https://github.com/marola-dev/marola-site/pull/69)), spec da página de alertas ([#58](https://github.com/marola-dev/marola-site/pull/58)), página de notícias ([#54](https://github.com/marola-dev/marola-site/pull/54)), SEO ([#31](https://github.com/marola-dev/marola-site/issues/31)) · [issues](https://github.com/marola-dev/marola-site/issues) |
| [marola-oods](https://github.com/marola-dev/marola-oods) | O Open Ocean Data Store: um lago de dados aberto com as praias e as amostras de balneabilidade, um DuckLake no Cloudflare R2 (ainda sem dados) | Esquema do lago como migrations ([#22](https://github.com/marola-dev/marola-oods/pull/22)), spec 001 ([#3](https://github.com/marola-dev/marola-oods/pull/3)), tarefas da MIP-0075 [#9](https://github.com/marola-dev/marola-oods/issues/9)–[#19](https://github.com/marola-dev/marola-oods/issues/19) |
| [marola-ml](https://github.com/marola-dev/marola-ml) | Python offline: compilação de prompts com DSPy, portão de benchmark, [marola-sea](https://huggingface.co/h0ffmann/marola-sea-tiny-GGUF) | Nenhum PR aberto; runners de GPU ([#2](https://github.com/marola-dev/marola-ml/issues/2)), uma regressão na busca do RAG ([#4](https://github.com/marola-dev/marola-ml/issues/4)), dá para prever a balneabilidade? ([#23](https://github.com/marola-dev/marola-ml/issues/23)) |
| [marola-corpus](https://github.com/marola-dev/marola-corpus) | O conhecimento sobre o mar, com fontes, de onde o marola tira as respostas | Estável; documentos novos são bem-vindos · [issues](https://github.com/marola-dev/marola-corpus/issues) |
| [marola-devkit](https://github.com/marola-dev/marola-devkit) | As ferramentas de desenvolvimento que todo repositório fixa (entra como input do flake Nix, não como submódulo): scripts, hooks, skills do Claude Code, CI reutilizável, como a revisão do Gemini | Tabelas da ligação entre repositórios (MIP-0076, [#32](https://github.com/marola-dev/marola-devkit/pull/32), [#33](https://github.com/marola-dev/marola-devkit/pull/33)) · [issues](https://github.com/marola-dev/marola-devkit/issues) |
| [marola-skills](https://github.com/marola-dev/marola-skills) | O catálogo de todas as skills e subagentes do Claude Code nos repositórios: onde cada um mora, de onde veio, o que foi testado. Lê tudo pela API do GitHub uma vez por dia | Entra no guarda-chuva como submódulo e no [site de docs](https://docs.marola.dev/5-Repos/marola-skills/) ([#761](https://github.com/marola-dev/marola/issues/761)) · [issues](https://github.com/marola-dev/marola-skills/issues) |
| [marola-agents](https://github.com/marola-dev/marola-agents) | O inventário de todos os agentes que o marola usa ou planeja: subagentes do Claude Code, os Claude Projects e suas rotinas, os papéis de LLM do app, os agentes que o CI chama | O primeiro README e o inventário · [issues](https://github.com/marola-dev/marola-agents/issues) |
| [marola-research](https://github.com/marola-dev/marola-research) | Science | [issues](https://github.com/marola-dev/marola-research/issues) |

Outros três repositórios são listas curadas, fora do produto: o
[awesome-ocean-sciences](https://github.com/marola-dev/awesome-ocean-sciences) é a lista do próprio
marola com software, dados e ferramentas sobre o oceano; o
[open-sustainable-technology](https://github.com/marola-dev/open-sustainable-technology) e o
[awesome-open-climate-science](https://github.com/marola-dev/awesome-open-climate-science) são forks
de listas da comunidade onde o marola ainda vai ser proposto.

## Todos os repositórios da org

A org [marola-dev](https://github.com/marola-dev) tem 14 repositórios públicos, forks incluídos.
A coluna "Commits" conta os commits do branch padrão de cada um (`git rev-list --count HEAD`,
contados em 2026-10-26). Num fork, essa conta inclui o histórico do projeto original. Um
repositório privado fica fora da lista.

| Repositório | Tipo | Commits | Último commit |
|---|---|---:|---|
| [marola](https://github.com/marola-dev/marola) | guarda-chuva | 594 | 2026-10-26 |
| [marola-devkit](https://github.com/marola-dev/marola-devkit) | código | 223 | 2026-10-20 |
| [marola-app](https://github.com/marola-dev/marola-app) | código | 197 | 2026-10-20 |
| [marola-site](https://github.com/marola-dev/marola-site) | código | 140 | 2026-10-26 |
| [marola-ml](https://github.com/marola-dev/marola-ml) | código | 116 | 2026-10-20 |
| [marola-skills](https://github.com/marola-dev/marola-skills) | catálogo | 48 | 2026-10-20 |
| [marola-corpus](https://github.com/marola-dev/marola-corpus) | dados | 45 | 2026-10-20 |
| [marola-research](https://github.com/marola-dev/marola-research) | ciência | 42 | 2026-10-20 |
| [marola-oods](https://github.com/marola-dev/marola-oods) | dados | 34 | 2026-10-20 |
| [.github](https://github.com/marola-dev/.github) | perfil da org | 15 | 2026-10-25 |
| [marola-agents](https://github.com/marola-dev/marola-agents) | catálogo | 4 | 2026-10-10 |
| [awesome-ocean-sciences](https://github.com/marola-dev/awesome-ocean-sciences) | lista | 1 | 2026-10-19 |
| [open-sustainable-technology](https://github.com/marola-dev/open-sustainable-technology) | fork de lista | 4089 | 2026-09-25 |
| [awesome-open-climate-science](https://github.com/marola-dev/awesome-open-climate-science) | fork de lista | 77 | 2025-11-12 |

## Em andamento: o lago de dados aberto do oceano

**[MIP-0075](../MIPs/MIP-0075-water-quality-store-r2.md), em andamento.** Hoje cada build do
mapa busca praias e balneabilidade do zero, e o histórico se perde. A MIP-0075 guarda tudo num lago
de dados aberto: um [DuckLake](https://ducklake.select/) (Parquet mais um catálogo DuckDB) num
bucket do Cloudflare R2, gravado por jobs de ETL agendados no módulo `oods` do marola-app, com o
esquema sob responsabilidade do marola-oods. Primeiro vêm as praias, depois a balneabilidade de
Santa Catarina, depois Rio e Bahia. Onde está: a revisão do contrato do lago
([marola#686](https://github.com/marola-dev/marola/pull/686)), as migrations do esquema
([marola-oods#22](https://github.com/marola-dev/marola-oods/pull/22)), a spec
([marola-oods#3](https://github.com/marola-dev/marola-oods/pull/3)) e as issues de tarefa
[marola-oods#9](https://github.com/marola-dev/marola-oods/issues/9)–[#19](https://github.com/marola-dev/marola-oods/issues/19).
