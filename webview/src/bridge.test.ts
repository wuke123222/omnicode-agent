describe('remote JCEF bridge startup', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.resetModules();
    delete window.omnicodeSend;
    delete window.__omnicodeReceive;
    window.__OMNICODE_PAGE_GENERATION__ = 11;
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('holds the readiness command until the delayed native bridge is injected', async () => {
    const { sendCommand, subscribeBridge } = await import('./bridge');
    const errors: unknown[] = [];
    subscribeBridge((message) => errors.push(message));

    sendCommand('frontend.ready', {});
    await vi.advanceTimersByTimeAsync(249);
    expect(errors).toEqual([]);

    const nativeBridge = vi.fn();
    window.omnicodeSend = nativeBridge;
    await vi.advanceTimersByTimeAsync(1);

    expect(nativeBridge).toHaveBeenCalledTimes(1);
    expect(JSON.parse(nativeBridge.mock.calls[0][0])).toMatchObject({
      command: 'frontend.ready',
      pageGeneration: 11
    });
    expect(errors).toEqual([]);
  });

  it('retries only the idempotent readiness handshake until the host acknowledges it', async () => {
    const nativeBridge = vi.fn();
    window.omnicodeSend = nativeBridge;
    const { sendCommand } = await import('./bridge');

    const requestId = sendCommand('frontend.ready', {});
    expect(nativeBridge).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(nativeBridge).toHaveBeenCalledTimes(2);

    window.__omnicodeReceive?.({ type: 'command.accepted', requestId });
    await vi.advanceTimersByTimeAsync(2_000);
    expect(nativeBridge).toHaveBeenCalledTimes(2);
  });
});
