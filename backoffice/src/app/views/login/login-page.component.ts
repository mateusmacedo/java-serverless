import {
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import {
  AlertComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  ContainerComponent,
  FormControlDirective,
  FormLabelDirective,
  RowComponent,
  SpinnerComponent,
} from '@coreui/angular';
import { IconDirective } from '@coreui/icons-angular';

import { AuthSession } from '../../core/auth/auth-session';
import { safeReturnUrl } from '../../core/auth/browser-location';
import { signInMessage } from '../../core/auth/cognito-client';
import { SessionStore } from '../../core/auth/session-store';
import { controlMessage, focusFirstInvalid } from '../../shared/ui/forms/form-errors';

/**
 * Login (`/login`): usuário e senha do user pool, a mensagem de sessão expirada e o retorno seguro ao endereço pedido
 * antes do login (`returnUrl`, só caminhos deste painel).
 */
@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    ContainerComponent,
    FormControlDirective,
    FormLabelDirective,
    IconDirective,
    ReactiveFormsModule,
    RowComponent,
    SpinnerComponent,
  ],
  template: `
    <div class="bg-body-tertiary min-vh-100 d-flex flex-row align-items-center">
      <c-container breakpoint="md">
        <c-row class="justify-content-center">
          <c-col lg="6" md="8" xl="5">
            <c-card class="p-4">
              <c-card-body>
                <div class="d-flex align-items-center gap-2 mb-3">
                  <svg aria-hidden="true" cIcon height="32" name="signet"></svg>
                  <h1 class="h3 mb-0">Cotação de seguros</h1>
                </div>
                <p class="text-body-secondary">Entre com o usuário e a senha da sua organização.</p>
                @if (sessionExpired()) {
                  <c-alert color="warning" role="status">Sua sessão expirou. Entre de novo para continuar.</c-alert>
                }
                @if (failure(); as message) {
                  <c-alert color="danger" role="alert">{{ message }}</c-alert>
                }
                <form #formElement (ngSubmit)="submit()" [formGroup]="form" novalidate>
                  <div class="mb-3">
                    <label cLabel for="login-username">Usuário</label>
                    <input
                      [attr.aria-describedby]="message('username') ? 'login-username-error' : null"
                      [attr.aria-invalid]="!!message('username')"
                      [valid]="message('username') ? false : undefined"
                      autocomplete="username"
                      autocapitalize="none"
                      cFormControl
                      formControlName="username"
                      id="login-username"
                      type="text"
                    />
                    @if (message('username'); as text) {
                      <div class="invalid-feedback d-block" id="login-username-error">{{ text }}</div>
                    }
                  </div>
                  <div class="mb-4">
                    <label cLabel for="login-password">Senha</label>
                    <input
                      [attr.aria-describedby]="message('password') ? 'login-password-error' : null"
                      [attr.aria-invalid]="!!message('password')"
                      [valid]="message('password') ? false : undefined"
                      autocomplete="current-password"
                      cFormControl
                      formControlName="password"
                      id="login-password"
                      type="password"
                    />
                    @if (message('password'); as text) {
                      <div class="invalid-feedback d-block" id="login-password-error">{{ text }}</div>
                    }
                  </div>
                  <button [disabled]="busy()" cButton class="px-4" color="primary" type="submit">
                    @if (busy()) {
                      <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />
                    }
                    Entrar
                  </button>
                </form>
              </c-card-body>
            </c-card>
          </c-col>
        </c-row>
      </c-container>
    </div>
  `,
})
export class LoginPageComponent {
  readonly #auth = inject(AuthSession);
  readonly #session = inject(SessionStore);
  readonly #router = inject(Router);

  /** `?expired=1`, quando o painel trouxe a pessoa de volta depois de a sessão expirar. */
  readonly expired = input<string | undefined>();
  readonly returnUrl = input<string | undefined>();

  readonly sessionExpired = computed(() => this.expired() === '1');
  readonly busy = signal(false);
  readonly failure = signal<string | null>(null);
  readonly #submitted = signal(false);
  readonly formElement = viewChild<ElementRef<HTMLFormElement>>('formElement');

  readonly form = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  message(name: 'username' | 'password'): string | null {
    const control = this.form.controls[name];
    return this.#submitted() || control.touched ? controlMessage(control) : null;
  }

  async submit(): Promise<void> {
    this.#submitted.set(true);
    this.failure.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      setTimeout(() => focusFirstInvalid(this.formElement()?.nativeElement));
      return;
    }
    this.busy.set(true);
    try {
      const { username, password } = this.form.getRawValue();
      await this.#auth.signIn(username.trim(), password);
      await this.#session.load();
      await this.#router.navigateByUrl(safeReturnUrl(this.returnUrl()));
    } catch (error) {
      this.failure.set(signInMessage(error));
      this.form.controls.password.reset('');
    } finally {
      this.busy.set(false);
    }
  }
}
