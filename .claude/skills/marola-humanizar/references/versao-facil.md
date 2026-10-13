# Versão fácil: o contrato de um `*.easy.pt-BR.md`

Uma versão fácil é a companheira, em português simples, de **um** documento em inglês. Ela serve
a quem quer saber o que foi decidido e por quê, sem ser especialista: um banhista curioso, um
voluntário, um parceiro de dados, um jornalista, alguém de outra área da universidade.

Ela não é uma tradução. Ela também não é um resumo executivo: nenhuma decisão fica de fora.

## 1. As regras do arquivo

| Regra | Detalhe |
|---|---|
| Nome | o nome do original, trocando `.md` por `.easy.pt-BR.md`, na mesma pasta. Para uma MIP, `MIP-NNNN.easy.pt-BR.md`, como o `MIP-NNNN.tasks.md`: um arquivo `MIP-NNNN-*.md` é lido como a própria MIP pelo `just context-mip` e pela busca de MIP do devkit. Para `MIP-0091-marola-forecaster-architecture.md`: `MIP-0091.easy.pt-BR.md` |
| Fonte da verdade | o arquivo em inglês. A versão fácil diz isso no cabeçalho e aponta para ele |
| Quando o original muda | atualize a versão fácil na mesma mudança, ou marque no cabeçalho que ela está atrasada. Não deixe as duas discordarem em silêncio |
| Título | começa com "Versão fácil:". Não começa com `# MIP-NNNN:` |
| Cabeçalho | o bloco da seção 2, em citação (`>`). Sem tabela de chaves em negrito |
| Links | relativos, para o original e para as seções dele. Confira cada âncora com `just docs`: o site é quem reprova um link quebrado. Num título com `/` ou `&`, a âncora do site tem um hífen só (`#6-scoring-safety-impact`) e a do GitHub tem dois |

O título e o cabeçalho têm essa forma por causa de uma armadilha: `scripts/mip_graph.py` lê todo
`docs/MIPs/MIP-*.md` cujo título é `# MIP-NNNN: …` e tira o status de uma linha
`| **Status** | … |`. Uma versão fácil com esse título ou essa tabela seria lida como uma segunda
MIP com o mesmo número.

## 2. O cabeçalho obrigatório

Copie e preencha. Não deixe nenhum campo em branco: se não souber, pergunte.

```markdown
# Versão fácil: <o título, em português, em uma linha>

> **Esta é a versão fácil, em português, de
> [<MIP-NNNN: título original, em inglês>](<arquivo-original>.md).**
>
> - **Vale o original.** O documento em inglês é a fonte da verdade. Se este texto e o original
>   discordarem, vale o original.
> - **Situação do original:** <status, como está no original>, em <data>.
> - **Feita a partir de:** a versão de <data> do original (commit `<sha curto>`).
> - **Para quem é:** quem quer entender o que foi decidido, sem ser especialista.
> - **O que ficou de fora:** as contas, os detalhes de implementação e os nomes dos testes. Cada
>   trecho encurtado aponta para a seção do original.
> - **Escrita por:** <autor do original>, com <ferramenta>.
```

Regras do cabeçalho:

- O status fica na palavra do original (`Draft`, `Accepted`, `Implemented`, `Partially
  implemented (…)`), com a tradução uma vez entre parênteses: "Accepted (aceita)". Uma MIP
  `Draft` ou `Accepted` descreve algo que **ainda não foi construído**. Diga isso na primeira
  seção.
- O commit é o do arquivo original no momento em que você escreveu:
  `git log -1 --format=%h -- <arquivo-original>`.
- "Escrita por" repete a linha *Written by* do original.

## 3. O que fica, o que encurta, o que sai

### Fica sempre

- Toda **decisão**, inclusive as de não fazer ("não haverá repositório novo").
- Todo **número com a unidade**, com a mesma precisão. Um intervalo continua intervalo.
- Todo **nome**: modelo, instrumento, órgão, repositório, pessoa, lugar.
- Toda **referência**: `MIP-NNNN`, issue, PR, com o link.
- Todo **status** e toda **condição**: "só depois que X estiver pronto" continua condição.
- Toda **afirmação de segurança**, mesmo quando é "nada muda": o que decide se o mar está
  seguro, o que o documento não toca, o que o banhista vê ou não vê.
- Toda **limitação**, todo **risco** e toda **questão em aberto**. Podem ficar mais curtos.
  Não podem ficar mais leves.
- Todo **custo** e toda decisão que depende de dinheiro ou do aval de uma pessoa.
- As marcas de verificação do original: `(v)` e `⚠`, sem tradução.

### Encurta, com ponteiro

- Derivações e fórmulas: diga o que a conta decide, em uma frase, e aponte a seção.
- Detalhes de implementação: módulos, arquivos, formatos, assinaturas.
- Listas longas de requisitos: agrupe por assunto, diga quantos são e onde está a lista
  inteira. Não escolha "os principais" sem dizer que há outros.
- Alternativas rejeitadas: uma linha para cada, com o motivo.
- Tabelas grandes: uma frase com o que a tabela mostra, e o link.

O ponteiro tem esta forma: "Os vinte requisitos estão na
[§4.3 do original](<arquivo-original>.md#<âncora>)."

### Sai

- Nomes de testes e de arquivos de código.
- Notas de revisão, histórico de edição, bookkeeping de tarefas.
- Citações de versão e de commit que só interessam a quem vai implementar.

### Nunca

- Nunca acrescente um exemplo, uma comparação ou um número que o original não tem. Uma
  comparação do dia a dia para explicar um **termo** é permitida se não afirmar nada sobre o
  projeto, e vem marcada: "Uma comparação: …".
- Nunca troque "pode" por "vai", "proposto" por "faz", "não conferido" por silêncio.
- Nunca junte duas condições em uma, nem dois números em "cerca de".

### Quando simplificar estraga

Mantenha a forma mais difícil e diga por quê, no relatório, quando a frase simples:

- mudaria uma relação de causa ("A causa B" vira "A e B acontecem");
- apagaria uma exceção ("exceto quando…");
- separaria um número do que ele mede ou da data a que se refere;
- trocaria o nome oficial de uma coisa por uma descrição.

## 4. Tamanho e nível de leitura

| Medida | Alvo |
|---|---|
| Tamanho | de 15% a 25% das palavras do original, e no máximo cerca de 1.500 palavras. Para um original de 6.000 palavras: de 900 a 1.500 |
| Quando não cabe | o que "fica sempre" ganha do tamanho. Passe do alvo e diga isso no relatório |
| Leitor | terminou o ensino médio, não é da área, lê no celular |
| Frase | uma ideia por frase. De 13 a 18 palavras em média, 25 no máximo |
| Ordem | sujeito, verbo, complemento. Voz ativa quando se sabe quem faz |
| Parágrafo | até 4 frases |
| Conectivos | os simples: "porque", "por isso", "mas", "então", "também" |
| Repetição | repita o mesmo termo. Não troque por sinônimo para variar |
| Listas | para 3 itens paralelos ou mais. Não transforme um raciocínio em lista |
| Títulos | caixa baixa, curtos, de preferência uma pergunta que o leitor faria |

Os alvos de frase vêm do perfil "Português Simplificado" da skill `humanizar`. O tamanho é uma
escolha do marola e ainda não foi medido em uso: ajuste depois da primeira versão fácil.

Simples não é infantil. O raciocínio fica inteiro; só a forma fica mais curta.

## 5. Termos de arte

1. Na primeira vez, escreva o termo em português e, entre parênteses e em itálico, o termo em
   inglês: "horizonte (*lead*)". Depois use só o português.
2. Se a área só usa o termo em inglês, mantenha o inglês e explique uma vez: "um *ensemble*, um
   conjunto de previsões do mesmo modelo com pequenas diferenças".
3. Nome de modelo, de instrumento e de produto não se traduz: WAVEWATCH III, GFS-Wave,
   Open-Meteo, Operational WeatherBench. Diga o que é, uma vez.
4. Sigla: por extenso na primeira vez, com a sigla entre parênteses. Depois, a sigla.
5. Um termo, uma palavra. Escolha na primeira ocorrência e não mude.
6. Se o documento tem mais de cinco termos de arte, feche com a seção "Palavras usadas".

Estes termos já têm forma em português em `papers/politica-previsor-marola-ptbr.md`. Use-os
como estão lá, para que os textos do marola concordem entre si:

| Em português | No original | O que é |
|---|---|---|
| previsor, ou braço (*arm*) | forecaster, arm | qualquer fonte de previsão que pode ser escolhida |
| horizonte (*lead*) | lead | tempo entre a emissão da previsão e o instante previsto |
| habilidade verificada | verified skill | o quanto a previsão acertou, medido contra o que aconteceu |
| verdade | truth | o valor com que a previsão é comparada: uma observação ou uma análise |
| portão | gate | uma verificação que precisa passar antes do passo seguinte |
| modo sombra | shadow | a previsão roda e é avaliada, mas não aparece para o banhista |
| histerese | hysteresis | a regra que impede a troca a cada pequena oscilação |
| Hs, altura significativa de onda | Hs, significant wave height | em metros; aproximadamente a média do terço mais alto das ondas |
| *ensemble* | ensemble | conjunto de previsões do mesmo modelo com pequenas perturbações |
| ressaca | ressaca | o original também usa a palavra em português |
| banhista | swimmer, user | quem lê a previsão para decidir se entra no mar |
| boia | buoy | o instrumento que mede a onda no mar |

A coluna "No original" é a correspondência usual. Confira no documento que você está adaptando
antes de usar: se ele define o termo de outro jeito, vale a definição dele.

## 6. O roteiro

Use estas seções, nesta ordem. Tire a que o original não tem conteúdo para preencher, menos
"E a segurança?", que aparece sempre.

```markdown
## Em poucas palavras
<3 a 5 frases: o que foi decidido, e em que estágio está. Se nada foi construído, diga aqui.>

## Qual é o problema?
<Por que este documento existe. Só os motivos que o original dá.>

## O que foi decidido
<Uma decisão por parágrafo ou por item, cada uma com o seu porquê e a seção do original.>

## O que muda para quem usa o marola
<O que a pessoa vai ver de diferente, e quando. Se nada muda por enquanto, diga.>

## E a segurança?
<O que o documento muda, ou não muda, nas regras que decidem se o mar está seguro.>

## O que ainda não está pronto, e o que pode dar errado
<Dependências, riscos e limites, sem suavizar.>

## O que continua em aberto
<As questões em aberto do original.>

## Palavras usadas
<Os termos de arte, um por linha.>

## Onde ler o resto
<Uma lista: assunto → seção do original, com link.>
```

## 7. Exemplo de adaptação

O trecho abaixo é a seção 6 de `docs/MIPs/MIP-0091-marola-forecaster-architecture.md`:

> None. `Swimability.score` and the ressaca veto (MIP-0062) read nothing that this MIP produces.
> `cli` does not depend on `forecaster`. The only product-side change, in stage 4, is which model id
> `OpenMeteoClient` passes in `models=`, from a committed file, with the spread of MIP-0038 shown
> beside it.

**Tradução ao pé da letra, que não serve:**

> Nenhum. O `Swimability.score` e o veto de ressaca (MIP-0062) não leem nada que esta MIP produz.
> O `cli` não depende do `forecaster`. A única mudança do lado do produto, no estágio 4, é qual
> id de modelo o `OpenMeteoClient` passa em `models=`.

**Versão fácil:**

> ## E a segurança?
>
> Nada muda na segurança. A nota de banho e o veto de ressaca (MIP-0062) não usam nada do que
> esta MIP cria.
>
> A única mudança no que o marola mostra vem na etapa 4. Nela, muda qual modelo de previsão o
> marola consulta. Junto do número, o marola mostra o quanto os modelos discordam entre si
> (MIP-0038).
>
> O detalhe técnico está na [§6 do original](MIP-0091-marola-forecaster-architecture.md#6-scoring-safety-impact).

O que aconteceu: a afirmação de segurança veio para a primeira frase e ficou inteira. As duas
referências ficaram. Os nomes de código saíram, com um ponteiro. Nada foi acrescentado.

## 8. Conferência antes de entregar

1. Liste as decisões do original e marque cada uma na versão fácil.
2. Liste os números com unidade da versão fácil. Cada um existe no original, igual. A única
   diferença permitida é a vírgula decimal (`2.5 m` no original, "2,5 m" aqui).
3. Liste as afirmações de segurança, os riscos e as questões em aberto do original. Todas
   estão lá, com a mesma força.
4. Abra cada link e cada âncora.
5. Conte as palavras: `wc -w <arquivo>`.
6. Passe o `catalogo-pt-br.md` no seu próprio texto.
7. Rode `just quality`.
