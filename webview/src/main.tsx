import React from 'react';
import { createRoot } from 'react-dom/client';
import { App, UiErrorBoundary } from './App';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <UiErrorBoundary><App /></UiErrorBoundary>
  </React.StrictMode>
);
