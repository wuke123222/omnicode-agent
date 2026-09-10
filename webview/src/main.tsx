import React from 'react';
import { createRoot } from 'react-dom/client';
import { App, UiErrorBoundary } from './App';
import './styles.css';
import './components/pet-companion.css';

function mount(attempt = 0) {
  const root = document.getElementById('root');
  if (!root) {
    // This should only be reachable during a transient remote-JCEF document swap. Throwing here
    // loses the whole renderer and prevents the host from receiving its readiness handshake.
    // A tiny bounded retry also works when DOMContentLoaded already fired for about:blank.
    if (attempt < 20) window.setTimeout(() => mount(attempt + 1), 16);
    return;
  }
  createRoot(root).render(
    <React.StrictMode>
      <UiErrorBoundary><App /></UiErrorBoundary>
    </React.StrictMode>
  );
}

mount();
