#!/usr/bin/env node
// Cria ou ajusta, no Cognito do Floci, os usuários de exemplo do painel na org-acme: `corretor` (cotação e seleção)
// e `operador` (consulta e reconciliação). A senha vem de BACKOFFICE_PASSWORD e segue a política do pool.
// Uso: `BACKOFFICE_PASSWORD='...' pnpm users:local`. Só no stage local: na AWS, siga docs/implantacao-aws-dev.md.
import { createAws, fail, quoteNames } from './aws.mjs';

const STAGE = 'local';
const REGION = process.env.AWS_REGION ?? 'us-east-1';

const USERS = [
  {
    username: 'corretor',
    groups: ['org_org-acme', 'perm_quotes_read', 'perm_quotes_write', 'perm_offers_select'],
  },
  {
    username: 'operador',
    groups: [
      'org_org-acme',
      'perm_quotes_read',
      'perm_operations_read',
      'perm_operations_reconcile',
    ],
  },
];

const password = process.env.BACKOFFICE_PASSWORD ?? '';
const policy = [
  [password.length >= 12, '12 ou mais caracteres'],
  [/[a-z]/.test(password), 'uma letra minúscula'],
  [/[A-Z]/.test(password), 'uma letra maiúscula'],
  [/\d/.test(password), 'um número'],
  [/[^A-Za-z0-9]/.test(password), 'um símbolo'],
];
const missing = policy.filter(([ok]) => !ok).map(([, rule]) => rule);
if (missing.length) {
  fail(`Defina BACKOFFICE_PASSWORD com ${missing.join(', ')} (política do user pool).`);
}

const aws = createAws({ stage: STAGE, region: REGION });
const names = quoteNames(STAGE);

try {
  const pool = aws
    .call(['cognito-idp', 'list-user-pools', '--max-results', '60'])
    .UserPools?.find((item) => item.Name === names.userPool);
  if (!pool) {
    fail(`User pool ${names.userPool} não encontrado. Rode pnpm deploy:quote na raiz antes.`);
  }
  for (const user of USERS) {
    const base = ['--user-pool-id', pool.Id, '--username', user.username];
    if (!aws.tryCall(['cognito-idp', 'admin-get-user', ...base])) {
      aws.call(['cognito-idp', 'admin-create-user', ...base, '--message-action', 'SUPPRESS']);
    }
    aws.call([
      'cognito-idp',
      'admin-set-user-password',
      ...base,
      '--password',
      password,
      '--permanent',
    ]);
    const current =
      aws.call(['cognito-idp', 'admin-list-groups-for-user', ...base, '--limit', '60']).Groups ??
      [];
    for (const group of current
      .map((item) => item.GroupName)
      .filter((name) => !user.groups.includes(name))) {
      aws.call(['cognito-idp', 'admin-remove-user-from-group', ...base, '--group-name', group]);
    }
    for (const group of user.groups) {
      aws.call(['cognito-idp', 'admin-add-user-to-group', ...base, '--group-name', group]);
    }
    console.log(`✔ ${user.username}: ${user.groups.join(', ')}`);
  }
} catch (error) {
  fail(`${error.message}\nO Floci está no ar (docker compose up -d)?`);
}
