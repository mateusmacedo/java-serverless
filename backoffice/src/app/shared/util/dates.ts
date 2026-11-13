/** Fuso das datas do painel: o mesmo das datas civis do contrato (`desiredStartDate`). */
export const SAO_PAULO_TIME_ZONE = 'America/Sao_Paulo';

/** Instante absoluto: `Date`, milissegundos desde a época ou texto ISO 8601 com fuso, como a API devolve. */
export type Instant = Date | number | string;

interface WallTime {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

// WHY: en-US com h23 garante as partes numéricas e a meia-noite como 00, sem depender do fuso do navegador.
const wallTimeFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: SAO_PAULO_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

const relativeFormat = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'always' });
const relativeDayFormat = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });

const CIVIL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Data e hora no fuso de São Paulo: "08/11/2026 14:02". Instante inválido vira texto vazio. */
export function formatDateTime(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time
    ? `${pad(time.day)}/${pad(time.month)}/${time.year} ${pad(time.hour)}:${pad(time.minute)}`
    : '';
}

/** Forma curta, sem o ano, das colunas de lista: "08/11 14:02". */
export function formatShortDateTime(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time ? `${pad(time.day)}/${pad(time.month)} ${pad(time.hour)}:${pad(time.minute)}` : '';
}

/** Só a hora, com segundos quando pedidos: "14:02" ou "14:02:31". */
export function formatTime(instant: Instant | null | undefined, seconds = false): string {
  const time = wallTimeOf(instant);
  if (!time) {
    return '';
  }
  return `${pad(time.hour)}:${pad(time.minute)}${seconds ? ':' + pad(time.second) : ''}`;
}

/** Data civil do contrato ("2026-12-01") no formato brasileiro ("01/12/2026"); fora do formato, texto vazio. */
export function formatCivilDate(value: string | null | undefined): string {
  const match = CIVIL_DATE.exec(value ?? '');
  return match ? `${match[3]}/${match[2]}/${match[1]}` : '';
}

/** Data civil de hoje em São Paulo, no formato do contrato e do `<input type="date">`: "2026-11-12". */
export function todayCivilDate(now: Instant = Date.now()): string {
  const time = wallTimeOf(now);
  return time ? `${time.year}-${pad(time.month)}-${pad(time.day)}` : '';
}

/** Início do dia civil em São Paulo, em ISO 8601 UTC, para o filtro `createdFrom`. */
export function startOfCivilDay(value: string | null | undefined): string | undefined {
  const start = civilDayStart(value);
  return start === undefined ? undefined : new Date(start).toISOString();
}

/** Último milissegundo do dia civil em São Paulo, em ISO 8601 UTC, para o filtro inclusivo `createdTo`. */
export function endOfCivilDay(value: string | null | undefined): string | undefined {
  const match = CIVIL_DATE.exec(value ?? '');
  if (!match) {
    return undefined;
  }
  const next = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + 1));
  const nextStart = civilDayStart(next.toISOString().slice(0, 10));
  return nextStart === undefined ? undefined : new Date(nextStart - 1).toISOString();
}

/**
 * Distância até agora em texto: "agora", "há 5 minutos", "há 1 hora", "ontem", "há 3 dias", "em 2 horas". Abaixo de
 * um minuto, nos dois sentidos, é "agora".
 */
export function formatRelative(
  instant: Instant | null | undefined,
  now: Instant = Date.now(),
): string {
  const target = toMillis(instant);
  const reference = toMillis(now);
  if (target === undefined || reference === undefined) {
    return '';
  }
  const diff = target - reference;
  if (Math.abs(diff) < MINUTE) {
    return 'agora';
  }
  const minutes = Math.round(diff / MINUTE);
  if (Math.abs(minutes) < 60) {
    return relativeFormat.format(minutes, 'minute');
  }
  const hours = Math.round(diff / HOUR);
  if (Math.abs(hours) < 24) {
    return relativeFormat.format(hours, 'hour');
  }
  const days = Math.round(diff / DAY);
  if (Math.abs(days) === 1) {
    return relativeDayFormat.format(days, 'day');
  }
  if (Math.abs(days) < 30) {
    return relativeFormat.format(days, 'day');
  }
  const months = Math.round(days / 30);
  if (Math.abs(months) < 12) {
    return relativeFormat.format(months, 'month');
  }
  return relativeFormat.format(Math.round(days / 365), 'year');
}

/** Milissegundos do instante, ou `undefined` quando ausente ou inválido. */
export function toMillis(instant: Instant | null | undefined): number | undefined {
  if (instant === null || instant === undefined || instant === '') {
    return undefined;
  }
  let millis: number;
  if (instant instanceof Date) {
    millis = instant.getTime();
  } else if (typeof instant === 'number') {
    millis = instant;
  } else {
    millis = Date.parse(instant);
  }
  return Number.isFinite(millis) ? millis : undefined;
}

function civilDayStart(value: string | null | undefined): number | undefined {
  const match = CIVIL_DATE.exec(value ?? '');
  if (!match) {
    return undefined;
  }
  const local = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  if (Number.isNaN(local)) {
    return undefined;
  }
  // WHY: `new Date('2026-11-10T00:00')` usaria o fuso da máquina. O deslocamento de São Paulo é medido no próprio
  // fuso, e a segunda passada acerta a data em que o deslocamento mudou (horário de verão até 2019).
  const firstGuess = local - offsetAt(local);
  return local - offsetAt(firstGuess);
}

function wallTimeOf(instant: Instant | null | undefined): WallTime | undefined {
  const millis = toMillis(instant);
  return millis === undefined ? undefined : wallTime(millis);
}

function wallTime(millis: number): WallTime {
  const values: Record<string, number> = {};
  for (const part of wallTimeFormat.formatToParts(millis)) {
    if (part.type !== 'literal') {
      values[part.type] = Number(part.value);
    }
  }
  return {
    year: values['year'],
    month: values['month'],
    day: values['day'],
    hour: values['hour'],
    minute: values['minute'],
    second: values['second'],
  };
}

/** Diferença entre o relógio de São Paulo e o UTC no instante, em milissegundos (-3 h hoje). */
function offsetAt(millis: number): number {
  const time = wallTime(millis);
  const asUtc = Date.UTC(time.year, time.month - 1, time.day, time.hour, time.minute, time.second);
  return asUtc - Math.floor(millis / SECOND) * SECOND;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}
