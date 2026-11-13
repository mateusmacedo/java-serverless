import type { AbstractControl } from '@angular/forms';

import { type ApiError, fieldErrorMap } from '../../util/api-error';

/** Chave do erro que veio no 400 do servidor, com a mensagem dele. */
export const SERVER_ERROR = 'server';

/**
 * Põe cada erro do 400 (`fieldErrors`, com `path` e `message`) no controle do mesmo caminho (`risk.vehicleRef`), como
 * o erro `server`, e marca o controle como tocado para a mensagem aparecer. O erro sai quando a pessoa muda o valor.
 * Devolve os caminhos que acharam controle, para o alerta da tela não repeti-los.
 */
export function applyServerErrors(
  form: AbstractControl,
  error: ApiError | null | undefined,
): string[] {
  const applied: string[] = [];
  for (const [path, message] of Object.entries(fieldErrorMap(error))) {
    const control = form.get(path);
    if (control) {
      control.setErrors({ ...control.errors, [SERVER_ERROR]: message });
      control.markAsTouched();
      applied.push(path);
    }
  }
  return applied;
}

/** Caminhos do 400 que têm campo no formulário, para a tela decidir se volta aos campos. */
export function serverErrorPaths(
  form: AbstractControl,
  error: ApiError | null | undefined,
): string[] {
  return Object.keys(fieldErrorMap(error)).filter((path) => form.get(path) !== null);
}

/**
 * Mensagem para mostrar embaixo do campo tocado: a do servidor ou a dos validadores do Angular. A tela só chama com o
 * campo tocado ou depois de tentar enviar.
 */
export function controlMessage(control: AbstractControl | null | undefined): string | null {
  const errors = control?.errors;
  if (!errors) {
    return null;
  }
  if (typeof errors[SERVER_ERROR] === 'string') {
    return errors[SERVER_ERROR];
  }
  if (errors['required']) {
    return 'Preencha este campo.';
  }
  if (errors['maxlength']) {
    return `Use até ${errors['maxlength'].requiredLength} caracteres.`;
  }
  if (errors['minDate']) {
    return errors['minDate'] as string;
  }
  return 'Confira este campo.';
}

/**
 * Trava ou destrava o formulário durante um envio. Só mexe quando o estado muda: destravar refaz a validação, e
 * repetir o `enable` num formulário já liberado apagaria os erros que o servidor pôs nos campos.
 */
export function lockForm(form: AbstractControl): void {
  if (form.enabled) {
    form.disable({ emitEvent: false });
  }
}

export function unlockForm(form: AbstractControl): void {
  if (form.disabled) {
    form.enable({ emitEvent: false });
  }
}

export function setLocked(form: AbstractControl, locked: boolean): void {
  if (locked) {
    lockForm(form);
    return;
  }

  unlockForm(form);
}

/**
 * Leva o foco ao primeiro campo inválido, na ordem do formulário na tela. O campo marca a si mesmo com
 * `aria-invalid="true"`; o `id` do elemento vem do `controlIds` da tela.
 */
export function focusFirstInvalid(root: HTMLElement | null | undefined): void {
  const target = root?.querySelector<HTMLElement>('[aria-invalid="true"]');
  target?.focus();
}
