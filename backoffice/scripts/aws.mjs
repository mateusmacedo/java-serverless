import { execFileSync } from 'node:child_process';

/** Endpoint do Floci no host, o mesmo do `compose.yaml` da raiz. */
export const FLOCI_ENDPOINT = process.env.FLOCI_ENDPOINT ?? 'http://localhost:4566';

/**
 * Executa o AWS CLI e devolve o JSON da resposta. No stage `local`, aponta para o Floci com as chaves fixas de teste,
 * sem depender de perfil configurado; nos outros, usa o perfil e as credenciais do ambiente.
 */
export function createAws({ stage, region, profile }) {
  const local = stage === 'local';
  const env = { ...process.env };
  if (local) {
    delete env.AWS_PROFILE;
    env.AWS_ACCESS_KEY_ID = 'test';
    env.AWS_SECRET_ACCESS_KEY = 'test';
  }
  const base = local
    ? ['--endpoint-url', FLOCI_ENDPOINT, '--region', region]
    : ['--region', region, ...(profile ? ['--profile', profile] : [])];

  const run = (args) => {
    const output = execFileSync('aws', [...base, ...args, '--output', 'json'], {
      env,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return output.trim() ? JSON.parse(output) : {};
  };

  return {
    /** Lança com a mensagem do CLI. */
    call: (args) => {
      try {
        return run(args);
      } catch (error) {
        throw new Error(
          `aws ${args.slice(0, 2).join(' ')} falhou: ${String(error.stderr ?? error.message).trim()}`,
        );
      }
    },
    /** Devolve `null` quando o CLI falha, para o chamador decidir. */
    tryCall: (args) => {
      try {
        return run(args);
      } catch {
        return null;
      }
    },
  };
}

/** Nomes que o `serverless.yml` da quote-solution dá aos recursos do stage. */
export function quoteNames(stage) {
  return {
    userPool: `quote-solution-${stage}-users`,
    userPoolClient: `quote-solution-${stage}-web`,
    restApi: `${stage}-quote-solution`,
  };
}

export function fail(message) {
  console.error(`\n✖ ${message}\n`);
  process.exit(1);
}
