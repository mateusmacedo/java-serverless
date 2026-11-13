// WHY: o PRD (§15) mantém o layout neutro até a identidade visual ser decidida; a marca é um escudo e o nome do
// produto, no formato de ícone do CoreUI ([viewBox, conteúdo SVG]).
const SHIELD = `<path d="M16 2 4 6.5v8.3c0 7.4 5.1 13.6 12 15.2 6.9-1.6 12-7.8 12-15.2V6.5L16 2Zm0 3.2 9 3.4v6.2c0 5.7-3.8 10.6-9 12-5.2-1.4-9-6.3-9-12V8.6l9-3.4Z"/>
  <path d="m14.6 19.4-4-4 1.8-1.8 2.2 2.2 5-5 1.8 1.8-6.8 6.8Z"/>`;

export const signet = ['32 32', `<g style="fill: currentColor">${SHIELD}</g>`];

export const logo = [
  '200 32',
  `<g style="fill: currentColor">${SHIELD}
    <text x="38" y="21.5" font-family="system-ui, -apple-system, 'Segoe UI', sans-serif" font-size="15" font-weight="600">Cotação de seguros</text>
  </g>`,
];
