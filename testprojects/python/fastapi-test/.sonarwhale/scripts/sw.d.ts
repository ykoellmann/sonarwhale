// Sonarwhale Script API — auto-generated, do not edit
// Place sw.d.ts at the root of .sonarwhale/scripts/ for IDE autocomplete

interface SwResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  error?: string;
  json<T = any>(): T;
}

interface SwExpect {
  toBe(expected: any): void;
  toEqual(expected: any): void;
  toBeTruthy(): void;
  toBeFalsy(): void;
  toContain(substr: string): void;
}

declare const sw: {
  env: {
    get(key: string): string | undefined;
    set(key: string, value: string): void;
  };
  request: {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: string;
    setHeader(key: string, value: string): void;
    setBody(body: string): void;
    setUrl(url: string): void;
  };
  response: {
    status: number;
    headers: Record<string, string>;
    body: string;
    json<T = any>(): T;
  };
  http: {
    get(url: string, headers?: Record<string, string>): SwResponse;
    post(url: string, body: string, headers?: Record<string, string>): SwResponse;
    request(method: string, url: string, body?: string, headers?: Record<string, string>): SwResponse;
  };
  test(name: string, fn: () => void): void;
  expect(value: any): SwExpect;
};