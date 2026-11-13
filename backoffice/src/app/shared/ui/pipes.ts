import { Pipe, type PipeTransform } from '@angular/core';

import {
  formatCivilDate,
  formatDateTime,
  formatRelative,
  formatShortDateTime,
  formatTime,
  type Instant,
} from '../util/dates';
import { formatMoney } from '../util/money';

/** Forma da data: `full` "08/11/2026 14:02", `short` "08/11 14:02" e `time` "14:02". */
export type DateTimeFormat = 'full' | 'short' | 'time';

/** Data e hora no fuso de São Paulo: `{{ quote.createdAt | dateTime: 'short' }}`. */
@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(instant: Instant | null | undefined, format: DateTimeFormat = 'full'): string {
    if (format === 'short') {
      return formatShortDateTime(instant);
    }
    return format === 'time' ? formatTime(instant) : formatDateTime(instant);
  }
}

/** Data civil do contrato no formato brasileiro: `{{ snapshot.desiredStartDate | civilDate }}`. */
@Pipe({ name: 'civilDate' })
export class CivilDatePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return formatCivilDate(value);
  }
}

/** Valor decimal na moeda: `{{ offer.premiumTotal | money: offer.currency }}`; ausente vira texto vazio. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amount: string | null | undefined, currency: string | null | undefined): string {
    return formatMoney(amount, currency);
  }
}

/** Distância até agora ("há 1 hora"), calculada quando o valor muda, sem relógio rodando na tela. */
@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  transform(instant: Instant | null | undefined, now?: Instant): string {
    return formatRelative(instant, now ?? Date.now());
  }
}
