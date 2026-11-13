import { canonicalJson } from './canonical-json';

describe('canonicalJson', () => {
  it('ignora a ordem das chaves e os campos indefinidos', () => {
    expect(canonicalJson({ b: 1, a: { d: 2, c: 3 } })).toBe(
      canonicalJson({ a: { c: 3, d: 2 }, b: 1 }),
    );
    expect(canonicalJson({ a: 1, b: undefined })).toBe(canonicalJson({ a: 1 }));
  });

  it('distingue corpos diferentes', () => {
    expect(canonicalJson({ offerId: 'a' })).not.toBe(canonicalJson({ offerId: 'b' }));
  });

  it('ordena as chaves pelas unidades UTF-16, como a RFC 8785, sem depender do idioma', () => {
    expect(canonicalJson({ a: 1, B: 2, _: 3 })).toBe('{"B":2,"_":3,"a":1}');
  });
});
