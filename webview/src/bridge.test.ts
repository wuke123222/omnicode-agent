describe('remote JCEF bridge startup', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.resetModules();
    delete window.omnicodeSend;
    delete window.__omnicodeReceive;
    delete window.__omnicodeBridgeReceipt;
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

  it('clears the receipt timeout when JCEF confirms the command was queued', async () => {
    const nativeBridge = vi.fn();
    window.omnicodeSend = nativeBridge;
    const { sendCommand, subscribeBridge } = await import('./bridge');
    const events: unknown[] = [];
    subscribeBridge((message) => events.push(message));

    const requestId = sendCommand('session.send', { text: 'hello' });
    window.__omnicodeBridgeReceipt?.(requestId, 'session.send', 'scheduled');
    await vi.advanceTimersByTimeAsync(ACK_TIMEOUT_MS_FOR_TEST);

    expect(nativeBridge).toHaveBeenCalledTimes(1);
    expect(events).toContainEqual(expect.objectContaining({ type: 'command.accepted', requestId }));
    expect(events).not.toContainEqual(expect.objectContaining({ type: 'command.error', requestId }));
  });

  it('reports JCEF transport failure immediately instead of waiting for the generic timeout', async () => {
    const nativeBridge = vi.fn();
    window.omnicodeSend = nativeBridge;
    const { sendCommand, subscribeBridge } = await import('./bridge');
    const events: unknown[] = [];
    subscribeBridge((message) => events.push(message));

    const requestId = sendCommand('project.map', {});
    window.__omnicodeBridgeReceipt?.(requestId, 'project.map', 'transport-error');
    await vi.advanceTimersByTimeAsync(ACK_TIMEOUT_MS_FOR_TEST);

    expect(nativeBridge).toHaveBeenCalledTimes(1);
    expect(events).toContainEqual(expect.objectContaining({
      type: 'command.error',
      requestId,
      payload: expect.objectContaining({ message: expect.stringContaining('桥接通信失败') })
    }));
  });
});

const ACK_TIMEOUT_MS_FOR_TEST = 8_000;
