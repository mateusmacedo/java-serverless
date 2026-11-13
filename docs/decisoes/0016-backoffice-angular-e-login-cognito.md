# 0016. Backoffice em Angular com o template do marketplace e login no user pool da stack

- Status: aceita
- Data: 2026-11-12

## Contexto

O [PRD de frontend](../prds/prd-cotacao-seguros-frontend-v2.md) pede um backoffice para o corretor e o
operador técnico: lista, formulário, acompanhamento com polling, comparação, complementação por versão,
seleção e painel operacional. O PRD cita React/Next.js como referência, não como restrição (§2), e deixa em
aberto a política de sessão e o provedor OIDC (§9 e §15).

O repositório já tem o painel de referência do projeto `marketplace-nx`: Angular 22 com o template grátis do
CoreUI, cliente gerado pelo Hey API, Vitest e Playwright, organizado em libs de um monorepo Nx. Este
repositório não usa Nx: cada solução tem a própria pasta, o Node entra pelo pnpm e o Java pelo Maven.

O user pool da `quote-solution` (`quote-solution-<stage>-users`) não tem domínio OAuth nem hosted UI. O app
client `quote-solution-<stage>-web` não tem segredo e permite `USER_PASSWORD_AUTH`, `USER_SRP_AUTH` e
`REFRESH_TOKEN_AUTH`; é o fluxo que os testes ponta a ponta já usam. A API não declara CORS.

## Decisão

- O frontend é o `backoffice/`, uma aplicação Angular 22 com o shell do CoreUI, nas mesmas versões e com os
  mesmos padrões do `admin-web` do marketplace (sinais, `httpResource` do cliente gerado, interceptors
  funcionais, guards por permissão, estados de lista, toasts, paginação por cursor, guarda de saída). As libs
  Nx viram pastas da aplicação: `core/`, `shared/{api,state,ui,util}` e `features/{quotes,offers,operations}`.
- É um projeto do Angular CLI sem Nx, membro do workspace pnpm da raiz (`pnpm-workspace.yaml`).
- O cliente da API é gerado pelo Hey API a partir de `contracts/openapi.yaml` (`pnpm codegen`).
- O login usa as APIs públicas do Cognito com `USER_PASSWORD_AUTH`, numa tela `/login` própria, e renova o
  access token pelo `REFRESH_TOKEN_AUTH`. Os tokens ficam no `sessionStorage` da aba, que some ao fechá-la; a
  saída revoga o refresh token, apaga o que o painel guardou na aba e recarrega a página.
- O navegador só fala com a origem do painel: o dev server encaminha `/v1` à API e `/cognito` ao Cognito, com
  os endereços que `pnpm run configure` resolve no stage implantado (`local` no Floci ou `dev` na AWS).
- A navegação segue as `permissions` de `GET /v1/me`; quem autoriza cada recurso continua sendo o servidor.
- Respostas simuladas existem só nos testes: o Vitest e o Playwright respondem com `contracts/fixtures`, e o app
  em execução sempre fala com a API real.

## Consequências

- O backoffice reaproveita, sem adaptação de stack, o conhecimento e os componentes do marketplace.
- Não há BFF nem cookie `HttpOnly`, como o PRD propõe: um script na página consegue ler os tokens da aba. O
  risco fica limitado à aba (sem `localStorage`), ao prazo do access token e à revogação na saída.
- Usuário com senha provisória (`NEW_PASSWORD_REQUIRED`) não conclui o login pelo painel; o administrador
  define a senha permanente, como o roteiro da AWS já faz.
- Publicar o painel fora do dev server exige servir os arquivos estáticos e os caminhos `/v1` e `/cognito` na
  mesma origem, por exemplo com o CloudFront diante do bucket, da API e do Cognito. Isso não faz parte deste
  ADR nem do MVP.
- O `pnpm install` da raiz passa a baixar as dependências do Angular.

## Alternativas consideradas

- **React/Next.js, a referência do PRD:** descartada para não manter dois padrões de frontend entre os
  projetos e porque o PRD não a exige.
- **Code flow com PKCE na hosted UI, como o marketplace faz com o Keycloak:** exige domínio OAuth no user pool,
  URLs de retorno por ambiente e um emulador que implemente os endpoints OAuth do Cognito; fica como evolução
  quando a identidade visual e o provedor forem decididos (PRD §15).
- **BFF com cookie `HttpOnly`:** é a proposta do PRD e a saída deste ADR quando houver hospedagem do painel;
  exige uma camada de servidor nova, fora do escopo do MVP.
- **Modo de fixtures no app em execução:** descartado; as fixtures servem só aos testes, para o app nunca
  mostrar dados simulados como reais.
