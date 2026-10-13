# A voz do marola: de onde vem cada regra

As regras de voz do `SKILL.md` não foram inventadas. Cada uma sai de um arquivo deste
repositório. Esta página mostra a origem e dá um exemplo. Os exemplos usam só fatos que estão
nos arquivos citados.

## O que o marola é, segundo os próprios arquivos

| Arquivo | O que ele diz |
|---|---|
| `README.pt-BR.md` | "Um guia amigável para o mar perto de você, feito em código aberto, como ciência cidadã." O marola "nunca inventa", "avisa quando não sabe" ("sem dados" aparece como "sem dados") e decide o que é risco "por regras simples que qualquer pessoa pode ler, e não pelo palpite de uma IA". O texto trata o leitor por "você", escreve "o marola" e avisa quando um link leva a um texto "(em inglês)". |
| `AGENTS.md`, "What this umbrella is" | O assunto: praias reais (OpenStreetMap), mar e tempo ao vivo (Open-Meteo), balneabilidade oficial, uma nota de 0 a 100 com um veto determinístico de segurança e um resumo de LLM revisado por uma segunda passada. |
| `docs/3-Ways-of-working/PHILOSOPHY.md` | As razões: o modelo escreve bem e testemunha mal; a plataforma existe para que o erro tenha onde parar; "no unsourced text reaches a user"; os documentos não podem soar mais seguros do que o código. |
| `docs/MIPs/MIP-0029-ocean-layer-positioning.md`, §11 | O nome: "Marola" é uma onda pequena, e a palavra já carrega a modéstia pretendida. O alcance é grande, e o que se diz é pequeno e concreto: uma praia, uma hora, um número com a sua fonte. |
| `docs/1-Using-marola/LIMITATIONS.md` | O jeito de falar de limite: "a heuristic, not a validated model". O limite vem junto da coisa, não numa nota escondida. |
| `papers/politica-previsor-marola-ptbr.md` | O registro do dono em texto técnico: frases curtas e declarativas, termo em português com o inglês em itálico entre parênteses ("Horizonte (*lead*)", "braço, *arm*"), vírgula decimal ("2,5 m"), títulos em caixa baixa, `(v)` e `⚠` para o que foi e o que não foi conferido. |
| `docs/MIPs/MIP-0054-portuguese-first-experience.md` | `PRÓPRIA` e `IMPRÓPRIA` são o veredito do órgão ambiental e ficam como o órgão escreve. |
| marola-site, `site/static/about.html` (artigo em pt-BR) e `DESIGN.md`, "Copy" | O registro do site: "a gente", "dá pra", comparações do dia a dia, tudo em minúsculas, sem travessão. Vale para o site. Aqui só o "sem travessão" e o tom de conversa em textos de divulgação são reaproveitados. |

## As regras

### 1. Não soar mais seguro do que a fonte

Origem: *What this is not*. "The docs are not allowed to sound more certain than the code."

> **Antes:** O Tier 2 do fine-tune garante um modelo melhor para o mar.
>
> **Depois:** O Tier 2 do fine-tune está escrito, mas ainda não foi rodado, por falta de GPU.

### 2. Dizer em que estágio a coisa está

Origem: *Why it was built, and the three pillars*. "The stages are named below rather than
smoothed over."

> **Antes:** O marola detecta ressacas e avisa a população.
>
> **Depois:** O alerta de ressaca está proposto na MIP-0062. Ainda não foi construído.

### 3. Dizer a parte difícil sem almofada

Origem: *The one decision*. "That is the unpopular part, stated plainly."

> **Antes:** Vale mencionar que, em alguns cenários, pode haver certas limitações na heurística
> de águas-vivas.
>
> **Depois:** A previsão de água-viva é uma heurística, não um modelo validado. Um dia calmo e
> ótimo para nadar costuma sair marcado como risco elevado.

### 4. As regras decidem a segurança; o modelo só escreve

Origem: *Why LLMs, and where they are not allowed*. A nota, o veto da água, a regra do escuro e
o desconto de mar agitado são código comum, testado, fora do prompt.

> **Antes:** A IA do marola analisa as condições e decide se é seguro entrar no mar.
>
> **Depois:** Regras fixas, que qualquer pessoa pode ler, decidem o que é risco. A IA só explica
> o resultado em palavras.

### 5. Nada sem fonte

Origem: *Why LLMs, and where they are not allowed*. "No unsourced text reaches a user."

> **Antes:** Segundo especialistas, a água estava boa para banho.
>
> **Depois:** O último boletim do IMA/SC classificou o ponto como PRÓPRIA.

Se o original não diz qual é a fonte, não invente uma. Escreva `[FALTA DADO: fonte]` e avise.

### 6. Pequeno e concreto

Origem: *Why marola* ("small enough to finish and hard enough to be honest about") e MIP-0029
§11.

> **Antes:** O marola oferece uma visão abrangente das condições oceânicas da região.
>
> **Depois:** O marola dá uma nota de 0 a 100 para cada praia, para hoje e para amanhã, e diz o
> melhor horário.

### 7. Uma frase que alguém vai ler

Origem: *Why marola* ("a sentence a person will actually read") e o `README.pt-BR.md`.

> **Antes:** São disponibilizadas, por meio da plataforma, informações referentes à
> balneabilidade das praias.
>
> **Depois:** O mapa mostra se a água de cada praia está própria para banho.

### 8. A razão vem junto da regra

Origem: o primeiro parágrafo da página. `AGENTS.md` guarda as regras, e `PHILOSOPHY.md`, as
razões.

> **Antes:** A exploração roda só em modo sombra.
>
> **Depois (quando o original dá a razão):** A exploração roda só em modo sombra, porque o
> banhista nunca deve ver uma previsão que ainda está em teste.

Só escreva a razão que o original traz.

### 9. Sem veredito a mais sobre as alternativas

Origem: *What this is not*. "Not anti-Python, anti-cloud or anti-anything."

> **Antes:** Os modelos de séries temporais são inúteis para o mar.
>
> **Depois:** A MIP-0007 conclui que os modelos de séries temporais são a ferramenta errada para
> onda e vento, e a certa para as séries que o próprio marola acumula.

### 10. O nome

Origem: `README.pt-BR.md`, `papers/politica-previsor-marola-ptbr.md` e o artigo em pt-BR do
`about.html` do marola-site.

- "o marola", em minúsculas, com artigo masculino: "o marola junta essas informações".
- No início da frase, prefira reordenar a escrever "Marola": "Esse amigo é o marola."
- Onde a fonte usa maiúscula como parte de um título, mantenha: "o previsor Marola".
- Nome de repositório não muda: `marola-app`, `marola-site`.

## Dois registros, um cuidado

| Texto | Registro | Exemplo de onde ele já existe |
|---|---|---|
| README, divulgação, convite a voluntários | conversa: "você", "a gente" e "dá pra" se o texto já usa | `README.pt-BR.md` |
| Paper, nota de política, versão fácil de uma MIP | português padrão, simples, sem gíria | `papers/politica-previsor-marola-ptbr.md` |

Não leve o registro de um para o outro. Uma versão fácil é simples, mas não é uma conversa de
praia.
