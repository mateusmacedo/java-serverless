const LOCALE = 'pt-BR';

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

const currencyFormats = new Map<string, Intl.NumberFormat>();

/**
 * Formata o valor decimal do contrato ("2450.90") na moeda recebida, no padrão brasileiro ("R$ 2.450,90"). O texto
 * vai direto ao `Intl`, que o formata como decimal exato, sem passar por `number`. Valor ausente ou fora do formato
 * decimal vira texto vazio: ausência não é zero.
 */
export function formatMoney(
  amount: string | null | undefined,
  currency: string | null | undefined,
): string {
  if (!isDecimal(amount) || !currency) {
    return '';
  }
  return currencyFormat(currency).format(amount as Intl.StringNumericLiteral);
}

/** Indica se o texto é um decimal do contrato (`^-?\d+(\.\d+)?$`). */
export function isDecimal(value: string | null | undefined): value is string {
  return typeof value === 'string' && DECIMAL.test(value);
}

/**
 * Compara dois decimais do contrato sem converter para `number`: negativo quando `a < b`, zero quando iguais e
 * positivo quando `a > b`. "10.0" e "10" são iguais.
 */
export function compareDecimal(a: string, b: string): number {
  const left = parts(a);
  const right = parts(b);
  if (left.negative !== right.negative) {
    return left.negative ? -1 : 1;
  }
  const magnitude = compareMagnitude(left, right);
  return left.negative ? -magnitude : magnitude;
}

/** Indica se os dois decimais têm o mesmo valor ("10.0" e "10"). */
export function sameDecimal(a: string, b: string): boolean {
  return compareDecimal(a, b) === 0;
}

interface DecimalParts {
  readonly negative: boolean;
  readonly integer: string;
  readonly fraction: string;
}

function parts(value: string): DecimalParts {
  const match = DECIMAL.exec(value);
  if (!match) {
    throw new RangeError(`Valor decimal inválido: ${value}`);
  }
  const integer = match[2].replace(/^0+(?=\d)/, '');
  const fraction = trimTrailingZeros(match[3] ?? '');
  const zero = integer === '0' && fraction === '';
  return { negative: match[1] === '-' && !zero, integer, fraction };
}

function compareMagnitude(left: DecimalParts, right: DecimalParts): number {
  if (left.integer.length !== right.integer.length) {
    return left.integer.length - right.integer.length;
  }
  if (left.integer !== right.integer) {
    return left.integer < right.integer ? -1 : 1;
  }
  const size = Math.max(left.fraction.length, right.fraction.length);
  const a = left.fraction.padEnd(size, '0');
  const b = right.fraction.padEnd(size, '0');
  if (a === b) {
    return 0;
  }
  return a < b ? -1 : 1;
}

function trimTrailingZeros(value: string): string {
  let end = value.length;
  while (end > 0 && value[end - 1] === '0') {
    end--;
  }
  return value.slice(0, end);
}

function currencyFormat(currency: string): Intl.NumberFormat {
  let format = currencyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat(LOCALE, { style: 'currency', currency });
    currencyFormats.set(currency, format);
  }
  return format;
}
