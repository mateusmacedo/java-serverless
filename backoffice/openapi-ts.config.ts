import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: '../contracts/openapi.yaml',
  output: 'src/app/shared/api/generated',
  plugins: [
    { name: '@hey-api/client-angular', runtimeConfigPath: './src/app/shared/api/client-config' },
    '@hey-api/typescript',
    '@hey-api/sdk',
    { name: '@angular/common', httpRequests: true, httpResources: true },
    '@hey-api/schemas',
  ],
});
