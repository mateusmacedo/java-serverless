import { Component, DestroyRef, inject, type OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, RouterOutlet } from '@angular/router';
import { ColorModeService } from '@coreui/angular';
import { IconSetService } from '@coreui/icons-angular';
import { delay, filter, map, tap } from 'rxjs/operators';

import { iconSubset } from './icons/icon-subset';

@Component({
  selector: 'app-root',
  template: '<router-outlet />',
  imports: [RouterOutlet],
})
export class AppComponent implements OnInit {
  readonly #destroyRef = inject(DestroyRef);
  readonly #activatedRoute = inject(ActivatedRoute);
  readonly #colorModeService = inject(ColorModeService);

  constructor() {
    inject(Title).setTitle('Cotação de seguros');
    inject(IconSetService).icons = { ...iconSubset };
    this.#colorModeService.localStorageItemName.set('backoffice-theme');
    this.#colorModeService.eventName.set('ColorSchemeChange');
  }

  ngOnInit(): void {
    this.#activatedRoute.queryParams
      .pipe(
        delay(1),
        map((params) => {
          const theme = params['theme'] as string | undefined;
          return /^[A-Za-z]+/.exec(theme ?? '')?.[0];
        }),
        filter((theme): theme is string => ['dark', 'light', 'auto'].includes(theme ?? '')),
        tap((theme) => this.#colorModeService.colorMode.set(theme)),
        takeUntilDestroyed(this.#destroyRef),
      )
      .subscribe();
  }
}
