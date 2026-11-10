# Documentação

Documentação do ambiente e da solução de referência `sample-solution`. Para subir o ambiente e
executar os comandos do dia a dia, comece pelo [README da raiz](../README.md).

| Documento | Para que serve |
|---|---|
| [Guia do código](code-walkthrough.md) | Como cada Lambda sobe, como os componentes são registrados e por onde a requisição passa |
| [Arquitetura](architecture.md) | Contexto, containers, módulos, Lambdas, recursos AWS, configuração e ambiente local |
| [Fluxos](flows.md) | Máquina de estado e sequência dos caminhos de sucesso, falha e reprocessamento |
| [API HTTP (OpenAPI 3.1)](api/openapi.yaml) | Contrato do `POST /requests` |
| [Eventos e mensagens (AsyncAPI 3.1)](events/asyncapi.yaml) | Estado entre os passos, SNS, EventBridge, filas `audit` e `dlq` e invocação do reprocessamento |
| [Testes](testing.md) | Níveis de teste, o que cada um cobre e como rodar |
| [Decisões (ADRs)](#decisões) | Por que o ambiente é como é |

## Decisões

| ADR | Decisão |
|---|---|
| [0001](decisions/0001-serverless-framework-e-floci.md) | Serverless Framework v4 com Floci como emulador local |
| [0002](decisions/0002-spring-mvc-e-spring-cloud-function.md) | Spring MVC na API e Spring Cloud Function nos passos |
| [0003](decisions/0003-spring-boot-3-5.md) | Spring Boot 3.5 pela adoção de mercado |
| [0004](decisions/0004-sem-graalvm-native.md) | Sem imagem nativa GraalVM |
| [0005](decisions/0005-clients-sincronos-com-crt.md) | Clients síncronos do AWS SDK com CRT e SDKs opcionais |
| [0006](decisions/0006-payload-no-s3.md) | Payload no S3, com só a chave na máquina de estado |
| [0007](decisions/0007-dlq-e-reprocessamento.md) | Falhas para a DLQ e reprocessamento com limite de tentativas |
| [0008](decisions/0008-aws-endpoint-url-s3-no-offline.md) | `AWS_ENDPOINT_URL_S3` explícito para o serverless-offline |

Um novo ADR recebe o próximo número, com as seções Status, Data, Contexto, Decisão, Consequências e
Alternativas consideradas. Uma decisão substituída continua no histórico, com o status apontando
para o ADR que a substitui.

## Convenções

- Documentação em PT-BR, versionada junto do código; nomes de classes, propriedades e recursos ficam
  como estão no código.
- Diagramas em [Mermaid](https://mermaid.js.org/), dentro dos próprios arquivos Markdown.
- Fatos sobre o sistema vêm do código e do `sample-solution/serverless.yml`. Exemplos de mensagens
  foram capturados no Floci; quando não foi possível observar, o documento diz isso.
- Ao mudar o fluxo, a API ou os eventos, atualize no mesmo commit o diagrama e o contrato afetados.

## Como validar

Execute na raiz do projeto. As ferramentas são baixadas sob demanda pelo `pnpm dlx`, sem entrar no
`package.json`.

```bash
# OpenAPI
pnpm --package=@redocly/cli@2.77.0 dlx redocly lint docs/api/openapi.yaml

# AsyncAPI: ignore-scripts evita o script de instalação da CLI, que altera ~/.zshrc e ~/.bashrc
pnpm --config.ignore-scripts=true --package=@asyncapi/cli@6.3.0 dlx asyncapi validate docs/events/asyncapi.yaml

# Diagramas: renderiza cada bloco Mermaid em SVG e falha em erro de sintaxe
echo '{"executablePath":"/usr/bin/google-chrome","args":["--no-sandbox"]}' > /tmp/puppeteer.json
for doc in architecture flows; do
  PUPPETEER_SKIP_DOWNLOAD=true pnpm --package=@mermaid-js/mermaid-cli@12.2.0 dlx mmdc \
    -p /tmp/puppeteer.json -i "docs/$doc.md" -o "/tmp/$doc.md"
done
```

Resultado esperado:

- **Redocly:** documento válido, com 3 avisos conhecidos: não há licença declarada no projeto
  (`info-license`) e os servidores apontam para `localhost` (`no-server-example.com`), que é o ambiente
  local de propósito.
- **AsyncAPI:** documento válido, sem apontamentos de governança. A CLI envia métricas anônimas de uso
  enquanto `~/.asyncapi-analytics` estiver com `analyticsEnabled` ligado.
- **Mermaid:** um SVG por diagrama (3 em `architecture.md` e 4 em `flows.md`). O caminho do Chrome no
  `puppeteer.json` é o desta máquina; ajuste se o seu for outro.
