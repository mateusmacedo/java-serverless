# Implantação e verificação no stage `dev` da AWS

Este guia leva a plataforma de cotação (barramento da plataforma, `provider-solution` e
`quote-solution`) a uma conta AWS de desenvolvimento, no stage `dev`, e roteia a verificação do M6: o
que o Floci não reproduz e ficou marcado na coluna "M6" do §13 e na lista "M6" do §14 do
[desenho técnico](plataforma-de-cotacao.md).

Estado: os `serverless.yml` e o perfil `e2e-aws` do `quote-e2e` estão preparados para o `dev`, e os
templates do `dev` foram gerados e conferidos localmente, com todas as chamadas desviadas para o
Floci. Nada deste guia foi executado numa conta AWS. Os resultados esperados vêm do código, do desenho
e da documentação da AWS; onde o comportamento não foi verificado, o texto diz.

Convenções:

- Todo comando AWS deste guia usa o boto3 por `uv run --no-project --with boto3 python`, com
  `region_name` explícito. Nenhum usa a AWS CLI.
- A região é `us-east-1`, fixa em `provider.region` nos três `serverless.yml` e no padrão de
  `e2e.region` do `quote-e2e`. Para usar outra, mude os quatro lugares antes do primeiro deploy.
- Os scripts usam `STAGE = "dev"`. Para outro stage, troque a constante e o `--stage`.

## 1. Visão geral

| Ordem | Solução | Diretório | Stack do CloudFormation | O que cria |
|---|---|---|---|---|
| 1 | Plataforma | `platform/events` | `platform-dev` | Barramento `platform-dev-events` (ADR 0013) |
| 2 | `provider-solution` | `provider-solution` | `provider-solution-dev` | 8 Lambdas, API `dev-provider-solution`, tabelas `-queries` e `-simulators`, bucket `-payloads`, 4 filas com DLQ, `stream-failures`, 2 segredos, tópico de alarmes e 16 alarmes |
| 3 | `quote-solution` | `quote-solution` | `quote-solution-dev` | 7 Lambdas, API `dev-quote-solution`, máquina `quote-solution-dev-quote-flow`, tabela `-quotes`, filas `results` e `issues` com DLQ, `stream-failures`, segredo `token-key`, user pool, tópico de alarmes e 8 alarmes |

As duas soluções chegam ao barramento pelo nome, sem referência entre stacks. O barramento sobe antes
delas, e a provider e a quote sobem em qualquer ordem entre si
([ADR 0013](decisoes/0013-barramento-da-plataforma.md)); na remoção, as soluções saem antes do barramento.
A numeração da tabela é a ordem sugerida, que os comandos deste guia seguem.

O `dev` não tem bloco próprio de `params` e herda o `default`, que traz os valores da AWS. O que muda
entre o `local` e o `dev` (`awsEndpointUrl`, `jwtIssuerBase`, `insurerTimeoutSeconds`,
`s3PathStyleAccess`, `providersBaseUrl` e `betaLateCallbackDelaySeconds`), com o efeito de cada valor, está
nas colunas `default` e `local` dos
[parâmetros por stage](plataforma-de-cotacao.md#12-parâmetros-por-stage) do desenho técnico. O
`alarmEmail` é vazio nos dois stages; no `dev`, o e-mail chega por `--param` (seção 2.6).

## 2. Pré-requisitos

### 2.1 Conta, região e cotas

- Uma conta AWS de desenvolvimento, sem outro stage `dev` destas soluções. Os nomes levam o stage,
  então outros stages convivem na mesma conta.
- Região `us-east-1` (ver as convenções acima).
- Concorrência de Lambda suficiente para o T06 com 100 cotações: 100 partidas a frio simultâneas da
  `api` da quote, mais `workflowSteps`, `dispatch` e os workers da provider. A cota não foi conferida.
  Se o valor abaixo for baixo, peça aumento no Service Quotas antes do V08.
- O API Gateway limita a conta a 10.000 requisições por segundo, com rajada de 5.000, por região. O
  T06 fica muito abaixo disso; o 429 do roteiro (V18) é provocado com um limite de stage temporário.

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
limits = boto3.client("lambda", region_name=REGION).get_account_settings()["AccountLimit"]
print("ConcurrentExecutions:", limits["ConcurrentExecutions"])
print("UnreservedConcurrentExecutions:", limits["UnreservedConcurrentExecutions"])
EOF
```

### 2.2 Ferramentas e build

- JDK 21, Maven 3.9, Node.js com pnpm e o login no Serverless Framework v4 (`pnpm sls login`), como no
  [README](../README.md#pré-requisitos).
- `uv`, para os scripts boto3.
- Docker e Floci não são necessários para o `dev`.

Os zips das Lambdas não são gerados pelo Serverless. Gere-os com o mesmo comando do `deploy:local` e
rode os testes de configuração, que conferem as invariantes de tempo e as diferenças do Floci nos
stages `local` e `dev`:

```bash
pnpm install
mvn -B -q package -DskipTests
mvn -B test -pl quote-solution/functions,provider-solution/functions -am \
  -Dtest='FunctionTimingConfigurationTest,ServerlessConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

| Solução | Zip |
|---|---|
| quote | `quote-solution/api/target/quote-api-lambda.zip`, `quote-solution/functions/target/quote-functions-lambda.zip` |
| provider | `provider-solution/api/target/provider-api-lambda.zip`, `provider-solution/simulators/target/provider-simulators-lambda.zip`, `provider-solution/functions/target/provider-functions-lambda.zip` |

### 2.3 Credenciais

- Use um perfil AWS da conta dev (SSO ou papel assumido), configurado em `~/.aws/config`, fora do
  repositório. A sessão do perfil é obtida pelo procedimento da sua conta. Nenhuma chave vai para o
  repositório, para o `.env` ou para os comandos.
- O Serverless resolve `${aws:accountId}` já no `package`, então todo comando `sls` precisa da
  credencial real.
- Não use os scripts `pnpm deploy:*` nem o `pnpm test:e2e` para o `dev`: eles forçam `--stage local`
  e as credenciais `test` do Floci.
- Limpe o shell de variáveis do Floci. O resolvedor de variáveis do Serverless respeita
  `AWS_ENDPOINT_URL`: com ela apontando para o Floci, `${aws:accountId}` resolveu `000000000000` na
  preparação, e os ARNs das políticas IAM sairiam com a conta errada. Credenciais em variáveis de
  ambiente também vencem o perfil.

```bash
export AWS_PROFILE=<perfil-da-conta-dev>
unset AWS_ENDPOINT_URL AWS_ENDPOINT_URL_S3 AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
uv run --no-project --with boto3 python - <<'EOF'
import boto3

identity = boto3.client("sts", region_name="us-east-1").get_caller_identity()
print(identity["Account"], identity["Arn"])
EOF
```

O resultado deve mostrar a conta dev, nunca `000000000000`.

O principal do deploy precisa de: CloudFormation; IAM (criar papéis e `iam:PassRole` nos papéis das
stacks); Lambda; SQS; DynamoDB; S3; Secrets Manager; EventBridge; EventBridge Scheduler; Step
Functions; SNS; CloudWatch; CloudWatch Logs; API Gateway; Cognito; SSM (o Serverless v4 guarda o nome
do bucket de deploy em `/serverless-framework/deployment/s3-bucket`).

### 2.4 Segredos

Nenhum segredo é criado à mão: o CloudFormation gera os três com `GenerateSecretString`, e só o código
os lê.

| Segredo | Stack | Lido por |
|---|---|---|
| `quote-solution/dev/token-key` | quote | `dispatch`, `resume` e `reconciler` (cifra dos task tokens) |
| `provider-solution/dev/query-key-secret` | provider | Papel compartilhado da provider; só o `enrichmentWorker` usa |
| `provider-solution/dev/callback-secret` | provider | `api` e `simCallbackSender` (assinatura `X-Signature`) |

Todos usam a chave gerenciada da AWS; nenhum papel precisa de KMS. Antes do primeiro deploy, confira
que nenhum segredo com esses nomes existe nem está agendado para exclusão, porque um nome ocupado
impede a criação pela stack. O script não lê o valor de nenhum segredo:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
STAGE = "dev"
secrets = boto3.client("secretsmanager", region_name=REGION)
found = 0
for page in secrets.get_paginator("list_secrets").paginate(
        IncludePlannedDeletion=True,
        Filters=[{"Key": "name", "Values": [f"quote-solution/{STAGE}/", f"provider-solution/{STAGE}/"]}]):
    for secret in page["SecretList"]:
        found += 1
        state = f"agendado para exclusão ({secret['DeletedDate']})" if secret.get("DeletedDate") else "ativo"
        print(secret["Name"], state)
print(found, "segredo(s)")
EOF
```

Antes do primeiro deploy, o esperado é `0 segredo(s)`; depois dele, os três ativos. Um segredo agendado
para exclusão, sobra de uma remoção anterior, precisa ser restaurado (`restore_secret`) ou apagado sem
recuperação (`delete_secret` com `ForceDeleteWithoutRecovery=True`) antes do deploy.

### 2.5 Usuários e grupos do Cognito

O user pool `quote-solution-dev-users` só aceita criação administrativa. O CloudFormation cria os
grupos, mas nenhum usuário:

| Grupo | Permissão no `GET /v1/me` |
|---|---|
| `org_org-acme`, `org_org-globex` | Organização do usuário (`org_<organizationId>`) |
| `perm_quotes_read` | `quotes:read` |
| `perm_quotes_write` | `quotes:write` |
| `perm_offers_select` | `offers:select` |
| `perm_operations_read` | `operations:read` |
| `perm_operations_reconcile` | `operations:reconcile` |

O `quote-e2e` cria e mantém os próprios 4 usuários (`e2e-acme-writer`, `e2e-acme-operator`,
`e2e-acme-viewer` e `e2e-globex-operator`). Os roteiros manuais deste guia usam um usuário seu, com a
senha fora do repositório. A política do pool pede 12 ou mais caracteres, com maiúscula, minúscula,
número e símbolo. Uma organização nova precisa de um grupo `org_<organizationId>` novo, que o script
cria quando falta:

```bash
export M6_USERNAME=m6-acme-operador
read -rs M6_PASSWORD && export M6_PASSWORD
uv run --no-project --with boto3 python - org_org-acme perm_quotes_read perm_quotes_write \
  perm_offers_select perm_operations_read perm_operations_reconcile <<'EOF'
import os
import sys

import boto3

REGION = "us-east-1"
STAGE = "dev"
groups = sys.argv[1:]
stack = boto3.client("cloudformation", region_name=REGION).describe_stacks(StackName=f"quote-solution-{STAGE}")
outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Stacks"][0]["Outputs"]}
pool_id = outputs["QuoteUserPoolId"]
cognito = boto3.client("cognito-idp", region_name=REGION)
existing = {group["GroupName"] for page in cognito.get_paginator("list_groups").paginate(UserPoolId=pool_id)
            for group in page["Groups"]}
for group in groups:
    if group not in existing:
        cognito.create_group(UserPoolId=pool_id, GroupName=group)
username = os.environ["M6_USERNAME"]
try:
    cognito.admin_create_user(UserPoolId=pool_id, Username=username, MessageAction="SUPPRESS")
except cognito.exceptions.UsernameExistsException:
    pass
cognito.admin_set_user_password(UserPoolId=pool_id, Username=username, Password=os.environ["M6_PASSWORD"],
                                Permanent=True)
for group in groups:
    cognito.admin_add_user_to_group(UserPoolId=pool_id, Username=username, GroupName=group)
print(username, "nos grupos", groups)
EOF
```

O script roda depois do deploy da quote (seção 3). O usuário fica `CONFIRMED` e entra com
`USER_PASSWORD_AUTH`, que o app client `quote-solution-dev-web` permite sem segredo.

### 2.6 E-mail dos alarmes

Os alarmes das duas soluções notificam `quote-solution-dev-alarms` e `provider-solution-dev-alarms`.
A assinatura por e-mail só existe quando o deploy recebe `--param="alarmEmail=<e-mail>"`, e a AWS
envia um link de confirmação que precisa ser aberto. Passe o parâmetro em todo deploy das duas
soluções: um deploy sem ele torna a condição `HasAlarmEmail` falsa e remove a assinatura. O e-mail não
vai para o repositório. Sem `OKActions`, só a entrada em `ALARM` gera e-mail.

### 2.7 Nome do bucket de respostas

O bucket `provider-solution-dev-payloads` tem nome global no S3, sem a conta no nome. Confira antes do
primeiro deploy:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3
import botocore.exceptions

REGION = "us-east-1"
try:
    boto3.client("s3", region_name=REGION).head_bucket(Bucket="provider-solution-dev-payloads")
    print("existe e é acessível por esta conta")
except botocore.exceptions.ClientError as error:
    print(error.response["Error"]["Code"])
EOF
```

`404` quer dizer livre. `403` quer dizer que o nome é de outra conta: o deploy da provider falharia, e
o `payloadsBucket` do `provider-solution/serverless.yml` teria de mudar, junto com o nome que o
`quote-e2e` deriva do stage.

## 3. Implantação

### 3.1 Conferir o template antes de implantar

O `package` grava o template em `.serverless/` de cada solução (ignorado pelo git) e não cria nada na
conta, mas consulta a conta para resolver `${aws:accountId}`:

```bash
(cd provider-solution && pnpm sls package --stage dev)
(cd quote-solution && pnpm sls package --stage dev)
uv run --no-project python - provider-solution quote-solution <<'EOF'
import json
import sys

for solution in sys.argv[1:]:
    path = f"{solution}/.serverless/cloudformation-template-update-stack.json"
    text = open(path, encoding="utf-8").read()
    template = json.loads(text)
    print(solution, "condição:", json.dumps(template["Conditions"]["HasAwsEndpointUrl"]))
    print(solution, "conta do Floci no template:", "000000000000" in text)
    for name, resource in template["Resources"].items():
        if resource["Type"] == "AWS::Lambda::Function":
            variables = resource["Properties"].get("Environment", {}).get("Variables", {})
            print("  ", name, json.dumps(variables.get("AWS_ENDPOINT_URL")))
EOF
```

O esperado é a condição `{"Fn::Not": [{"Fn::Equals": ["", ""]}]}`, `False` para a conta do Floci e,
em cada função, `{"Fn::If": ["HasAwsEndpointUrl", "", {"Ref": "AWS::NoValue"}]}`.

### 3.2 Implantar

```bash
export ALARM_EMAIL=<e-mail-dos-alarmes>
(cd platform/events && pnpm sls deploy --stage dev)
(cd provider-solution && pnpm sls deploy --stage dev --param="alarmEmail=$ALARM_EMAIL")
(cd quote-solution && pnpm sls deploy --stage dev --param="alarmEmail=$ALARM_EMAIL")
```

O primeiro deploy do Serverless v4 na região cria o bucket de deploy compartilhado
(`serverless-framework-deployments-us-east-1-<id>`, versionado) e o parâmetro SSM que aponta para ele.

### 3.3 Confirmar as stacks

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
STAGE = "dev"
cloudformation = boto3.client("cloudformation", region_name=REGION)
for stack_name in (f"platform-{STAGE}", f"provider-solution-{STAGE}", f"quote-solution-{STAGE}"):
    stack = cloudformation.describe_stacks(StackName=stack_name)["Stacks"][0]
    print(stack_name, stack["StackStatus"])
    for output in stack.get("Outputs", []):
        print("   ", output["OutputKey"], output["OutputValue"])
EOF
```

As três stacks devem estar em `CREATE_COMPLETE` (ou `UPDATE_COMPLETE` nos deploys seguintes). Depois
disso, confirme a assinatura do e-mail (2.6) e crie o usuário manual (2.5).

## 4. Roteiro de verificação

### 4.1 Como usar

Cada linha da tabela 4.2 corresponde a uma linha da coluna "M6" do §13 ou a um item da lista "M6" do
§14. A coluna "Como executar" aponta um caso do `quote-e2e` (seção 5) ou um script da seção 4.3.
Registre o resultado de cada item na tabela 4.4 e leve-o depois ao §13 e ao §14 do desenho.

Um caso do `quote-e2e`, isolado:

```bash
mvn -B verify -Pe2e,e2e-aws -pl quote-solution/e2e -Dit.test='QuoteFlowE2E#t08*'
```

Dois scripts auxiliares se repetem no roteiro. O B1 busca um padrão no log de uma função; o primeiro
argumento é o nome da função, o segundo, o padrão do CloudWatch Logs, e o terceiro, a janela em
minutos:

```bash
uv run --no-project --with boto3 python - quote-solution-dev-reconciler '"reconciler report"' 10 <<'EOF'
import sys
import time

import boto3

REGION = "us-east-1"
function, pattern, minutes = sys.argv[1], sys.argv[2], int(sys.argv[3])
logs = boto3.client("logs", region_name=REGION)
start = int((time.time() - minutes * 60) * 1000)
count = 0
for page in logs.get_paginator("filter_log_events").paginate(
        logGroupName=f"/aws/lambda/{function}", startTime=start, filterPattern=pattern):
    for event in page["events"]:
        count += 1
        moment = time.strftime("%H:%M:%S", time.gmtime(event["timestamp"] / 1000))
        print(moment, event["message"].strip()[:300])
print(count, "ocorrência(s)")
EOF
```

O B2 cria uma cotação com o usuário manual (2.5); os argumentos são veículo, uso e condutor do
catálogo do laboratório (§10):

```bash
uv run --no-project --with boto3 python - vehicle-van-2022 COMMERCIAL driver-acme-bruno <<'EOF'
import datetime
import json
import os
import sys
import urllib.request
import uuid

import boto3

REGION = "us-east-1"
STAGE = "dev"
vehicle, usage, driver = sys.argv[1:4]
stack = boto3.client("cloudformation", region_name=REGION).describe_stacks(StackName=f"quote-solution-{STAGE}")
outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Stacks"][0]["Outputs"]}
token = boto3.client("cognito-idp", region_name=REGION).initiate_auth(
    ClientId=outputs["QuoteUserPoolClientId"], AuthFlow="USER_PASSWORD_AUTH",
    AuthParameters={"USERNAME": os.environ["M6_USERNAME"], "PASSWORD": os.environ["M6_PASSWORD"]},
)["AuthenticationResult"]["AccessToken"]
apis = boto3.client("apigateway", region_name=REGION).get_rest_apis(limit=500)["items"]
api_id = next(api["id"] for api in apis if api["name"] == f"{STAGE}-quote-solution")
start = datetime.datetime.now(datetime.timezone.utc).date() + datetime.timedelta(days=30)
body = {"product": "AUTO", "applicantRef": "customer-acme-frota",
        "risk": {"vehicleRef": vehicle, "usage": usage, "principalDriverRef": driver},
        "coverageProfile": "COMPREHENSIVE", "desiredStartDate": start.isoformat()}
request = urllib.request.Request(
    f"https://{api_id}.execute-api.{REGION}.amazonaws.com/{STAGE}/v1/quotes",
    data=json.dumps(body).encode(), method="POST",
    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
             "Idempotency-Key": f"m6-{uuid.uuid4()}"})
with urllib.request.urlopen(request) as response:
    print(response.status, response.read().decode())
EOF
```

O esperado é `202` com `quoteId`, `version` e `statusUrl`.

### 4.2 Itens

| ID | Item | Origem | Como executar | Resultado esperado |
|---|---|---|---|---|
| V01 | Parâmetros e variáveis do `dev` | §12; §14 (params do stage `dev`) | Script V01 | Nenhuma função com `AWS_ENDPOINT_URL*`; `JWT_ISSUER` com o issuer da AWS; máquina com 360 s, enriquecimento com 20 s e seguradora com 90 s |
| V02 | Agendamento e papel do `reconciler` | §2 (permissões do M4 e do M5); §14 (IAM das permissões novas) | Script V02 | Dois agendamentos `ENABLED`; um ciclo por minuto no log de cada `reconciler` |
| V03 | Task token | §13 (`waitForTaskToken`; IAM do `serverless-step-functions`); §14 | Casos T01 e T04; script V03 | Execuções `SUCCEEDED`; `ResolveEnrichment` e `RequestInsurerQuote` concluídos; nenhum `AccessDenied` no histórico |
| V04 | Callbacks, inclusive corpo fora do ASCII | §13 (assinatura pelo `AWS_PROXY`); §14 | Casos T07, T08, T11 e T12; sonda V04 | Casos passam; a sonda recebe 400 `INVALID_CALLBACK` com a assinatura certa e 401 `INVALID_SIGNATURE` com a errada |
| V05 | IAM efetivo e `AccessDenied` | §2; §13 (papéis próprios da provider); §14 (`IssueProjectorRole`, API e `reconciler`) | `quote-e2e` completo; script V05 | Nenhuma ocorrência nos 15 grupos de log; os 8 mapeamentos de evento `Enabled` |
| V06 | Timeout por estado | §13 (`TimeoutSeconds` por estado); §14 (timeouts) | Caso T08; script V03 | `TaskTimedOut` em `RequestInsurerQuote` depois de 90 s, seguido de `RecordInsurerTimeout`; seguradora `TIMED_OUT` |
| V07 | Timeout da máquina | §13 (`TimeoutSeconds` da máquina); §14 (timeouts) | Deploy temporário V07 | Execução `TIMED_OUT` sem passar por `Catch`; versão finalizada pelo `reconciler` com `WORKFLOW_FAILED` |
| V08 | Vazão do ESM de SQS e `JOIN` ponta a ponta | §13; §14 | Caso T06 com 100 cotações | T06 passa com um líder (`MISS=1`); `JOIN` aparece nas decisões |
| V09 | `TransactionConflict` | §13; §14 (100 pedidos na liderança e corrida do T29) | Durante o V08 e o caso T29; B1 | Conflitos absorvidos pelas repetições; nenhum 503 no T29 |
| V10 | Vazão do ESM de stream | §13 | Script V10, depois do `quote-e2e` | Atraso entre `createdAt` e `publishedAt` do outbox medido e registrado |
| V11 | Retry e divisão de lote do stream | §13 (retry padrão; `MaximumRetryAttempts`, `BisectBatchOnFunctionError` e `onFailure`); §14 | Script V11 | Configuração igual à do `serverless.yml`; na indução, falhas na `stream-failures` e o shard seguindo |
| V12 | Tempos reais de DLQ, inclusive a fila `issues` | §13 (`RedrivePolicy`); §14 | Sonda V12 | Chegada à DLQ cerca de 450 s depois da primeira entrega; dois reenvios e descarte como `DEAD_LETTER` |
| V13 | Visibilidade × timeout | §13; §14 | Script V13 | Valores da tabela de filas do §12; deploy sem `InvalidParameterValueException` |
| V14 | Issuer do Cognito | §13 (`iss` do Cognito); §14 | Script V14 | `iss` igual ao `JWT_ISSUER`; 200 no `/v1/me` com access token; 401 com ID token e sem token |
| V15 | Disparo, `AlarmActions` e recuperação dos alarmes, inclusive os da `issues-dlq` | §13 (CloudFormation de alarmes); §14 | Scripts V15 | 8 e 16 alarmes com o tópico; e-mail na entrada em `ALARM`; volta a `OK` |
| V16 | Extração das métricas EMF | §13; §14 | Script V16 | Métricas nos namespaces `mmda/quote-solution` e `mmda/provider-solution` com `Stage=dev` |
| V17 | `StopExecution` | §13; §14 (T20) | Caso T20 | Execução `ABORTED` sem eventos depois; versão `PARTIAL` finalizada pelo `reconciler` na primeira tentativa |
| V18 | 429 do API Gateway | §11; §14 | Script V18 | 429 com o limite temporário; `Retry-After` e corpo registrados contra o `TooManyRequests` do contrato |
| V19 | Grupos reais do Cognito | §14 (T18, T29 e T30) | Casos T18, T29 e T30 | Casos passam: 404 pela organização, uma vencedora por rodada e 403 sem a permissão |
| V20 | `ISSUE#` no formato anterior ao ADR 0015 | §4; §14 | Script V20 | Nenhum item |

### 4.3 Detalhes

#### V01. Parâmetros e variáveis do `dev`

```bash
uv run --no-project --with boto3 python - <<'EOF'
import json

import boto3

REGION = "us-east-1"
STAGE = "dev"
FUNCTIONS = {
    "quote-solution": ["api", "workflowSteps", "dispatch", "resume", "issueProjector", "outboxRelay",
                       "reconciler"],
    "provider-solution": ["api", "simulators", "simCallbackSender", "enrichmentWorker", "insurerWorker",
                          "reconcileWorker", "outboxRelay", "reconciler"],
}
lambda_client = boto3.client("lambda", region_name=REGION)
for service, names in FUNCTIONS.items():
    for name in names:
        config = lambda_client.get_function_configuration(FunctionName=f"{service}-{STAGE}-{name}")
        variables = config.get("Environment", {}).get("Variables", {})
        endpoints = sorted(key for key in variables if key.startswith("AWS_ENDPOINT_URL")) or "ausente"
        print(config["FunctionName"], f"timeout={config['Timeout']}", f"memória={config['MemorySize']}",
              f"endpoint={endpoints}", f"issuer={variables.get('JWT_ISSUER', '-')}")
account = boto3.client("sts", region_name=REGION).get_caller_identity()["Account"]
machine = f"arn:aws:states:{REGION}:{account}:stateMachine:quote-solution-{STAGE}-quote-flow"
states = boto3.client("stepfunctions", region_name=REGION)
definition = json.loads(states.describe_state_machine(stateMachineArn=machine)["definition"])
insurer = definition["States"]["QuoteInsurers"]["ItemProcessor"]["States"]["RequestInsurerQuote"]
print("máquina:", definition["TimeoutSeconds"], "enriquecimento:",
      definition["States"]["ResolveEnrichment"]["TimeoutSeconds"], "seguradora:", insurer["TimeoutSeconds"])
EOF
```

Esperado: `endpoint=ausente` nas 15 funções; memória 1769 em todas; `issuer` só na `api` da quote, com
`https://cognito-idp.us-east-1.amazonaws.com/<poolId>`; timeouts da quote 29, 15, 15, 15, 15, 30 e
60 s e da provider 29, 29, 15, 25, 25, 25, 60 e 60 s, na ordem da lista; máquina 360, enriquecimento
20 e seguradora 90.

#### V02. Agendamento e papel do `reconciler`

O `ReconcilerRole` da quote é também o papel de execução do agendamento: confia em
`scheduler.amazonaws.com`, com `aws:SourceAccount`, e tem `lambda:InvokeFunction` no `reconciler`. Sem
isso, o `reconciler` nunca rodaria na AWS, e o Floci não acusaria, porque não aplica IAM.

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
STAGE = "dev"
scheduler = boto3.client("scheduler", region_name=REGION)
for page in scheduler.get_paginator("list_schedules").paginate():
    for summary in page["Schedules"]:
        if summary["Target"]["Arn"].endswith(f"-{STAGE}-reconciler"):
            schedule = scheduler.get_schedule(Name=summary["Name"], GroupName=summary["GroupName"])
            print(summary["Target"]["Arn"].split(":")[-1], schedule["State"], schedule["ScheduleExpression"],
                  schedule["Target"]["RoleArn"].split("/")[-1])
EOF
```

Esperado: dois agendamentos `ENABLED` com `rate(1 minute)`; o da quote com o papel gerado a partir do
`ReconcilerRole`, e o da provider com o papel padrão do Serverless. Depois, rode o B1 com
`quote-solution-dev-reconciler '"reconciler report"' 10` e com
`provider-solution-dev-reconciler '"Reconciler cycle"' 10`: cerca de dez linhas em cada, uma por
minuto. Nenhuma linha por mais de dois minutos indica agendamento parado ou sem permissão.

#### V03. Task token e histórico das execuções

Rode os casos T01 e T04 (`-Dit.test='QuoteFlowE2E#t01*+t04*'`) e resuma as execuções mais recentes:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import collections
import json

import boto3

REGION = "us-east-1"
STAGE = "dev"
account = boto3.client("sts", region_name=REGION).get_caller_identity()["Account"]
machine = f"arn:aws:states:{REGION}:{account}:stateMachine:quote-solution-{STAGE}-quote-flow"
states = boto3.client("stepfunctions", region_name=REGION)
for execution in states.list_executions(stateMachineArn=machine, maxResults=10)["executions"]:
    events = []
    for page in states.get_paginator("get_execution_history").paginate(executionArn=execution["executionArn"]):
        events.extend(page["events"])
    types = collections.Counter(event["type"] for event in events)
    exited = collections.Counter(event["stateExitedEventDetails"]["name"] for event in events
                                 if "stateExitedEventDetails" in event)
    timed_out = [round((event["timestamp"] - execution["startDate"]).total_seconds())
                 for event in events if event["type"] == "TaskTimedOut"]
    denied = sum("AccessDenied" in json.dumps(event, default=str) for event in events)
    duration = (execution["stopDate"] - execution["startDate"]).total_seconds() if "stopDate" in execution else None
    print(execution["name"], execution["status"], "duração (s):", duration)
    print("   eventos:", dict(types))
    print("   estados concluídos:", dict(exited))
    print("   TaskTimedOut (s desde o início):", timed_out)
    print("   AccessDenied:", denied)
EOF
```

Esperado: `SUCCEEDED`; `ResolveEnrichment` e `RequestInsurerQuote` entre os estados concluídos, sem
`TaskTimedOut` nem `TaskFailed`. Como nenhuma espera venceu nem falhou nesses casos, os dois estados
terminaram pelo `SendTaskSuccess` da `dispatch` ou do `resume`, restrito ao ARN da máquina. Nenhum
`ExecutionFailed` e `AccessDenied: 0`. A máquina só usa `lambda:invoke.waitForTaskToken`; o
`sqs:sendMessage.waitForTaskToken` da tabela do §13 não tem uso nesta stack. Um `AccessDenied` no
histórico aponta o papel da máquina, gerado pelo `serverless-step-functions`.

#### V04. Callbacks e corpo fora do ASCII

Os casos T07, T08, T11 e T12 passam os callbacks da beta pelo `simCallbackSender`, pelo API Gateway e
pela `api` da provider. O `simCallbackSender` escapa em JSON tudo o que não é ASCII, então esses casos
não exercitam um corpo fora do ASCII. A sonda abaixo envia um corpo UTF-8 assinado direto à `api`. A
`api` confere a assinatura antes de qualquer leitura ou escrita e só depois o envelope; como o corpo
não traz `result`, a assinatura certa chega ao 400, e nada é gravado.

```bash
uv run --no-project --with boto3 python - <<'EOF'
import hashlib
import hmac
import json
import urllib.error
import urllib.request

import boto3

REGION = "us-east-1"
STAGE = "dev"
apis = boto3.client("apigateway", region_name=REGION).get_rest_apis(limit=500)["items"]
api_id = next(api["id"] for api in apis if api["name"] == f"{STAGE}-provider-solution")
url = f"https://{api_id}.execute-api.{REGION}.amazonaws.com/{STAGE}/v1/providers/beta/callbacks"
secret = boto3.client("secretsmanager", region_name=REGION).get_secret_value(
    SecretId=f"provider-solution/{STAGE}/callback-secret")["SecretString"].encode()
body = json.dumps({"externalEventId": "m6-nao-ascii", "providerRequestId": "m6-sonda",
                   "observacao": "cotação em São Paulo – ação"}, ensure_ascii=False).encode("utf-8")


def post(signature):
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8", "X-Signature": signature})
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


print("assinatura certa:", post("sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()))
print("assinatura errada:", post("sha256=" + "0" * 64))
EOF
```

Esperado: 400 `INVALID_CALLBACK` com a assinatura certa e 401 `INVALID_SIGNATURE` com a errada. Um 401
com a assinatura certa quer dizer que os bytes mudaram no caminho, e o escape para ASCII do sender
continua necessário. O T12 usa o `vehicle-beta-early`; na AWS, o callback pode chegar antes do `202`,
ordem que a `api` já trata (§13).

#### V05. IAM efetivo e `AccessDenied` nos logs

Rode o `quote-e2e` completo (seção 5) e procure negações em todos os grupos de log. O SDK Java registra
a negação com o código `AccessDeniedException` (ou `AccessDenied`) e a frase
`is not authorized to perform: <ação> on resource: <ARN>`, que diz a ação e o recurso que faltaram.
Ações de dados, como as do DynamoDB e do SQS, não aparecem no histórico de eventos do CloudTrail sem uma
trilha de eventos de dados; o log da função é a fonte.

```bash
uv run --no-project --with boto3 python - 24 <<'EOF'
import sys
import time

import boto3

REGION = "us-east-1"
STAGE = "dev"
FUNCTIONS = {
    "quote-solution": ["api", "workflowSteps", "dispatch", "resume", "issueProjector", "outboxRelay",
                       "reconciler"],
    "provider-solution": ["api", "simulators", "simCallbackSender", "enrichmentWorker", "insurerWorker",
                          "reconcileWorker", "outboxRelay", "reconciler"],
}
start = int((time.time() - int(sys.argv[1]) * 3600) * 1000)
logs = boto3.client("logs", region_name=REGION)
for service, names in FUNCTIONS.items():
    for name in names:
        hits = []
        for page in logs.get_paginator("filter_log_events").paginate(
                logGroupName=f"/aws/lambda/{service}-{STAGE}-{name}", startTime=start,
                filterPattern='?AccessDenied ?AccessDeniedException ?"not authorized"'):
            hits.extend(page["events"])
        print(f"{service}-{STAGE}-{name}: {len(hits)}")
        for event in hits[:3]:
            print("    ", event["message"].strip()[:300])
lambda_client = boto3.client("lambda", region_name=REGION)
for page in lambda_client.get_paginator("list_event_source_mappings").paginate():
    for mapping in page["EventSourceMappings"]:
        if f"-{STAGE}-" in mapping["FunctionArn"]:
            print(mapping["FunctionArn"].split(":")[-1], mapping["State"],
                  mapping.get("LastProcessingResult", "-"), mapping.get("StateTransitionReason", "-"))
EOF
```

O argumento é a janela em horas. Esperado: 0 nos 15 grupos e 8 mapeamentos `Enabled` (`resume` e
`issueProjector` na quote; os três workers e o `simCallbackSender` na provider; o `outboxRelay` das
duas). Nos dois streams, `LastProcessingResult` deve ser `OK` ou `No records processed`; o da
provider usa o `dynamodb:ListStreams` gerado pelo Serverless, restrito ao stream, e um `PROBLEM`
apontaria a falta dele em `*`. Cada negação encontrada se corrige no `serverless.yml`, nunca pelo
console, comparando com o §7 do desenho. Se um evento do barramento não chegar à fila, a métrica
`FailedInvocations` da regra no namespace `AWS/Events` mostra a entrega recusada.

#### V06. Timeout por estado

Rode o T08 (`-Dit.test='QuoteFlowE2E#t08*'`) e o script do V03. Esperado: na execução do caso,
`TaskTimedOut` uma vez, `RecordInsurerTimeout` entre os estados concluídos e a execução
`SUCCEEDED`; a seguradora que não respondeu fica `TIMED_OUT`. O `TaskTimedOut` deve vir cerca de
90 s depois do início do Map, ou seja, 90 s mais a duração do enriquecimento desde o início da
execução. A máquina não declara `HeartbeatSeconds`, então não há heartbeat a verificar.

#### V07. Timeout da máquina

Nenhum caso do `quote-e2e` chega ao `TimeoutSeconds` da máquina (360 s), porque a soma das esperas é
menor. A verificação usa um deploy temporário com a máquina em 60 s, que viola de propósito a invariante
do §9, e uma cotação cuja beta nunca responde:

```bash
(cd quote-solution && pnpm sls deploy --stage dev --param="alarmEmail=$ALARM_EMAIL" \
  --param="workflowTimeoutSeconds=60")
```

Crie a cotação com o B2 e os argumentos `vehicle-beta-silent PERSONAL driver-acme-ana`. Cerca de dois
minutos depois, rode o script do V03 e o B1 com `quote-solution-dev-reconciler '"WORKFLOW_FAILED"' 10`.
Esperado: a execução termina `TIMED_OUT` com duração de cerca de 60 s, com `ExecutionTimedOut` e sem
`RecordInsurerTimeout`; no ciclo seguinte, o `reconciler` finaliza a versão, como faz com as execuções
`FAILED`, `TIMED_OUT` e `ABORTED` (§9), e registra `WORKFLOW_FAILED`. Em seguida, volte ao valor do
stage e confirme 360 s com o V01:

```bash
(cd quote-solution && pnpm sls deploy --stage dev --param="alarmEmail=$ALARM_EMAIL")
```

#### V08. Vazão do ESM de SQS e `JOIN` ponta a ponta

Confira a concorrência (2.1) e rode o T06 com 100 cotações:

```bash
mvn -B verify -Pe2e,e2e-aws -pl quote-solution/e2e -De2e.concurrentQuotes=100 -Dit.test='QuoteFlowE2E#t06*'
```

O caso dispara 100 `POST` juntos e espera 20 + 0 × 90 + 30 + 100 = 150 s. Registre a linha
`T06 N=100 decisions=... posts=... ms lastTerminalSeen=... ms` da saída. Esperado: o caso passa, com
`MISS=1`, uma evidência, uma consulta e uma chamada externa. Na AWS, o ESM de SQS sobe vários
consumidores, então o `JOIN` deve aparecer entre as decisões, ao contrário do Floci (§13). A partida a
frio do Spring nas Lambdas da provider conta dentro dos 20 s de espera do enriquecimento; um
`TECHNICAL_FAILURE` com `ENRICHMENT_FAILED` no T06 deve ser registrado com os tempos.

#### V09. `TransactionConflict`

A provider repete o conflito até 3 vezes e registra cada repetição; a quote repete até 4 tentativas,
sem log por repetição, e só um conflito esgotado aparece, como 503 `SERVICE_UNAVAILABLE` na API ou como
reentrega no `resume`. Depois do V08, rode o B1 com
`provider-solution-dev-enrichmentWorker '"Transaction conflict"' 30`. Depois, rode o T29
(`-Dit.test='QuoteFlowE2E#t29*'`) e registre as linhas `T29 round=... selections=[...] versions=[...]`.

Esperado: no T06, conflitos absorvidos (linhas `Transaction conflict, retrying (n of 3)`, se houver) e o
caso aprovado; no T29, um lado vence cada rodada, e o outro recebe 409 (`QUOTE_ALREADY_SELECTED`,
`VERSION_SUPERSEDED` ou `VERSION_CONFLICT`). Um 503 no T29 indica conflito esgotado nas 4 tentativas
e reprova o caso; registre-o como achado. Se o limite de repetições bastou para 100 pedidos na mesma
liderança não foi verificado.

#### V10. Vazão do ESM de stream

Depois do `quote-e2e`, meça o atraso da relay nas duas tabelas:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import datetime
import statistics

import boto3

REGION = "us-east-1"
STAGE = "dev"


def instant(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


dynamodb = boto3.client("dynamodb", region_name=REGION)
for table in (f"quote-solution-{STAGE}-quotes", f"provider-solution-{STAGE}-queries"):
    delays = []
    for page in dynamodb.get_paginator("scan").paginate(
            TableName=table, FilterExpression="#type = :outbox AND attribute_exists(publishedAt)",
            ExpressionAttributeNames={"#type": "type"}, ExpressionAttributeValues={":outbox": {"S": "OUTBOX"}},
            ProjectionExpression="createdAt, publishedAt"):
        for item in page["Items"]:
            delays.append((instant(item["publishedAt"]["S"]) - instant(item["createdAt"]["S"])).total_seconds())
    delays.sort()
    if delays:
        print(table, f"n={len(delays)}", f"mediana={statistics.median(delays):.2f}s",
              f"p95={delays[int(len(delays) * 0.95) - 1]:.2f}s", f"máximo={delays[-1]:.2f}s")
EOF
```

Esperado: mediana de poucos segundos, que deve ser registrada. O máximo inclui a sonda do T19, criada
10 minutos no passado, e os itens republicados pelo `reconciler`; por isso a mediana e o p95 são os
números de vazão.

#### V11. Retry e divisão de lote do stream

Primeiro, a configuração dos dois mapeamentos de stream:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import json

import boto3

REGION = "us-east-1"
STAGE = "dev"
lambda_client = boto3.client("lambda", region_name=REGION)
for service in ("quote-solution", "provider-solution"):
    function = f"{service}-{STAGE}-outboxRelay"
    for mapping in lambda_client.list_event_source_mappings(FunctionName=function)["EventSourceMappings"]:
        print(function, mapping["State"], f"BatchSize={mapping['BatchSize']}",
              f"MaximumRetryAttempts={mapping.get('MaximumRetryAttempts')}",
              f"BisectBatchOnFunctionError={mapping.get('BisectBatchOnFunctionError')}",
              f"FunctionResponseTypes={mapping.get('FunctionResponseTypes')}")
        print("   onFailure:", mapping.get("DestinationConfig", {}).get("OnFailure", {}).get("Destination"))
        print("   filtro:", json.dumps(mapping.get("FilterCriteria", {})))
EOF
```

Esperado nos dois: `Enabled`, `BatchSize=100`, `MaximumRetryAttempts=3`, `BisectBatchOnFunctionError=True`,
`FunctionResponseTypes=['ReportBatchItemFailures']`, `onFailure` na `stream-failures` da solução e o
filtro de `INSERT` com `type = OUTBOX`. O retry padrão da AWS (repetir até o registro expirar) não se
aplica a esta stack, que limita as tentativas a 3.

Depois, a indução de falha. Este procedimento é proposto e não foi exercitado em nenhum ambiente.
Rode-o só no laboratório, sem outro teste em andamento. Uma negação temporária de
`dynamodb:UpdateItem` no `OutboxRelayRole` da quote faz a relay publicar e falhar ao marcar o item; a
segunda gravação, a do registro da falha, também é negada, e a exceção encerra a invocação com erro.
Os consumidores descartam as publicações repetidas pelo `eventId`.

```bash
uv run --no-project --with boto3 python - aplicar <<'EOF'
import json
import sys

import boto3

REGION = "us-east-1"
STAGE = "dev"
account = boto3.client("sts", region_name=REGION).get_caller_identity()["Account"]
role = boto3.client("cloudformation", region_name=REGION).describe_stack_resource(
    StackName=f"quote-solution-{STAGE}", LogicalResourceId="OutboxRelayRole",
)["StackResourceDetail"]["PhysicalResourceId"]
iam = boto3.client("iam", region_name=REGION)
policy = "m6-deny-outbox-update"
if sys.argv[1] == "aplicar":
    iam.put_role_policy(RoleName=role, PolicyName=policy, PolicyDocument=json.dumps({
        "Version": "2012-10-17",
        "Statement": [{"Effect": "Deny", "Action": "dynamodb:UpdateItem",
                       "Resource": f"arn:aws:dynamodb:{REGION}:{account}:table/quote-solution-{STAGE}-quotes"}],
    }))
else:
    iam.delete_role_policy(RoleName=role, PolicyName=policy)
print(sys.argv[1], role)
EOF
```

1. Aplique a negação e espere 30 s.
2. Crie três cotações seguidas com o B2, para o stream entregar mais de um registro por lote.
3. Espere dois minutos e leia a `stream-failures` com o script abaixo, sem apagar.
4. Rode o mesmo script de negação com o argumento `remover` no lugar de `aplicar`.
5. Confira, com o B1 em `quote-solution-dev-reconciler '"reconciler report"' 5`, que o ciclo seguinte
   republica os outbox pendentes.
6. Leia a `stream-failures` de novo com o argumento `apagar`, para o alarme voltar a `OK`.

```bash
uv run --no-project --with boto3 python - quote-solution ler <<'EOF'
import json
import sys

import boto3

REGION = "us-east-1"
STAGE = "dev"
service, action = sys.argv[1], sys.argv[2]
sqs = boto3.client("sqs", region_name=REGION)
url = sqs.get_queue_url(QueueName=f"{service}-{STAGE}-stream-failures")["QueueUrl"]
while True:
    messages = sqs.receive_message(QueueUrl=url, MaxNumberOfMessages=10, WaitTimeSeconds=2).get("Messages", [])
    if not messages:
        break
    for message in messages:
        body = json.loads(message["Body"])
        print(json.dumps({"requestContext": body.get("requestContext"),
                          "DDBStreamBatchInfo": body.get("DDBStreamBatchInfo")}, indent=1))
        if action == "apagar":
            sqs.delete_message(QueueUrl=url, ReceiptHandle=message["ReceiptHandle"])
EOF
```

Esperado: um registro de falha por registro do stream, só com shard e faixa de sequência (§13), com
lote de 1 depois da divisão e a condição de tentativas esgotadas; erros da `outboxRelay` no log, com
`AccessDeniedException` em `dynamodb:UpdateItem`; os registros seguintes do shard processados, sem
bloqueio; o alarme `quote-solution-dev-stream-failures-messages` em `ALARM`; e, depois da remoção da
negação, a republicação pelo `reconciler`. Enquanto a negação existe, o papel diverge do template; a
remoção no passo 4 desfaz a divergência.

#### V12. Tempos reais de DLQ

A sonda publica no barramento dois eventos fora do contrato, em nome da provider. O `resume` recusa o
`InsurerResultRecorded` sem `eventId`, e o `issue-projector` recusa o `OperationIssueRaised` sem
`type`; os dois devolvem a mensagem à fila, que vai para a DLQ depois de `maxReceiveCount` recebimentos.
Como os eventos identificam a cotação ou a ocorrência, o `reconciler` reenvia cada um da DLQ até a
terceira chegada e então o descarta como `DEAD_LETTER` (ADR 0007).

```bash
uv run --no-project --with boto3 python - <<'EOF'
import json
import uuid

import boto3

REGION = "us-east-1"
STAGE = "dev"
probe = f"m6-dlq-{uuid.uuid4()}"
bus = f"platform-{STAGE}-events"
result = boto3.client("events", region_name=REGION).put_events(Entries=[
    {"EventBusName": bus, "Source": "mmda.provider", "DetailType": "InsurerResultRecorded",
     "Detail": json.dumps({"quoteId": probe, "version": 1})},
    {"EventBusName": bus, "Source": "mmda.provider", "DetailType": "OperationIssueRaised",
     "Detail": json.dumps({"eventId": probe, "issueId": probe})},
])
print("falhas:", result["FailedEntryCount"], "sonda:", probe)
EOF
```

Acompanhe com o B1, numa janela de 40 minutos: `quote-solution-dev-resume` e
`quote-solution-dev-issueProjector` com `'"failed and will be redelivered"'`, e
`quote-solution-dev-reconciler` com `'"dead letter"'`.

| Etapa | `results` | `issues` |
|---|---|---|
| Recebimentos por passagem | 5, um a cada 90 s mais o polling | 5, um a cada 90 s mais o polling |
| Chegada à DLQ | Cerca de 450 s depois da primeira entrega | Cerca de 450 s depois da primeira entrega |
| Reenvio pelo `reconciler` | No ciclo seguinte, `redriven as attempt 2`, depois `attempt 3` | Igual |
| Descarte | Na terceira chegada, `discarded at attempt 3 as DEAD_LETTER` | Igual |
| Total | Cerca de 25 min | Cerca de 25 min |

Registre os intervalos reais entre recebimentos e a hora de cada chegada à DLQ. A ocorrência
`DEAD_LETTER` fica `OPEN` na tabela da quote, sem organização, e não aparece na listagem da API. Os
alarmes `-results-dlq-messages` e `-issues-dlq-messages` podem disparar, mas não é garantido, porque o
`reconciler` esvazia a DLQ a cada ciclo; o disparo determinístico fica no V15.

#### V13. Visibilidade × timeout

A AWS recusa um mapeamento de SQS cuja visibilidade seja menor que o timeout da função, então um deploy
concluído já é uma prova. O script registra os valores:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import json

import boto3

REGION = "us-east-1"
STAGE = "dev"
PAIRS = [("quote-solution", "results", "resume"), ("quote-solution", "issues", "issueProjector"),
         ("provider-solution", "enrichment-requests", "enrichmentWorker"),
         ("provider-solution", "insurer-requests", "insurerWorker"),
         ("provider-solution", "reconcile-requests", "reconcileWorker"),
         ("provider-solution", "sim-callbacks", "simCallbackSender")]
sqs = boto3.client("sqs", region_name=REGION)
lambda_client = boto3.client("lambda", region_name=REGION)
for service, queue, function in PAIRS:
    url = sqs.get_queue_url(QueueName=f"{service}-{STAGE}-{queue}")["QueueUrl"]
    attributes = sqs.get_queue_attributes(QueueUrl=url, AttributeNames=["VisibilityTimeout", "RedrivePolicy"])
    attributes = attributes["Attributes"]
    timeout = lambda_client.get_function_configuration(FunctionName=f"{service}-{STAGE}-{function}")["Timeout"]
    redrive = json.loads(attributes["RedrivePolicy"])
    print(f"{service} {queue}: função {timeout}s, visibilidade {attributes['VisibilityTimeout']}s, "
          f"maxReceiveCount {redrive['maxReceiveCount']}")
EOF
```

Esperado, como no §12: `results` e `issues` com 15, 90 e 5; `enrichment-requests`, `insurer-requests`
e `reconcile-requests` com 25, 150 e 3; `sim-callbacks` com 15, 90 e 5.

#### V14. Issuer do Cognito

```bash
uv run --no-project --with boto3 python - <<'EOF'
import base64
import json
import os
import urllib.error
import urllib.request

import boto3

REGION = "us-east-1"
STAGE = "dev"
stack = boto3.client("cloudformation", region_name=REGION).describe_stacks(StackName=f"quote-solution-{STAGE}")
outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Stacks"][0]["Outputs"]}
tokens = boto3.client("cognito-idp", region_name=REGION).initiate_auth(
    ClientId=outputs["QuoteUserPoolClientId"], AuthFlow="USER_PASSWORD_AUTH",
    AuthParameters={"USERNAME": os.environ["M6_USERNAME"], "PASSWORD": os.environ["M6_PASSWORD"]},
)["AuthenticationResult"]
apis = boto3.client("apigateway", region_name=REGION).get_rest_apis(limit=500)["items"]
api_id = next(api["id"] for api in apis if api["name"] == f"{STAGE}-quote-solution")
me = f"https://{api_id}.execute-api.{REGION}.amazonaws.com/{STAGE}/v1/me"
issuer = boto3.client("lambda", region_name=REGION).get_function_configuration(
    FunctionName=f"quote-solution-{STAGE}-api")["Environment"]["Variables"]["JWT_ISSUER"]


def claims(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def get(token):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(me, headers=headers)) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


access = claims(tokens["AccessToken"])
print("iss:", access["iss"], "| JWT_ISSUER:", issuer, "| iguais:", access["iss"] == issuer)
print("access token:", get(tokens["AccessToken"]))
print("ID token:", get(tokens["IdToken"]))
print("sem token:", get(None))
EOF
```

Esperado: `iguais: True`, com `https://cognito-idp.us-east-1.amazonaws.com/<poolId>`; 200 com a
organização e as permissões do usuário; 401 com o ID token, que não tem `client_id`; 401 sem token. A
`api` busca o JWKS por HTTPS, sem IAM.

#### V15. Alarmes: `AlarmActions`, disparo e recuperação

Conferência dos alarmes e das assinaturas:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
STAGE = "dev"
account = boto3.client("sts", region_name=REGION).get_caller_identity()["Account"]
cloudwatch = boto3.client("cloudwatch", region_name=REGION)
sns = boto3.client("sns", region_name=REGION)
for service, expected in (("quote-solution", 8), ("provider-solution", 16)):
    alarms = []
    for page in cloudwatch.get_paginator("describe_alarms").paginate(AlarmNamePrefix=f"{service}-{STAGE}-"):
        alarms.extend(page["MetricAlarms"])
    topic = f"arn:aws:sns:{REGION}:{account}:{service}-{STAGE}-alarms"
    without_topic = [alarm["AlarmName"] for alarm in alarms if alarm["AlarmActions"] != [topic]]
    print(service, f"{len(alarms)} alarmes (esperado {expected})", "sem o tópico:", without_topic)
    for alarm in alarms:
        print("   ", alarm["AlarmName"], alarm["StateValue"])
    for subscription in sns.list_subscriptions_by_topic(TopicArn=topic)["Subscriptions"]:
        print("    assinatura:", subscription["Protocol"], subscription["SubscriptionArn"])
EOF
```

Esperado: 8 e 16 alarmes, nenhum sem o tópico, e a assinatura `email` com um ARN, não
`PendingConfirmation`.

Disparo das ações, um alarme por vez, começando pelos da `issues-dlq`:

```bash
uv run --no-project --with boto3 python - quote-solution-dev-issues-dlq-messages <<'EOF'
import sys

import boto3

REGION = "us-east-1"
alarm = sys.argv[1]
cloudwatch = boto3.client("cloudwatch", region_name=REGION)
cloudwatch.set_alarm_state(AlarmName=alarm, StateValue="ALARM", StateReason="Teste M6 das AlarmActions")
for item in cloudwatch.describe_alarm_history(AlarmName=alarm, HistoryItemType="StateUpdate",
                                              MaxRecords=5)["AlarmHistoryItems"]:
    print(item["Timestamp"], item["HistorySummary"])
EOF
```

Esperado: um e-mail `ALARM` para o endereço assinado e, na avaliação seguinte, a volta a `OK`, visível no
histórico ao rodar o script de novo. Repita com `quote-solution-dev-issues-dlq-age` e com um alarme da
provider.

Disparo pela métrica real, com recuperação. A `stream-failures` não tem consumidor, então uma mensagem
posta nela fica visível:

```bash
uv run --no-project --with boto3 python - quote-solution <<'EOF'
import json
import sys

import boto3

REGION = "us-east-1"
STAGE = "dev"
sqs = boto3.client("sqs", region_name=REGION)
url = sqs.get_queue_url(QueueName=f"{sys.argv[1]}-{STAGE}-stream-failures")["QueueUrl"]
print(sqs.send_message(QueueUrl=url, MessageBody=json.dumps({"m6": "sonda de alarme"}))["MessageId"])
EOF
```

Esperado: `quote-solution-dev-stream-failures-messages` em `ALARM` em poucos minutos e
`quote-solution-dev-stream-failures-age` depois de 300 s de idade, cada um com e-mail. Para a
recuperação, apague a sonda com o script de leitura do V11 (argumentos `quote-solution apagar`); os
dois voltam a `OK`. Com o argumento `provider-solution`, o mesmo vale para
`provider-solution-dev-stream-failures-volume` e `-age`.

Disparo de um alarme de métrica EMF com metric math: o T10 (`-Dit.test='QuoteFlowE2E#t10*'`) deixa a
alpha indisponível, com 3 chamadas e 3 falhas. Esperado:
`provider-solution-dev-alpha-provider-failures` em `ALARM` (100% ≥ 50%) e de volta a `OK` quando a
janela de 300 s fica sem chamadas. Rode o T10 sozinho, porque chamadas bem-sucedidas de outros casos
na mesma janela baixam o percentual. Os alarmes `expired-tasks`, `unmatched-callbacks` e
`unknown-queries` não têm indução neste roteiro; as ações deles ficam cobertas pelo `set_alarm_state`.

#### V16. Extração das métricas EMF

```bash
uv run --no-project --with boto3 python - <<'EOF'
import datetime

import boto3

REGION = "us-east-1"
STAGE = "dev"
cloudwatch = boto3.client("cloudwatch", region_name=REGION)
for namespace in ("mmda/quote-solution", "mmda/provider-solution"):
    for page in cloudwatch.get_paginator("list_metrics").paginate(Namespace=namespace):
        for metric in page["Metrics"]:
            print(namespace, metric["MetricName"], {d["Name"]: d["Value"] for d in metric["Dimensions"]})
now = datetime.datetime.now(datetime.timezone.utc)
points = cloudwatch.get_metric_statistics(
    Namespace="mmda/quote-solution", MetricName="OutboxPendingAgeSeconds",
    Dimensions=[{"Name": "Stage", "Value": STAGE}], StartTime=now - datetime.timedelta(minutes=15),
    EndTime=now, Period=60, Statistics=["Maximum", "SampleCount"])["Datapoints"]
print("pontos da quote nos últimos 15 min:", len(points))
EOF
```

Esperado: na quote, `OutboxPendingAgeSeconds` e `ExpiredTasks` com `Stage=dev`; na provider,
`UnmatchedCallbacks`, `UnknownQueries` e `OutboxPendingAgeSeconds` com `Stage=dev` e, depois do
`quote-e2e`, `ProviderCalls` e `ProviderFailures` com `Stage=dev` e `Provider` (`enrichment-sim`,
`alpha` e `beta`); cerca de um ponto por minuto. Métricas novas levam alguns minutos para aparecer. Se
o log do `reconciler` tem as linhas JSON com `_aws` e as métricas não aparecem, a extração falhou, e
os alarmes de EMF ficariam em `OK` por falta de dados (`TreatMissingData: notBreaching`).

#### V17. `StopExecution`

Rode o T20 (`-Dit.test='QuoteFlowE2E#t20*'`) e registre as linhas
`T20 attempt=... dispatch=cycle+... stop=cycle+... beta=...`. O caso foi escrito para a continuação
do Floci, que segue executando estados depois do `StopExecution` (§13). Esperado na AWS: a execução
termina `ABORTED`, com `ExecutionAborted` como último evento no script do V03; a primeira tentativa é
finalizada pelo `reconciler`, com a versão `PARTIAL`, a beta `TIMED_OUT` com `WORKFLOW_ABORTED`, as
ofertas mantidas, a ocorrência `WORKFLOW_FAILED` aberta e o `QuoteCompleted` no outbox. O alinhamento
de 51 s ao ciclo do `reconciler` não foi validado com o Scheduler real; uma falha "consolidated before
the reconciler" tem a causa conhecida descrita nos [limites conhecidos dos testes](testes.md#limites-conhecidos).

#### V18. 429 do API Gateway

O contrato declara o 429 com `Retry-After` em todas as rotas, e a API não o emite no MVP (§11). Na AWS,
ele vem do throttling do API Gateway. O script baixa o limite do stage para 1 requisição por segundo,
dispara 40 chamadas a `/v1/me` e devolve o stage aos limites padrão da conta, 10.000 e 5.000, mesmo se
algo falhar. Rode-o sem outro teste em andamento.

```bash
uv run --no-project --with boto3 python - <<'EOF'
import collections
import concurrent.futures
import os
import time
import urllib.error
import urllib.request

import boto3

REGION = "us-east-1"
STAGE = "dev"
stack = boto3.client("cloudformation", region_name=REGION).describe_stacks(StackName=f"quote-solution-{STAGE}")
outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Stacks"][0]["Outputs"]}
token = boto3.client("cognito-idp", region_name=REGION).initiate_auth(
    ClientId=outputs["QuoteUserPoolClientId"], AuthFlow="USER_PASSWORD_AUTH",
    AuthParameters={"USERNAME": os.environ["M6_USERNAME"], "PASSWORD": os.environ["M6_PASSWORD"]},
)["AuthenticationResult"]["AccessToken"]
apigateway = boto3.client("apigateway", region_name=REGION)
api_id = next(api["id"] for api in apigateway.get_rest_apis(limit=500)["items"]
              if api["name"] == f"{STAGE}-quote-solution")
me = f"https://{api_id}.execute-api.{REGION}.amazonaws.com/{STAGE}/v1/me"


def throttle(rate, burst):
    apigateway.update_stage(restApiId=api_id, stageName=STAGE, patchOperations=[
        {"op": "replace", "path": "/*/*/throttling/rateLimit", "value": str(rate)},
        {"op": "replace", "path": "/*/*/throttling/burstLimit", "value": str(burst)}])


def call(_):
    request = urllib.request.Request(me, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, dict(response.headers), response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode()


throttle(1, 1)
try:
    time.sleep(30)
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(call, range(40)))
finally:
    throttle(10000, 5000)
print(collections.Counter(status for status, _, _ in results))
throttled = next((result for result in results if result[0] == 429), None)
if throttled:
    print("Retry-After:", throttled[1].get("Retry-After"), "| corpo:", throttled[2])
print(apigateway.get_stage(restApiId=api_id, stageName=STAGE).get("methodSettings"))
EOF
```

Esperado: parte das respostas em 429, sem invocar a `api`. O repositório não declara `GatewayResponse`,
então o 429 é a resposta padrão do API Gateway. Registre se ele atende ao `TooManyRequests` do
`contracts/openapi.yaml` (cabeçalho `Retry-After` inteiro ≥ 1 e corpo `Error`); a divergência, se houver,
é uma decisão pendente. A última linha deve mostrar `*/*` com 10.000 e 5.000.

#### V19. Grupos reais do Cognito

```bash
mvn -B verify -Pe2e,e2e-aws -pl quote-solution/e2e -Dit.test='QuoteFlowE2E#t18*+t29*+t30*'
```

Esperado: os três casos passam com os grupos do user pool da AWS. O T18 recusa a oferta expirada e a
oferta de outra organização sem gravar intenção, com 404 para o operador da Globex; o T29 tem uma
vencedora por rodada (V09); o T30 responde 403 na seleção, na reconciliação e na listagem de
ocorrências para quem não tem a permissão.

#### V20. `ISSUE#` no formato anterior ao ADR 0015

Antes do ADR 0015, o tipo da ocorrência ficava em `type`, sem `issueType` (§4). O script procura esse
formato:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
STAGE = "dev"
dynamodb = boto3.client("dynamodb", region_name=REGION)
legacy = []
for page in dynamodb.get_paginator("scan").paginate(
        TableName=f"quote-solution-{STAGE}-quotes",
        FilterExpression="begins_with(pk, :issue) AND (attribute_not_exists(issueType) OR #type <> :issue_type)",
        ExpressionAttributeNames={"#type": "type"},
        ExpressionAttributeValues={":issue": {"S": "ISSUE#"}, ":issue_type": {"S": "ISSUE"}},
        ProjectionExpression="pk, #type"):
    legacy.extend(page["Items"])
print(len(legacy), "item(ns) no formato antigo")
for item in legacy[:10]:
    print(item)
EOF
```

Esperado: `0 item(ns) no formato antigo`. Num stage criado depois do ADR 0015, como este `dev`, o
resultado é zero por construção; a conferência importa em qualquer stage da AWS que já existia antes.

### 4.4 Registro dos resultados

| ID | Data | Resultado | Evidência | Pendência |
|---|---|---|---|---|
| V01 | | | | |
| V02 | | | | |
| V03 | | | | |
| V04 | | | | |
| V05 | | | | |
| V06 | | | | |
| V07 | | | | |
| V08 | | | | |
| V09 | | | | |
| V10 | | | | |
| V11 | | | | |
| V12 | | | | |
| V13 | | | | |
| V14 | | | | |
| V15 | | | | |
| V16 | | | | |
| V17 | | | | |
| V18 | | | | |
| V19 | | | | |
| V20 | | | | |

## 5. `quote-e2e` contra o stage `dev`

O módulo está preparado. O perfil `floci`, ativo por padrão, aponta o SDK para o Floci com as
credenciais `test`. O perfil `e2e-aws` desliga o `floci`, define `e2e.stage=dev` e não declara
endpoint nem credenciais, então o SDK usa a cadeia padrão (`AWS_PROFILE` ou SSO). Sem endpoint, a URL
das APIs vira `https://<apiId>.execute-api.<região>.amazonaws.com/<stage>`, e o S3 deixa o estilo de
caminho.

```bash
export AWS_PROFILE=<perfil-da-conta-dev>
unset AWS_ENDPOINT_URL AWS_ENDPOINT_URL_S3 AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
mvn -B verify -Pe2e,e2e-aws -pl quote-solution/e2e
```

| Propriedade | Padrão | Uso |
|---|---|---|
| `e2e.stage` | `dev` no perfil `e2e-aws` | Prefixo dos nomes das stacks, tabelas, filas, segredos e grupos de log |
| `e2e.region` | `us-east-1` | Vai para `aws.region` |
| `e2e.concurrentQuotes` | 12 | Cotações do T06; 100 no V08 |
| `it.test` | todos | Um caso ou um grupo, como `QuoteFlowE2E#t06*` |

Pré-condições: as três stacks implantadas e a credencial do e2e com as permissões abaixo. O próprio
`quote-e2e` cria os usuários do Cognito.

| Serviço | Ações | Recursos |
|---|---|---|
| DynamoDB | `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `Scan` | `quote-solution-dev-quotes` (e o índice `gsi1`), `provider-solution-dev-queries` |
| SQS | `GetQueueUrl`, `GetQueueAttributes`, `SendMessage` | Filas e DLQs `quote-solution-dev-*` e `provider-solution-dev-*` |
| EventBridge | `PutEvents` | `platform-dev-events` |
| CloudWatch Logs | `FilterLogEvents` | `/aws/lambda/quote-solution-dev-*` e `/aws/lambda/provider-solution-dev-*` |
| Step Functions | `ListStateMachines`, `ListExecutions`, `DescribeExecution`, `StopExecution` | `quote-solution-dev-quote-flow` e as execuções dela |
| Secrets Manager | `GetSecretValue` | `provider-solution/dev/query-key-secret`, `provider-solution/dev/callback-secret` |
| S3 | `GetObject` (o `HeadObject`), `ListBucket` | `provider-solution-dev-payloads` |
| API Gateway | `GET` | `/restapis` |
| Cognito | `ListUserPools`, `ListUserPoolClients`, `ListGroups`, `CreateGroup`, `AdminGetUser`, `AdminCreateUser`, `AdminSetUserPassword`, `AdminListGroupsForUser`, `AdminRemoveUserFromGroup`, `AdminAddUserToGroup` | `quote-solution-dev-users` |

O que muda em relação ao Floci:

- As esperas seguem os params do stage: 20 + ceil(n / 2) × 90 + 30 = 140 s para uma ou duas
  seguradoras, 50 s sem seguradora e 150 s no T06 com 100 cotações. A suíte fica bem mais longa que no
  Floci.
- O `reconciler` dispara a cada 60 s; o orçamento do e2e para esperar o `reconciler` (3 min) cobre
  dois ciclos e a publicação.
- O T20 conta com o alinhamento de 51 s ao ciclo do `reconciler`, calibrado no Floci (V17).
- O T31 põe as mensagens direto nas DLQs; a passagem real pela `RedrivePolicy` fica no V12.
- A senha dos 4 usuários do e2e está no código. Enquanto a stack existir, a API pública aceita login
  com eles; apague-os depois das rodadas se a stack ficar no ar (seção 7).

As linhas de log dos casos (`T06`, `T20`, `T29`) saem na saída do Maven e nos relatórios em
`quote-solution/e2e/target/failsafe-reports/`.

A provider não tem módulo e2e: os ITs dela sobem um Floci próprio por Testcontainers e não foram feitos
para apontar para a AWS. O fluxo dela na AWS é coberto pelo `quote-e2e`.

## 6. Custos

### 6.1 Antes do deploy

O CloudFormation marca os recursos que suportam tags com `aws:cloudformation:stack-name`. Ative essa
tag em Billing and Cost Management, na página **Cost allocation tags**, antes do primeiro deploy: a
chave pode levar até 24 h para aparecer na página e mais até 24 h para valer no Cost Explorer. Numa
organização, só a conta de gerenciamento ativa tags. Os `serverless.yml` não declaram tags próprias,
então essa é a única tag por stack.

### 6.2 O que gera custo

| Serviço | O que gera custo neste laboratório | Custo com as stacks ociosas |
|---|---|---|
| AWS Lambda | Invocações e GB-s das 15 funções, todas com 1.769 MB | Sim: os dois `reconciler` rodam a cada minuto |
| Amazon EventBridge Scheduler | Disparos dos dois agendamentos | Sim: um por minuto em cada |
| Amazon SQS | Mensagens e as chamadas `ReceiveMessage` dos 6 mapeamentos de evento | Sim: os mapeamentos consultam as filas sem parar |
| AWS Step Functions | Transições de estado das execuções padrão | Não |
| Amazon DynamoDB | Requisições sob demanda e armazenamento das 3 tabelas | Sim: as consultas dos `reconciler` a cada minuto e o armazenamento |
| Amazon API Gateway | Requisições às duas APIs REST | Não |
| Amazon EventBridge | Eventos publicados no barramento `platform-dev-events` | Não |
| AWS Secrets Manager | 3 segredos por mês e as leituras | Sim |
| Amazon CloudWatch | 24 alarmes (os de metric math contam por métrica), métricas EMF, ingestão e armazenamento de logs sem retenção definida | Sim: alarmes, métricas dos `reconciler` e logs guardados |
| Amazon S3 | Bucket `provider-solution-dev-payloads` e o bucket de deploy do Serverless, versionado | Só armazenamento |
| Amazon SNS | Notificações por e-mail | Não |
| Amazon Cognito | Usuários ativos no mês | Não |

### 6.3 Onde ver

- No Cost Explorer, filtre a conta e agrupe por **Service**, com granularidade diária, para o custo
  total do laboratório.
- Com a tag ativa, agrupe por **Tag** `aws:cloudformation:stack-name` para separar `platform-dev`,
  `provider-solution-dev` e `quote-solution-dev`. O que fica fora das stacks (bucket de deploy do
  Serverless e as próprias consultas ao Cost Explorer) aparece sem tag.
- Pela API, cada requisição ao Cost Explorer é cobrada; o script abaixo faz uma por agrupamento:

```bash
uv run --no-project --with boto3 python - 2026-11-01 2026-11-30 <<'EOF'
import sys

import boto3

start, end = sys.argv[1], sys.argv[2]
cost_explorer = boto3.client("ce", region_name="us-east-1")
for group_by in ({"Type": "DIMENSION", "Key": "SERVICE"}, {"Type": "TAG", "Key": "aws:cloudformation:stack-name"}):
    print("==", group_by["Key"])
    response = cost_explorer.get_cost_and_usage(
        TimePeriod={"Start": start, "End": end}, Granularity="DAILY", Metrics=["UnblendedCost"],
        GroupBy=[group_by])
    for day in response["ResultsByTime"]:
        for group in day["Groups"]:
            amount = float(group["Metrics"]["UnblendedCost"]["Amount"])
            if amount > 0:
                print(day["TimePeriod"]["Start"], group["Keys"][0], f"{amount:.4f}",
                      group["Metrics"]["UnblendedCost"]["Unit"])
EOF
```

As datas são o início e o fim (exclusivo) do período.

### 6.4 Registro

Anote as janelas de cada atividade, para casar com os custos diários:

| Data | Atividade | Início e fim (UTC) | Custo do dia por serviço | Observação |
|---|---|---|---|---|
| | Deploy das três stacks | | | |
| | Stacks ociosas por 24 h | | | |
| | `quote-e2e` completo | | | |
| | T06 com 100 cotações | | | |
| | Sondas V11, V12, V15 e V18 | | | |
| | Remoção | | | |

## 7. Limpeza do laboratório

### 7.1 Retenção dos dados

Nenhum recurso declara `DeletionPolicy` ou `UpdateReplacePolicy`. O que acontece no `sls remove`:

| Recurso | No `sls remove` |
|---|---|
| Tabelas `quote-solution-dev-quotes`, `provider-solution-dev-queries` e `-simulators` | Apagadas com os dados |
| Bucket `provider-solution-dev-payloads` | A remoção falha se houver objetos; esvazie antes |
| Filas e DLQs (retenção de 14 dias nas DLQs) | Apagadas com as mensagens |
| Segredos | Removidos com a stack; confira se ficaram agendados para exclusão |
| Grupos de log das funções | Criados pelo Serverless na stack e removidos com ela |
| User pool | Removido com usuários e grupos |
| Bucket de deploy do Serverless e o parâmetro SSM | Ficam: são compartilhados por todos os serviços da região |
| Backups sob demanda do DynamoDB | Ficam até serem apagados, com custo de armazenamento |

Para guardar os dados das tabelas depois da remoção, crie um backup sob demanda antes:

```bash
uv run --no-project --with boto3 python - <<'EOF'
import datetime

import boto3

REGION = "us-east-1"
STAGE = "dev"
dynamodb = boto3.client("dynamodb", region_name=REGION)
suffix = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M")
for table in (f"quote-solution-{STAGE}-quotes", f"provider-solution-{STAGE}-queries"):
    backup = dynamodb.create_backup(TableName=table, BackupName=f"{table}-m6-{suffix}")
    print(backup["BackupDetails"]["BackupArn"])
EOF
```

### 7.2 Usuários

Com a remoção da stack, o user pool sai com todos os usuários. Se a stack ficar no ar, apague os
usuários do e2e, cuja senha está no código, e o usuário manual:

```bash
uv run --no-project --with boto3 python - e2e-acme-writer e2e-acme-operator e2e-acme-viewer \
  e2e-globex-operator "$M6_USERNAME" <<'EOF'
import sys

import boto3

REGION = "us-east-1"
STAGE = "dev"
stack = boto3.client("cloudformation", region_name=REGION).describe_stacks(StackName=f"quote-solution-{STAGE}")
outputs = {output["OutputKey"]: output["OutputValue"] for output in stack["Stacks"][0]["Outputs"]}
cognito = boto3.client("cognito-idp", region_name=REGION)
for username in sys.argv[1:]:
    try:
        cognito.admin_delete_user(UserPoolId=outputs["QuoteUserPoolId"], Username=username)
        print("apagado:", username)
    except cognito.exceptions.UserNotFoundException:
        print("não existe:", username)
EOF
```

### 7.3 Remover as stacks

Na ordem inversa do deploy. O bucket de respostas precisa estar vazio antes da remoção da provider:

```bash
(cd quote-solution && pnpm sls remove --stage dev)
uv run --no-project --with boto3 python - <<'EOF'
import boto3

REGION = "us-east-1"
boto3.resource("s3", region_name=REGION).Bucket("provider-solution-dev-payloads").objects.all().delete()
print("bucket vazio")
EOF
(cd provider-solution && pnpm sls remove --stage dev)
(cd platform/events && pnpm sls remove --stage dev)
```

### 7.4 Conferir as sobras

```bash
uv run --no-project --with boto3 python - <<'EOF'
import boto3
import botocore.exceptions

REGION = "us-east-1"
STAGE = "dev"
cloudformation = boto3.client("cloudformation", region_name=REGION)
for stack_name in (f"platform-{STAGE}", f"provider-solution-{STAGE}", f"quote-solution-{STAGE}"):
    try:
        print(stack_name, cloudformation.describe_stacks(StackName=stack_name)["Stacks"][0]["StackStatus"])
    except botocore.exceptions.ClientError:
        print(stack_name, "removida")
secrets = boto3.client("secretsmanager", region_name=REGION)
for page in secrets.get_paginator("list_secrets").paginate(
        IncludePlannedDeletion=True,
        Filters=[{"Key": "name", "Values": [f"quote-solution/{STAGE}/", f"provider-solution/{STAGE}/"]}]):
    for secret in page["SecretList"]:
        print("segredo:", secret["Name"], secret.get("DeletedDate", "ativo"))
logs = boto3.client("logs", region_name=REGION)
for prefix in (f"/aws/lambda/quote-solution-{STAGE}-", f"/aws/lambda/provider-solution-{STAGE}-"):
    for page in logs.get_paginator("describe_log_groups").paginate(logGroupNamePrefix=prefix):
        for group in page["logGroups"]:
            print("grupo de log:", group["logGroupName"])
dynamodb = boto3.client("dynamodb", region_name=REGION)
for backup in dynamodb.list_backups(BackupType="USER")["BackupSummaries"]:
    print("backup:", backup["BackupName"], backup["BackupStatus"])
EOF
```

Esperado: as três stacks removidas e nenhum grupo de log. Um segredo agendado para exclusão impede um
novo deploy com o mesmo nome até sair; para reimplantar logo, apague-o com `delete_secret` e
`ForceDeleteWithoutRecovery=True`. Apague os backups quando não forem mais necessários
(`delete_backup`). O bucket de deploy do Serverless e o parâmetro
`/serverless-framework/deployment/s3-bucket` só devem sair se nenhum outro serviço da conta usar o
Serverless na região. As métricas EMF não se apagam; elas param de gerar custo quando deixam de
receber dados. A tag de custo pode ficar ativa.

## 8. Lacunas conhecidas

- Não há scripts `deploy:dev` nem `package:dev` no `package.json`; este guia usa `pnpm sls` direto.
- A região está fixa em `us-east-1` nas três stacks.
- O nome do bucket de respostas não leva a conta e pode colidir no namespace global do S3 (2.7).
- Nenhum recurso tem `DeletionPolicy` nem tags próprias; a retenção depende de backup manual (7.1) e o
  custo por stack, da tag gerada pelo CloudFormation (6.1).
- Os alarmes não têm `OKActions` nem `InsufficientDataActions`, e nenhum alarme cobre execução falha ou
  expirada da máquina.
- O 429 do API Gateway não tem `GatewayResponse` própria e pode não seguir o `TooManyRequests` do
  contrato (V18).
- As induções do V11 e do V12 e a sonda do V04 não foram exercitadas; o V11 altera um papel fora do
  template até o passo de remoção.
- Sobras de privilégio aceitas na provider: o papel compartilhado lê o `query-key-secret` e mexe nas
  DLQs para todas as funções; o Serverless acrescenta a ele a leitura da `sim-callbacks` e a confiança
  no Scheduler sem `aws:SourceAccount`.
- Não foram verificados: a cota de concorrência da conta, se 4 tentativas bastam para o
  `TransactionConflict` com 100 pedidos na mesma liderança, o alinhamento do T20 ao Scheduler real e a
  partida a frio do Spring dentro da espera de 20 s do enriquecimento.
