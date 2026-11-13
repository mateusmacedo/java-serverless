#!/usr/bin/env node
// Gera a configuração do painel a partir da quote-solution implantada: `runtime/environment.json`, lido pelo proxy
// do dev server, e `public/config.json`, servido ao navegador. Uso: `pnpm configure [--stage dev] [--profile p]`.
import { mkdirSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';

import { createAws, fail, FLOCI_ENDPOINT, quoteNames } from './aws.mjs';

const { values } = parseArgs({
  options: {
    stage: { type: 'string', default: 'local' },
    region: { type: 'string', default: process.env.AWS_REGION ?? 'us-east-1' },
    profile: { type: 'string' },
    'poll-interval': { type: 'string', default: '3' },
    'request-timeout': { type: 'string', default: '30' },
  },
});

const { stage, region, profile } = values;
const local = stage === 'local';
const names = quoteNames(stage);
const aws = createAws({ stage, region, profile });

let pool;
let client;
let api;
try {
  pool = aws
    .call(['cognito-idp', 'list-user-pools', '--max-results', '60'])
    .UserPools?.find((item) => item.Name === names.userPool);
  if (!pool) {
    fail(
      `User pool ${names.userPool} não encontrado. Implante a quote-solution no stage ${stage} antes.`,
    );
  }
  client = aws
    .call([
      'cognito-idp',
      'list-user-pool-clients',
      '--user-pool-id',
      pool.Id,
      '--max-results',
      '60',
    ])
    .UserPoolClients?.find((item) => item.ClientName === names.userPoolClient);
  if (!client) {
    fail(`App client ${names.userPoolClient} não encontrado no pool ${pool.Id}.`);
  }
  api = aws
    .call(['apigateway', 'get-rest-apis', '--limit', '500'])
    .items?.find((item) => item.name === names.restApi);
  if (!api) {
    fail(`API ${names.restApi} não encontrada. Implante a quote-solution no stage ${stage} antes.`);
  }
} catch (error) {
  const hint = local
    ? 'O Floci está no ar (docker compose up -d) e o AWS CLI v2 está instalado?'
    : 'As credenciais da conta estão configuradas? Use --profile <perfil> ou as variáveis AWS_*.';
  fail(`${error.message}\n${hint}`);
}

const environment = {
  stage,
  region,
  api: local
    ? { origin: FLOCI_ENDPOINT, basePath: `/restapis/${api.id}/${stage}/_user_request_` }
    : { origin: `https://${api.id}.execute-api.${region}.amazonaws.com`, basePath: `/${stage}` },
  cognito: { origin: local ? FLOCI_ENDPOINT : `https://cognito-idp.${region}.amazonaws.com` },
};

const config = {
  stage,
  cognito: { clientId: client.ClientId, endpoint: '/cognito' },
  pollIntervalSeconds: Number(values['poll-interval']),
  requestTimeoutSeconds: Number(values['request-timeout']),
};

const root = new URL('..', import.meta.url);
mkdirSync(new URL('runtime/', root), { recursive: true });
writeFileSync(
  new URL('runtime/environment.json', root),
  `${JSON.stringify(environment, null, 2)}\n`,
);
writeFileSync(new URL('public/config.json', root), `${JSON.stringify(config, null, 2)}\n`);

console.log(`✔ Painel configurado para o stage ${stage}`);
console.log(`  API:     ${environment.api.origin}${environment.api.basePath}/v1`);
console.log(`  Cognito: ${environment.cognito.origin} (app client ${client.ClientId})`);
console.log('  Rode `pnpm start` e abra http://localhost:4200');
