/**
 * In-process fixed-window counter for limits that apply to one tool call
 * rather than to an HTTP route (express-rate-limit covers the routes). Kept in
 * memory on purpose: the sidecar runs as a single replica and holds no other
 * state, so a restart resetting the counters is acceptable for a demo stack.
 */
export class FixedWindowCounter {
  private readonly windows = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Counts one hit for `key`; refuses without counting once the limit is reached. */
  tryConsume(key: string): { ok: true } | { ok: false; retryAfterSeconds: number } {
    const t = this.now();
    if (this.windows.size > 10_000) {
      for (const [k, w] of this.windows) if (w.resetAt <= t) this.windows.delete(k);
    }
    let w = this.windows.get(key);
    if (!w || w.resetAt <= t) {
      w = { count: 0, resetAt: t + this.windowMs };
      this.windows.set(key, w);
    }
    if (w.count >= this.limit) {
      return { ok: false, retryAfterSeconds: Math.ceil((w.resetAt - t) / 1000) };
    }
    w.count += 1;
    return { ok: true };
  }
}
