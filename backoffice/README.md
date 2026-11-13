# backoffice

Frontend da plataforma de cotação de seguros, conforme o
[PRD de frontend](../docs/prds/prd-cotacao-seguros-frontend-v2.md): o corretor pede cotações de seguro auto,
acompanha as consultas, compara e seleciona ofertas e complementa dados por versão; o operador técnico acompanha
as ocorrências e pede reconciliações. O painel consome só a API de produto da `quote-solution`
([`contracts/openapi.yaml`](../contracts/openapi.yaml)) e o login do user pool da mesma stack. As decisões de
stack e de autenticação estão no [ADR 0016](../docs/decisoes/0016-backoffice-angular-e-login-cognito.md).

## Sumário

- [Stack](#stack)
- [Origem: o painel do marketplace](#origem-o-painel-do-marketplace)
- [Estrutura](#estrutura)
- [Rodar no Floci](#rodar-no-floci)
- [Rodar contra o stage dev da AWS](#rodar-contra-o-stage-dev-da-aws)
- [Configuração](#configuração)
- [Telas e rotas](#telas-e-rotas)
- [Comportamentos do contrato](#comportamentos-do-contrato)
- [Comandos](#comandos)
- [Testes](#testes)
- [Cobertura do PRD](#cobertura-do-prd)
- [Limites conhecidos](#limites-conhecidos)

## Stack

| Peça                                                                                    | Versão                   |
| --------------------------------------------------------------------------------------- | ------------------------ |
| Node.js / pnpm                                                                          | 24 / 11                  |
| Angular (core, router, forms) / `@angular/build` e CLI                                  | 22.2.5 / 22.2.6          |
| CoreUI for Angular (grátis) / `@coreui/coreui` / ícones                                 | 5.7.42 / 5.9.1 / 3.1.0   |
| TypeScript                                                                              | 6.0.3                    |
| Cliente gerado: `@hey-api/openapi-ts` (`client-angular`, SDK, `httpResource` e schemas) | 0.99.0                   |
| Testes de unidade e componente: Vitest pelo `@angular/build:unit-test`, com jsdom       | 5.1.0                    |
| Testes de navegador: Playwright (Chromium)                                              | 1.65.0                   |
| ESLint / angular-eslint / Prettier                                                      | 9.39.5 / 22.6.0 / 3.9.10 |

## Origem: o painel do marketplace

O shell, o layout, o tema e os padrões vêm do `admin-web` do projeto `marketplace-nx`, que por sua vez parte do
[CoreUI Free Angular Admin Template](https://github.com/coreui/coreui-free-angular-admin-template) (licença MIT
em [`LICENSE-coreui`](LICENSE-coreui)). Como este repositório não usa Nx, cada lib virou uma pasta da aplicação:

| No marketplace (Nx)                                                               | Aqui                                                |
| --------------------------------------------------------------------------------- | --------------------------------------------------- |
| `apps/admin-web` (shell, layout, auth, interceptors)                              | `src/app/{layout,core}`                             |
| `libs/shared/admin-ui` (estados, toast, paginação, modal, guarda de saída, pipes) | `src/app/shared/ui`                                 |
| `libs/shared/web-util` (cursor, datas, dinheiro, erros)                           | `src/app/shared/util` e `src/app/shared/state`      |
| `libs/<contexto>/admin/data-access` (Hey API)                                     | `src/app/shared/api`                                |
| `libs/<contexto>/admin/feature-*`                                                 | `src/app/features/{quotes,offers,operations}`       |
| `apps/admin-web-e2e`                                                              | `e2e/`                                              |
| Alvos do Nx                                                                       | Scripts do `package.json` e alvos do `angular.json` |

O que muda em relação ao marketplace, pelo contrato desta plataforma:

| Assunto         | Marketplace                                          | Aqui                                                                                                                                |
| --------------- | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Login           | Keycloak, code flow com PKCE (`angular-oauth2-oidc`) | User pool da stack, `USER_PASSWORD_AUTH` na tela `/login` ([ADR 0016](../docs/decisoes/0016-backoffice-angular-e-login-cognito.md)) |
| Quem pode o quê | Capacidades de `GET /me`                             | `permissions` de `GET /v1/me`                                                                                                       |
| Erro da API     | RFC 9457 (`type`, `title`, `errors`)                 | `code`, `message`, `correlationId` e `fieldErrors` (`path` e `message`)                                                             |
| Escrita         | `If-Match` com a versão do corpo                     | `Idempotency-Key` por intenção e `expectedVersion` na nova versão                                                                   |
| Atualização     | Leitura sob demanda                                  | Polling no ritmo do `retryAfterSeconds`, com `revision` monotônica                                                                  |

## Estrutura

```text
backoffice/
  angular.json            build, dev server (com proxy.conf.mjs) e testes de unidade
  openapi-ts.config.ts    geração do cliente a partir de ../contracts/openapi.yaml
  proxy.conf.mjs          /v1 → API da quote-solution; /cognito → Cognito; lê runtime/environment.json
  playwright.config.ts    jornadas do PRD no Chromium, com as fixtures do contrato
  scripts/
    configure.mjs         resolve a stack implantada e grava runtime/environment.json e public/config.json
    local-users.mjs       cria os usuários corretor e operador no Cognito do Floci
  e2e/                    testes de navegador e o simulador da API (support/mock-api.ts)
  src/
    main.ts               lê /config.json e inicia o app
    app/
      core/               configuração, login no Cognito, sessão (GET /v1/me), guards e interceptors
      layout/             menu lateral por permissão, cabeçalho, breadcrumb e rodapé do template
      shared/api/         cliente gerado (generated/) e a ligação com o HttpClient
      shared/state/       mutação idempotente, polling, armazenamento da aba e métricas de interface
      shared/ui/          componentes e pipes do template
      shared/util/        erros do contrato, cursor, datas e decimais
      features/quotes/    lista, nova cotação, versão, complementação e formulário
      features/offers/    oferta, comparação e seleção
      features/operations/ ocorrências, reconciliação e acompanhamento
      views/              login e aviso de usuário sem acesso
    testing/              fixtures do contrato e ajudas dos testes de unidade
```

## Rodar no Floci

Pré-requisitos do [README da raiz](../README.md#pré-requisitos), com o Node.js 24.

```bash
# Na raiz: Floci, dependências (inclusive as do backoffice) e as stacks da cotação
docker compose up -d
pnpm install
pnpm deploy:local                  # ou só: mvn -B -q package -DskipTests && pnpm deploy:platform && pnpm deploy:provider && pnpm deploy:quote

# No backoffice/
cd backoffice
BACKOFFICE_PASSWORD='<senha>' pnpm run users:local   # corretor e operador da org-acme
pnpm run configure                                   # stage local
pnpm start                                           # http://localhost:4200
```

A senha segue a política do pool: 12 ou mais caracteres, com maiúscula, minúscula, número e símbolo. O `corretor`
tem `quotes:read`, `quotes:write` e `offers:select`; o `operador`, `quotes:read`, `operations:read` e
`operations:reconcile`. As referências do catálogo de cada organização estão em
[`contracts/fixtures/catalog.json`](../contracts/fixtures/catalog.json), e o comportamento de cada veículo de
cenário, no [§10 do desenho](../docs/plataforma-de-cotacao.md).

O `configure` precisa ser refeito depois de cada implantação da `quote-solution`, porque o id da API e o do app
client mudam. Sem o `runtime/environment.json`, o dev server sobe sem proxy e avisa no terminal.

## Rodar contra o stage dev da AWS

Com a stack implantada pelo [roteiro da AWS](../docs/implantacao-aws-dev.md), que também cria os usuários:

```bash
cd backoffice
pnpm run configure --stage dev --profile <perfil>
pnpm start
```

O dev server encaminha `/v1` para `https://<api-id>.execute-api.<região>.amazonaws.com/dev` e `/cognito` para
`https://cognito-idp.<região>.amazonaws.com`. O `users:local` recusa outro stage que não o `local`.

## Configuração

O `pnpm run configure` grava dois arquivos, fora do git:

| Arquivo                    | Lido por                                               | Conteúdo                                                                                                               |
| -------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `runtime/environment.json` | `proxy.conf.mjs`                                       | Stage, região, origem e caminho base da API e origem do Cognito                                                        |
| `public/config.json`       | o navegador, em `/config.json`, antes de o app iniciar | `stage`, `cognito.clientId`, `cognito.endpoint` (`/cognito`), `pollIntervalSeconds` (3) e `requestTimeoutSeconds` (30) |

O `config.json` só tem valores públicos: o app client não tem segredo, e nenhuma URL de infraestrutura entra no
bundle. Opções do `configure`: `--stage`, `--region` (padrão `AWS_REGION` ou `us-east-1`), `--profile`,
`--poll-interval` e `--request-timeout`. O Floci é procurado em `http://localhost:4566`, ou em `FLOCI_ENDPOINT`.

## Telas e rotas

| Rota                                            | Tela                                                                                                           | Permissão         |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | ----------------- |
| `/login`                                        | Usuário e senha; aviso de sessão expirada (`?expired=1`) e volta segura (`?returnUrl=`, só caminhos do painel) | —                 |
| `/`                                             | Leva à primeira área liberada; sem nenhuma, avisa que o usuário ainda não tem acesso                           | —                 |
| `/quotes`                                       | Lista com filtros de fase, resultado e período e paginação por cursor, tudo na URL                             | `quotes:read`     |
| `/quotes/new`                                   | Formulário, revisão, envio idempotente e verificação do envio sem resposta                                     | `quotes:write`    |
| `/quotes/:id/versions/:version`                 | Andamento, resultado por seguradora, ofertas, pendências e seleção                                             | `quotes:read`     |
| `/quotes/:id/versions/:version/compare?offers=` | Comparação de até três ofertas                                                                                 | `quotes:read`     |
| `/quotes/:id/versions/:version/edit`            | Complementação, que cria a próxima versão                                                                      | `quotes:write`    |
| `/operations`                                   | Ocorrências, reconciliação e acompanhamento das reconciliações pedidas                                         | `operations:read` |

O menu e as guardas seguem `GET /v1/me`; a seleção exige também `offers:select`, e a reconciliação,
`operations:reconcile` e o `RECONCILE` que o servidor oferece na ocorrência. As ações da cotação aparecem só
quando o servidor as oferece em `allowedActions`.

## Comportamentos do contrato

- **Idempotência:** cada intenção (cotação, versão, seleção, reconciliação) recebe uma `Idempotency-Key`. A chave
  e o corpo ficam na aba antes do envio; sem resposta (rede, prazo de 30 s ou `502`/`504`), a tela trava a
  edição e oferece verificar, reenviando os mesmos. Depois de um `503` ou `429`, o mesmo corpo reusa a chave;
  depois de uma recusa definitiva, a próxima tentativa é outra intenção. Não há retry automático de `POST`.
- **Polling:** uma leitura por vez, no ritmo do `retryAfterSeconds` (3 s sem ele), suspensa com a aba oculta,
  com leitura imediata ao voltar, e encerrada em `TERMINAL`. O "Atualizar" e a seleção leem de novo sem abrir
  outra rodada.
- **Revisão:** uma resposta com `revision` menor não substitui a exibida; a da versão e a das ofertas são
  comparadas cada uma com a sua.
- **Falha de rede:** os dados recebidos continuam na tela, com o horário da última leitura e o aviso.
- **Validade das ofertas:** estimada pelo `serverTime`; quem decide é o servidor (`selectable` e
  `409 OFFER_EXPIRED`).
- **Valores:** decimais do contrato formatados e comparados sem passar por `number`; zero, "Não informado" e
  "Não incluída" são estados diferentes.
- **Sessão:** um `401` renova o token uma vez e repete a chamada; recusado de novo, leva ao login com o retorno.
  A saída revoga o refresh token, apaga o que o painel guardou na aba e recarrega a página. As tentativas de
  envio ficam numa partição por usuário e organização.
- **Correlação:** cada chamada leva `X-Correlation-Id`, e os erros mostram o `correlationId` para o suporte.
- **Métricas:** `QuoteFormStarted`, `QuoteSubmissionAccepted`, `FirstOfferDisplayed`, `OfferComparisonOpened`,
  `OfferSelectionConfirmed` e `UiRecoveryRequested` saem como o evento DOM `backoffice:analytics`, só com ids
  opacos, versão, contagens e códigos; sem coletor ouvindo, nada sai do navegador.

## Comandos

Na pasta `backoffice/` (ou da raiz, com `pnpm --filter backoffice <script>`):

| Comando                                     | O que faz                                                                                       |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `pnpm start`                                | Dev server em http://localhost:4200, com o proxy                                                |
| `pnpm run configure`                        | Configura o painel para a stack implantada (veja [Configuração](#configuração))                 |
| `pnpm run users:local`                      | Cria os usuários de exemplo no Floci                                                            |
| `pnpm build`                                | Build de produção em `dist/backoffice/browser`                                                  |
| `pnpm test`                                 | Testes de unidade e de componente (Vitest, até 2 workers; `VITEST_MAX_WORKERS` muda)            |
| `pnpm e2e`                                  | Testes de navegador (Playwright, 1 worker; `E2E_WORKERS` muda); sobe o dev server na porta 4310 |
| `pnpm lint`                                 | ESLint, com as regras do angular-eslint, de acessibilidade dos templates e do Playwright        |
| `pnpm run typecheck:e2e`                    | Checagem de tipos dos testes de navegador                                                       |
| `pnpm run codegen`                          | Gera o cliente de novo a partir de `../contracts/openapi.yaml`                                  |
| `pnpm run format` / `pnpm run format:check` | Prettier, com o `.prettierrc` do Angular (100 colunas, aspas simples)                           |

O build do Angular faz a checagem de tipos do app (`strict` e `strictTemplates`). O código em
`src/app/shared/api/generated` não é editado nem formatado à mão: mudou o contrato, rode o `codegen`.

## Testes

As respostas simuladas existem só nos testes, e todas saem de [`contracts/fixtures`](../contracts/fixtures), as
mesmas que o backend confere contra o `openapi.yaml`.

| Nível                | Onde                                                          | Quantidade                  | Tempo aproximado  |
| -------------------- | ------------------------------------------------------------- | --------------------------- | ----------------- |
| Unidade e componente | `src/**/*.spec.ts`, com `HttpTestingController`               | 76 testes em 17 arquivos    | 3 s, mais o build |
| Navegador            | `e2e/*.spec.ts`, com `page.route` (`e2e/support/mock-api.ts`) | 34 testes, 3 deles a 360 px | 2 min             |

O primeiro `pnpm e2e` precisa do Chromium do Playwright 1.65: `pnpm exec playwright install chromium`.

## Cobertura do PRD

| Caso                                 | Onde está coberto                                                                                                        |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| FT01 lista vazia                     | `e2e/quotes-list.spec.ts`                                                                                                |
| FT02 validação                       | `e2e/new-quote.spec.ts`, `new-quote-page.component.spec.ts`                                                              |
| FT03 duplo clique e timeout          | `e2e/new-quote.spec.ts` (também depois de recarregar), `new-quote-page.component.spec.ts`, `idempotent-mutation.spec.ts` |
| FT04 oferta parcial                  | `e2e/quote-detail.spec.ts`, `quote-version-store.spec.ts`                                                                |
| FT05 recusa versus indisponibilidade | `e2e/quote-detail.spec.ts`, `quote-presentation.spec.ts`                                                                 |
| FT06 rede indisponível               | `e2e/quote-detail.spec.ts`, `quote-version-store.spec.ts`                                                                |
| FT07 respostas fora de ordem         | `e2e/quote-detail.spec.ts`, `quote-version-store.spec.ts`                                                                |
| FT08 aba oculta                      | `e2e/quote-detail.spec.ts`, `poller.spec.ts`                                                                             |
| FT09 complementação                  | `e2e/complement.spec.ts` (inclui o `409 VERSION_CONFLICT`)                                                               |
| FT10 expiração durante a seleção     | `e2e/selection.spec.ts`                                                                                                  |
| FT11 seleção durante `QUOTING`       | `e2e/selection.spec.ts`                                                                                                  |
| FT12 sessão e permissões             | `e2e/session.spec.ts`, `auth.interceptor.spec.ts`, `nav.spec.ts`                                                         |
| FT13 troca de usuário e saída        | `e2e/session.spec.ts`, `auth-session.spec.ts`, `session-storage.spec.ts`                                                 |
| FT14 comparação heterogênea          | `e2e/comparison.spec.ts`, `offer-comparison.spec.ts`                                                                     |
| FT15 acessibilidade e tela estreita  | `e2e/mobile.spec.ts` (360 px e teclado), regras de acessibilidade do lint nos templates                                  |
| FT16 reconciliação                   | `e2e/operations.spec.ts`                                                                                                 |
| FT17 seleção concorrente             | `e2e/selection.spec.ts` (`SELECTION_ALREADY_EXISTS` e `VERSION_SUPERSEDED`)                                              |

Os requisitos FE01 a FE14 estão nas telas da [tabela de rotas](#telas-e-rotas) e nos
[comportamentos do contrato](#comportamentos-do-contrato).

## Limites conhecidos

- A jornada integrada contra a stack implantada (criar, receber oferta parcial, complementar e selecionar no
  Floci ou no `dev`) ainda não foi registrada; é o último item da definição de pronto do PRD (§14).
- O login não conclui o desafio `NEW_PASSWORD_REQUIRED`; o usuário precisa de senha permanente.
- Sem BFF, os tokens ficam no `sessionStorage` da aba ([ADR 0016](../docs/decisoes/0016-backoffice-angular-e-login-cognito.md)).
- Não há publicação do painel: ele roda no dev server, que faz o proxy. Hospedar exige servir `/v1` e `/cognito`
  na mesma origem dos arquivos estáticos.
- A ocorrência não informa a versão da cotação; o link leva à versão 1, cujo aviso de versão substituída leva à
  atual.
- O perfil de cobertura do catálogo não informa a moeda das importâncias de referência; o formulário as mostra em
  BRL, a moeda do laboratório.
- WCAG 2.2 AA é o alvo: os testes cobrem teclado, foco, rótulos, anúncios e tela estreita, mas não houve
  auditoria com leitor de tela.
