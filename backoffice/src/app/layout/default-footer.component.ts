import { Component, inject } from '@angular/core';
import { FooterComponent } from '@coreui/angular';

import { APP_CONFIG } from '../core/config/app-config';

@Component({
  selector: 'app-default-footer',
  template: `
    <div>Plataforma de cotação <span class="text-body-secondary small">· ambiente {{ stage }}</span></div>
    <div class="ms-auto">
      Feito com
      <a href="https://coreui.io/angular" rel="noopener" target="_blank">CoreUI for Angular</a>
    </div>
  `,
})
export class DefaultFooterComponent extends FooterComponent {
  readonly stage = inject(APP_CONFIG).stage;
}
