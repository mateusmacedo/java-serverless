# PRD Frontend — Plataforma de cotação de seguros

Versão: 2.0 • Data: 11/11/2026 • Status: especificação implementável do MVP
Documento complementar: PRD_Cotacao_Seguros_Backend_v2.md.

## 1. Resumo e problema

Construir um backoffice responsivo para solicitar cotações de seguro auto, acompanhar processamento assíncrono, comparar ofertas e registrar a seleção de uma oferta. O usuário deve distinguir claramente consulta em andamento, recusa comercial, falha técnica e necessidade de informação adicional.

O backend coordena consultas externas e reuso de informações. O frontend apresenta seu contrato de negócio e nunca acessa Step Functions, SQS, banco ou credenciais de seguradoras. Trata-se de implementação nova com dados sintéticos e simuladores na primeira entrega. Não foram realizados testes com usuários ou medições de conversão; resultados comerciais são hipóteses.

## 2. Objetivos, usuários e escopo

Objetivos do usuário: enviar uma única solicitação com segurança, consultar andamento sem permanecer na tela, receber ofertas parciais, entender diferenças de cobertura e selecionar uma oferta ainda válida. Objetivos de produto: reduzir abandono por falta de visibilidade, impedir seleção duplicada e tornar falhas recuperáveis compreensíveis.

Usuários: corretor/operador comercial; operador técnico autorizado para consulta e reconciliação. Sistemas parceiros consomem o backend diretamente e não dependem deste frontend.

P0: autenticação, lista, formulário com catálogos sintéticos, acompanhamento, comparação, complementação por versão, seleção e painel operacional restrito. Fora: proposta enviada à seguradora, emissão, pagamento, cadastro completo de pessoas/veículos, portal público, SEO, aplicativos nativos, notificações externas e reprocessamento irrestrito pelo usuário.

Stack proposta: React/Next.js com TypeScript, cliente HTTP tipado e testes Jest/Testing Library e de navegador. É uma escolha de referência, não uma restrição de produto; versões serão fixadas na implementação. Não é necessário SSE/WebSocket no MVP; polling atende à atualização inicial.

## 3. Jornada e histórias

1. Usuário autentica e vê apenas a organização autorizada.
2. Lista mostra cotações existentes ou estado vazio com ação de criar.
3. Formulário permite escolher proponente, veículo, condutor, uso, cobertura e vigência.
4. Revisão apresenta os dados e informa que o envio inicia consultas, não uma contratação.
5. Aceitação leva ao detalhe com protocolo. Timeout de envio permite recuperar a mesma intenção.
6. Resultados aparecem progressivamente. Falha de uma seguradora não oculta ofertas de outras.
7. Pendência permite complementar e gerar nova versão, se a cotação ainda não tem seleção.
8. Comparação mostra diferenças materiais. Seleção exige confirmação e validação pelo servidor.
9. Sucesso informa “Intenção de proposta registrada”. Não mostrar “Seguro contratado”.

Histórias: como corretor, quero acompanhar sem reenviar; como operador, quero corrigir dados sem apagar o histórico; como usuário, quero saber por que uma oferta não pode ser selecionada; como operação técnica, quero solicitar reconciliação com motivo e acompanhar o resultado.

## 4. Rotas e componentes

| Rota | Responsabilidade |
|---|---|
| /login | Início da autenticação, retorno seguro e mensagem de sessão expirada. |
| /quotes | Lista paginada, filtros, estados vazio/carregando/erro e acesso a detalhes. |
| /quotes/new | Formulário, revisão, envio e recuperação de resultado desconhecido. |
| /quotes/{id}/versions/{version} | Snapshot, fases, resultados por seguradora, ofertas, pendências e seleção. |
| /quotes/{id}/versions/{version}/compare | Comparação de até três ofertas selecionadas para visualização. |
| /quotes/{id}/versions/{version}/edit | Complementação baseada no snapshot e expectedVersion; cria versão, não edita a anterior. |
| /operations | Lista filtrável de ocorrências e acompanhamento de reconciliações. |

Componentes: QuoteForm, QuoteSummary, PhaseTimeline, InsurerResultList, OfferCard, CoverageComparison, MissingInformationPanel, VersionBanner, SelectionDialog, RecoveryNotice, OperationIssueTable e ReconciliationDialog. Componentes são sugestões de divisão, não obrigação de microcomponentização.

## 5. Requisitos e critérios de aceite

| ID | Requisito | Critério de aceite |
|---|---|---|
| FE01 | Autenticar e respeitar permissões | /v1/me controla navegação; acesso direto sem permissão mostra estado apropriado; backend segue responsável por autorização. |
| FE02 | Listar e filtrar | Filtros persistem na URL, paginação por cursor funciona e nova combinação reinicia cursor. |
| FE03 | Coletar entrada válida | Campos obrigatórios identificados, referências escolhidas por rótulo, erro localizado e foco no primeiro campo inválido. |
| FE04 | Enviar idempotentemente | Duplo clique produz uma intenção; timeout reutiliza chave/body; protocolo confirmado é exibido. |
| FE05 | Mostrar progresso | Fase e resultados por seguradora atualizam sem recarregar a página e sem porcentagem fictícia. |
| FE06 | Exibir ofertas parciais | Oferta aparece enquanto outra seguradora está PROCESSING; mensagem informa que mais resultados podem chegar. |
| FE07 | Comparar | Até três ofertas; preço, parcelas, cobertura, limites e franquias; ausência de informação não é zero. |
| FE08 | Complementar com versão | Pendência mostra campos; submissão inclui expectedVersion; 409 não sobrescreve dados do servidor. |
| FE09 | Selecionar | Modal resume oferta; clique só confirma sucesso após resposta; expirada/substituída/seleção anterior são tratadas. |
| FE10 | Preservar resultado em falha de rede | Dados existentes continuam visíveis com horário e aviso; ação de atualizar não recria cotação. |
| FE11 | Controlar operações | Apenas autorizado solicita RECONCILE quando permitido, informa motivo e acompanha operationId. |
| FE12 | Acessibilidade e responsividade | Fluxos completos por teclado, foco previsível, labels e anúncios moderados; sem informação exclusiva por cor. |
| FE13 | Isolar sessão e organização | Logout limpa caches de dados de negócio; cache é particionado por usuário/organização/recurso. |
| FE14 | Tratar resultado desconhecido | Tela não declara falha comercial nem inicia outra solicitação após timeout; mantém recuperação da mesma intenção. |

## 6. Formulário e validação

| Campo | Controle e regra de interface |
|---|---|
| product | AUTO fixo e visível no MVP. |
| applicantRef | Autocomplete do catálogo autorizado; obrigatório. |
| risk.vehicleRef | Seleção do veículo sintético; obrigatório. |
| risk.principalDriverRef | Seleção do condutor; obrigatório. |
| risk.usage | PERSONAL/COMMERCIAL com rótulos claros; obrigatório. |
| coverageProfile | Seleção de perfil e resumo de coberturas retornado pelo catálogo. |
| desiredStartDate | Campo de data no formato de apresentação local, enviado YYYY-MM-DD; obrigatoriedade/formato no cliente, elegibilidade de data no servidor. |

Validação no cliente melhora a interação; não implementa regras de subscrição nem decide seguradoras elegíveis. Erros de contrato usam fieldErrors.path para apontar campos. Catálogo vazio deve explicar indisponibilidade de opções e oferecer atualização, sem aceitar IDs livres. Rascunho fica em memória no MVP; ao sair com alterações não enviadas, avisar sobre perda. Não prometer retomada de rascunho entre dispositivos.

## 7. Estados apresentados ao usuário

| Código | Texto sugerido e ação |
|---|---|
| RECEIVED | Solicitação recebida — protocolo disponível. |
| VALIDATING | Validando dados. |
| ENRICHING | Obtendo informações para a cotação. |
| QUOTING | Consultando seguradoras — resultados podem aparecer gradualmente. |
| COMPLETED | Consultas concluídas — consultar ofertas e recusas individuais. |
| PARTIAL | Ofertas disponíveis; algumas consultas não foram concluídas ou precisam de dados. |
| NEEDS_INFORMATION | Informações adicionais necessárias — complementar quando permitido. |
| INELIGIBLE | Cotação não pode prosseguir — mostrar motivo seguro. |
| NO_OFFER | Nenhuma oferta disponível — mostrar motivo; não atribuir a indisponibilidade técnica. |
| TECHNICAL_FAILURE | Não foi possível concluir as consultas — mostrar protocolo e orientação. |

Por seguradora: PENDING = aguardando; PROCESSING = em consulta; QUOTED = oferta disponível; DECLINED = risco não aceito; NEEDS_INFORMATION = dados adicionais; UNAVAILABLE = indisponível; TIMED_OUT = prazo de resposta excedido.

TERMINAL é fase técnica, apresentada por outcome. Não usar “concluída” isoladamente para transmitir aprovação. Versão substituída mostra banner e link à versão atual, mantendo leitura do histórico e bloqueando seleção. Deadline excedido no relógio local não autoriza inventar outcome; indicar atraso e atualizar o servidor.

## 8. Comparação e seleção

Cada oferta apresenta seguradora, prêmio total e moeda, parcelas quando disponíveis, coberturas/limites, franquias e validade. Ordenação por preço é opcional e rotulada; não destacar automaticamente “melhor oferta”. Valores usam decimal seguro para ordenação e formatação; zero, ausência e cobertura não incluída são estados diferentes.

Comparação usa códigos normalizados de cobertura. Divergência de limite/franquia fica explícita. Campo ausente aparece como “Não informado”; não inferir equivalência. Cards são usados em telas estreitas, preservando todos os dados materiais.

Antes de confirmar, obter dados atuais; exibir oferta e informar que a ação registra intenção. Oferta pode expirar entre refresh e POST: backend decide e interface trata 409 OFFER_EXPIRED. Ao sucesso, bloquear nova seleção conforme contrato. Se a resposta se perder, recuperar com a mesma chave; não presumir inexistência de seleção.

## 9. Integração, atualização e gestão de estado

API de produto é a única fonte de dados do frontend. Estado de servidor separado de estado local de formulário/comparação. Chaves de cache incluem organização, quoteId e versão. Invalidar detalhe/lista após nova versão ou seleção. Nunca guardar task tokens, credenciais de seguradora ou URLs internas de infraestrutura.

Polling conforme contrato compartilhado: sem sobreposição, suspenso com aba oculta, retomada com refresh, encerrado quando terminal. Preservar respostas parciais em falha de rede; respostas fora de ordem não podem regredir a tela. Expiração visual usa serverTime para estimar diferença do relógio, mas validade final é do servidor. Uma indicação de dados desatualizados acompanha falhas repetidas.

Integração OIDC proposta: sessão via camada de servidor/BFF com cookie HttpOnly, Secure e política SameSite apropriada; proteção CSRF nas mutações e retorno de login validado. O BFF só adapta sessão/transporte, sem duplicar regras ou orquestração. Nunca usar client secret no bundle. Política exata de sessão e provedor deve ser definida antes da implementação da autenticação.

## 10. Painel operacional

Mostrar ocorrências com tipo, idade, resumo seguro e referência de cotação quando disponível. Não apresentar payload bruto de fornecedor. RECONCILE habilitado somente com permissão e allowedActions correspondente; exige motivo, confirmação e acompanhamento do status retornado. Uma reconciliação aceita não significa consulta recuperada. Falha exibe correlationId e ação de atualizar; evitar disparos automáticos repetidos.

Callbacks sem correlação podem ser somente consultáveis no MVP. A associação manual arbitrária e o reenvio irrestrito de consultas ficam fora do escopo.

## 11. Requisitos de conteúdo e qualidade

- Português brasileiro, termos consistentes: cotação, oferta, intenção de proposta. Não mostrar nomes de estados AWS ao usuário comercial.
- WCAG 2.2 AA como alvo de acessibilidade, a verificar na implementação; teclado, contraste, labels, foco e feedback de erro.
- Layout testado em larguras de 360, 768 e 1280 px; comparação não perde conteúdo no mobile.
- Meta proposta: em ambiente de teste, interação local em até 200 ms e atualização de oferta até um ciclo de polling mais latência da API. Não é benchmark já alcançado.
- Testes de sessão expirada preservam somente o contexto seguro necessário para retorno; logout apaga dados de cache e metadados de tentativa.
- Sem dados pessoais em analytics, URLs ou logs do navegador. IDs opacos e correlationId são suficientes.
- Configuração por ambiente: URL da API/BFF, emissor/cliente OIDC, polling padrão e modo de fixtures. Segredos apenas no servidor.

## 12. Métricas

Medir início de formulário, solicitação aceita, primeira oferta exibida, comparação aberta, seleção confirmada e erro de interação. Eventos: QuoteFormStarted, QuoteSubmissionAccepted, FirstOfferDisplayed, OfferComparisonOpened, OfferSelectionConfirmed e UiRecoveryRequested. Seleção confirmada só é emitida após confirmação do backend.

Indicadores: conclusão do formulário, tempo até primeira oferta visualizada, recuperação de envio após timeout e sucesso da seleção. Segmentar falha técnica e recusa comercial. Sem metas percentuais arbitrárias antes do baseline; guardrails: zero seleção duplicada nos testes, zero exposição entre organizações e nenhuma oferta expirada apresentada como confirmada.

## 13. Testes de aceitação frontend

| Caso | Resultado esperado |
|---|---|
| FT01 — lista vazia | Mensagem e ação de criar; não mostrar erro. |
| FT02 — validação | Erros em campos e foco correto; nenhuma chamada inválida disparada. |
| FT03 — duplo clique/timeout | Uma intenção e recuperação com a mesma chave e body. |
| FT04 — oferta parcial | Card de A aparece enquanto B está processando. |
| FT05 — recusa versus indisponibilidade | Textos e estados distintos, sem conversão automática. |
| FT06 — rede indisponível | Ofertas existentes preservadas; aviso e atualização disponível. |
| FT07 — respostas fora de ordem | Revision menor não regride o mesmo recurso. |
| FT08 — aba oculta/retorno | Polling suspenso e refresh ao retornar; sem duplicação de loops. |
| FT09 — complementação | Nova versão criada; histórico preservado; conflito tratado. |
| FT10 — expiração durante seleção | 409 apresentado; nenhuma confirmação falsa. |
| FT11 — seleção durante QUOTING | Permitida apenas quando servidor autoriza; consultas continuam. |
| FT12 — sessão/permissões | Login/401/403 tratados; operação restrita não aparece nem é aceita pelo backend. |
| FT13 — troca/logout | Cache anterior removido; dados não atravessam sessões. |
| FT14 — comparação heterogênea | Limites e franquias distintos destacados; ausência não é zero. |
| FT15 — acessibilidade/mobile | Formulário, comparação e modal utilizáveis por teclado e tela estreita. |
| FT16 — reconciliação | 202 mostra operação pendente; acompanha sucesso/falha sem repetir comando. |
| FT17 — seleção concorrente | VERSION_SUPERSEDED/SELECTION_ALREADY_EXISTS tratados conforme contrato. |

Mocks incluem todas as fases/outcomes e códigos 400/401/403/404/409/429/503. Testes de componente para estados; testes de navegador para jornadas e recuperação. Integração final usa backend de desenvolvimento com simuladores, sem depender de fornecedores pagos.

## 14. Entrega incremental e handoff

| Marco | Estimativa relativa | Entrega |
|---|---|---|
| F1 | 1 semana | Shell autenticado, cliente tipado, fixtures e listagem. |
| F2 | 1 semana | Formulário, envio idempotente e recuperação. |
| F3 | 1–2 semanas | Acompanhamento, polling e ofertas parciais. |
| F4 | 1 semana | Comparação, versões e seleção. |
| F5 | 1 semana | Operações restritas, testes completos e integração. |

Estimativas indicativas, não compromisso. Frontend pode desenvolver com mocks em paralelo após congelar contrato compartilhado. Alterações de contrato exigem ajuste de fixtures e ambos os PRDs.

Estrutura sugerida: apps/backoffice; features/quotes, features/offers, features/operations; shared/api, shared/auth, shared/ui; contracts/openapi.yaml e contracts/fixtures compartilhados com backend. Não criar persistência paralela de negócio no frontend.

Definition of Done: FE01–FE14 e FT01–FT17 aprovados; contrato validado; fluxo integrado cria cotação, recebe oferta parcial, complementa quando necessário e seleciona sem duplicidade; navegação responsiva/acessível revisada; recuperação de timeout demonstrada; analytics sem dados pessoais; documentação de execução local com mocks e ambiente integrado.

## 15. Riscos e decisões abertas

- Confirmar identidade visual e provedor OIDC; manter layout neutro até decisão.
- Dados comerciais reais podem ampliar o formulário; laboratório usa catálogo sintético definido no contrato.
- Normalização de coberturas depende do backend e homologação de produto; não compensar diferenças por heurísticas visuais.
- Fluxo de troca de oferta selecionada, envio de proposta e emissão requerem nova especificação.
- Custo de polling deve ser medido; SSE/WebSocket é evolução condicionada à necessidade.

## Contrato compartilhado de integração — versão 2.0

Esta seção é reproduzida nos dois PRDs e deve permanecer idêntica. O backend é responsável pelo contrato OpenAPI; frontend e simuladores usam os mesmos schemas e fixtures. Alterações incompatíveis exigem revisão dos dois PRDs. Datas/horários são ISO 8601 em UTC; valores monetários são strings decimais acompanhadas de moeda; IDs são opacos.

### Identidade, dados de entrada e permissões

- GET /v1/me retorna userId, displayName, organizationId e permissions. Permissões do MVP: quotes:read, quotes:write, offers:select, operations:read, operations:reconcile.
- Para o laboratório, GET /v1/catalog/customers, /vehicles e /drivers retornam fixtures autorizadas com id e label; aceitam search e cursor. GET /v1/catalog/coverage-profiles retorna códigos, rótulos e resumo das coberturas simuladas. Não implementar cadastro completo neste MVP.
- Criação exige product=AUTO, applicantRef, risk.vehicleRef, risk.usage (PERSONAL ou COMMERCIAL), risk.principalDriverRef, coverageProfile e desiredStartDate. Formato e referências são validados no servidor. O backend resolve referências para snapshot imutável; o frontend não precisa digitar IDs.
- O servidor é autoridade de organização e autorização em todos os endpoints. Esconder botão não autoriza recurso.

### Endpoints e resposta

| Endpoint | Contrato compartilhado |
|---|---|
| POST /v1/quotes | Header Idempotency-Key; body acima; 202 com quoteId, version, phase, statusUrl. Repetição idêntica retorna mesma identidade. |
| GET /v1/quotes | cursor, phase, outcome, createdFrom, createdTo; retorna items e nextCursor; resumo com id, currentVersion, phase, outcome, createdAt e offerCount. |
| GET /v1/quotes/{id}/versions/{v} | Snapshot autorizado, phase, outcome, currentVersion, supersededAt, revision, updatedAt, serverTime, retryAfterSeconds, missingFields, insurerResults, selection, allowedActions. |
| GET /v1/quotes/{id}/versions/{v}/offers | Lista com revision, serverTime, items e nextCursor; item contém id, insurerId/name, premiumTotal, currency, installments, coverages, deductibles, validUntil, selectable e unavailabilityReason. |
| POST /v1/quotes/{id}/versions | Idempotency-Key, expectedVersion e novo snapshot; 202 com version/statusUrl; divergência de expectedVersion retorna 409. |
| POST /v1/quotes/{id}/versions/{v}/selection | Idempotency-Key e offerId; 201 com selectionId, offerId, selectedAt e status=INTENT_RECORDED; repetição idêntica retorna seleção existente. |
| GET /v1/operations/issues | Restrito; cursor, type e status; items com id, queryId opcional, quoteId opcional, type, status, firstSeenAt, lastSeenAt, safeSummary e allowedActions. Sem tokens/payload bruto. |
| POST /v1/operations/queries/{id}/reconcile | Idempotency-Key e reason obrigatório; 202 com operationId, status=QUEUED e statusUrl. |
| GET /v1/operations/{operationId} | status QUEUED/RUNNING/SUCCEEDED/FAILED, safeSummary e updatedAt. |

missingFields contém path, label e reasonCode. allowedActions usa CREATE_VERSION e SELECT_OFFER nas cotações; RECONCILE nos itens operacionais quando queryId existe. Um callback não correlacionado pode ser apenas consultável, sem botão de reconciliação manual indevido.

phase: RECEIVED, VALIDATING, ENRICHING, QUOTING, TERMINAL. outcome: null enquanto ativo; depois COMPLETED, PARTIAL, NEEDS_INFORMATION, INELIGIBLE, NO_OFFER ou TECHNICAL_FAILURE. insurerResults.status: PENDING, PROCESSING, QUOTED, DECLINED, NEEDS_INFORMATION, UNAVAILABLE ou TIMED_OUT. Motivos expostos são códigos e mensagens seguros, não stack traces.

### Seleção e concorrência

Seleção permitida também durante QUOTING quando a oferta está válida, a versão é atual e SELECT_OFFER é permitido. Outras consultas continuam; seleção não emite proposta. Uma única seleção por versão: mesma oferta é idempotente; tentativa de trocar por outra retorna 409 SELECTION_ALREADY_EXISTS no MVP. Após seleção, novas versões são bloqueadas com QUOTE_ALREADY_SELECTED. Corrida seleção/criação de versão é serializada no backend: apenas uma ação pode vencer. O sucesso não é presumido por clique ou expiração de timeout.

409 pode retornar IDEMPOTENCY_CONFLICT, VERSION_CONFLICT, OFFER_EXPIRED, VERSION_SUPERSEDED, SELECTION_ALREADY_EXISTS ou QUOTE_ALREADY_SELECTED. Erro padrão: code, message, correlationId, fieldErrors opcionais (path/message). 400 validação; 401 sessão inválida; 403 sem permissão; 404 recurso não acessível; 429 quota com Retry-After; 503 indisponibilidade antes da aceitação durável. Erros de infraestrutura após aceitação aparecem no recurso.

### Atualização e reenvio

Polling inicial de 3 segundos, usando retryAfterSeconds quando fornecido; sem requisições sobrepostas; suspender em aba oculta e fazer refresh ao retornar. Parar polling de progresso em TERMINAL e atualizar novamente antes de uma seleção. Revision é monotônica por recurso; não comparar revision de ofertas com revision da cotação. Respostas antigas não substituem as novas. Falha temporária de consulta mantém dados já recebidos com aviso de desatualização.

Cada intenção de mutação recebe chave própria. Após timeout, reenviar exatamente o mesmo body com a mesma chave; não criar chave nova automaticamente. Enquanto resultado é desconhecido, bloquear edição e oferecer recuperação. Não executar retries de POST fora desse protocolo. Metadados mínimos da tentativa podem ser mantidos na sessão, sem copiar respostas sensíveis para localStorage.

### Entregáveis de integração

OpenAPI versionada em contracts/openapi.yaml; fixtures de progresso, oferta parcial, recusa, indisponibilidade, pendência, expiração e conflito em contracts/fixtures; validação de schema em CI. Frontend pode iniciar com mocks derivados desses contratos; a liberação integrada exige testes contra backend real em desenvolvimento.
