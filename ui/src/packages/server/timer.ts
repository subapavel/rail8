/** The timers the git half waits on, injected so that a test runs every deadline on a fake one. */
export interface Timer {
  /** Calls `run` once `ms` milliseconds have passed, and answers what cancels that call. */
  after(ms: number, run: () => void): () => void;
}

/** The timers of the JavaScript runtime; a pending one does not keep the development server alive. */
export const realTimer: Timer = {
  after(ms, run) {
    const handle = setTimeout(run, ms);
    handle.unref();
    return () => clearTimeout(handle);
  },
};
