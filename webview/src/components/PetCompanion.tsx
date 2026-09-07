import { useEffect, useId, useRef, useState } from 'react';
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react';
import { Check, Sparkles } from 'lucide-react';
import { sendCommand } from '../bridge';
import type { PetView } from '../types';

export type PetMood = 'idle' | 'working' | 'celebrating';

function PetFace() {
  return <g className="pet-face">
    <ellipse className="pet-eye pet-eye-left" cx="38" cy="38" rx="2.6" ry="3.7" />
    <ellipse className="pet-eye pet-eye-right" cx="54" cy="38" rx="2.6" ry="3.7" />
    <path className="pet-mouth" d="M42 47c2.3 2.4 5.8 2.4 8.2 0" />
    <circle className="pet-cheek" cx="34" cy="45" r="3.1" />
    <circle className="pet-cheek" cx="58" cy="45" r="3.1" />
  </g>;
}

function CreaturePet() {
  return <>
    <path className="pet-tail" d="M67 63c14 1 14-18 5-20-7-2-10 5-5 9" />
    <path className="pet-ear pet-ear-left" d="M25 29 29 12l13 13Z" />
    <path className="pet-ear pet-ear-right" d="m51 25 13-13 4 18Z" />
    <path className="pet-body" d="M22 43c0-15 10-24 24-24s24 9 24 24v22c0 13-10 21-24 21S22 78 22 65Z" />
    <path className="pet-belly" d="M31 62c3-7 9-10 15-10s12 3 15 10v15c-4 6-9 9-15 9s-12-3-15-9Z" />
    <path className="pet-arm pet-arm-left" d="M29 58c-7 6-9 14-4 18 4 2 8-2 11-9" />
    <path className="pet-arm pet-arm-right" d="M63 58c7 6 9 14 4 18-4 2-8-2-11-9" />
    <PetFace />
    <path className="pet-nose" d="m46 42-2.4-2.1h4.8Z" />
    <path className="pet-stripe" d="M39 25v7m7-9v8m7-6-2 7" />
  </>;
}

function OwlPet() {
  return <>
    <path className="pet-wing pet-wing-left" d="M25 46C11 50 14 72 30 77l8-20Z" />
    <path className="pet-wing pet-wing-right" d="M67 46c14 4 11 26-5 31l-8-20Z" />
    <path className="pet-body" d="M21 40c0-16 10-26 25-26s25 10 25 26v28c0 12-11 19-25 19s-25-7-25-19Z" />
    <path className="pet-belly" d="M29 57c4-7 10-10 17-10s13 3 17 10v21c-5 6-10 9-17 9s-12-3-17-9Z" />
    <circle className="pet-eye-ring" cx="36" cy="39" r="11" />
    <circle className="pet-eye-ring" cx="56" cy="39" r="11" />
    <circle className="pet-eye" cx="36" cy="39" r="3.2" />
    <circle className="pet-eye" cx="56" cy="39" r="3.2" />
    <path className="pet-beak" d="m46 43-5 5 5 4 5-4Z" />
    <path className="pet-feather" d="m35 66 5 5 6-6 6 6 5-5" />
  </>;
}

function DuckPet() {
  return <>
    <ellipse className="pet-wing pet-wing-right" cx="64" cy="64" rx="16" ry="12" transform="rotate(-18 64 64)" />
    <path className="pet-body" d="M23 48c0-20 8-32 23-32 14 0 23 11 23 25 0 7-3 12-7 16 7 5 9 13 5 20-4 8-13 11-23 9-15 0-23-8-23-20 0-7 2-12 7-17Z" />
    <path className="pet-belly" d="M30 62c4-7 10-10 17-10 8 0 14 5 17 13 2 8-5 18-18 20-11-1-18-7-16-23Z" />
    <ellipse className="pet-eye" cx="39" cy="37" rx="2.6" ry="3.5" />
    <ellipse className="pet-eye" cx="55" cy="37" rx="2.6" ry="3.5" />
    <path className="pet-beak" d="M38 45c5-5 12-5 18 0-5 7-13 8-18 0Z" />
    <path className="pet-foot" d="M34 84c-5 1-8 3-9 6m33-6c5 1 8 3 9 6" />
  </>;
}

function RobotPet() {
  return <>
    <path className="pet-antenna" d="M46 17V9" />
    <circle className="pet-signal" cx="46" cy="7" r="3.5" />
    <rect className="pet-body" x="20" y="19" width="52" height="54" rx="17" />
    <rect className="pet-screen" x="27" y="29" width="38" height="27" rx="10" />
    <ellipse className="pet-eye pet-eye-left" cx="38" cy="42" rx="3" ry="4" />
    <ellipse className="pet-eye pet-eye-right" cx="54" cy="42" rx="3" ry="4" />
    <path className="pet-mouth" d="M41 49h10" />
    <circle className="pet-core" cx="46" cy="65" r="4.5" />
    <path className="pet-arm pet-arm-left" d="M20 42c-10 3-11 16-3 19" />
    <path className="pet-arm pet-arm-right" d="M72 42c10 3 11 16 3 19" />
    <path className="pet-leg" d="M34 72v12m24-12v12" />
    <path className="pet-foot" d="M28 86h13m10 0h13" />
  </>;
}

function VocalistPet() {
  return <>
    <path className="pet-hair-back" d="M24 43c0-21 9-32 22-32s23 11 23 32v39H23Z" />
    <path className="pet-idol-body" d="M30 59c4-7 10-10 16-10s12 3 16 10l5 25H25Z" />
    <circle className="pet-skin" cx="46" cy="36" r="18" />
    <path className="pet-hair" d="M28 34c1-15 8-23 19-23 10 0 18 8 18 21-8-6-15-9-24-8-5 1-9 4-13 10Z" />
    <path className="pet-hair-highlight" d="M47 14c-5 4-7 8-7 13m14-10c-3 4-4 7-4 10" />
    <PetFace />
    <path className="pet-idol-arm pet-arm-left" d="M31 62c-8 2-11 9-8 15" />
    <path className="pet-idol-arm pet-arm-right" d="M61 62c7-8 12-8 15-4" />
    <g className="pet-prop"><rect className="pet-mic" x="72" y="47" width="5" height="24" rx="2.5" transform="rotate(18 72 47)" /><circle className="pet-mic-head" cx="77" cy="47" r="5" /></g>
    <path className="pet-costume" d="m37 58 9 9 9-9 7 26H30Z" />
    <path className="pet-ribbon" d="m46 61-8-6v12l8-4 8 4V55Z" />
  </>;
}

function GuitaristPet() {
  return <>
    <path className="pet-hair-back" d="M22 41c0-20 10-31 24-31s24 11 24 31v42H22Z" />
    <path className="pet-idol-body" d="M29 59c4-7 10-10 17-10 8 0 14 4 18 11l4 24H24Z" />
    <circle className="pet-skin" cx="46" cy="35" r="18" />
    <path className="pet-hair" d="M28 33c1-15 8-23 19-23 12 0 19 9 19 23-7-5-12-9-18-12-4 7-10 11-20 12Z" />
    <PetFace />
    <path className="pet-idol-arm pet-arm-left" d="M30 62c-5 8-5 14 1 18" />
    <path className="pet-idol-arm pet-arm-right" d="M62 62c5 7 5 13 0 17" />
    <g className="pet-prop pet-guitar"><path className="pet-guitar-body" d="M34 62c-8 1-11 7-9 13 3 7 12 7 17 1 5 6 14 5 16-2 2-6-3-11-10-11Z" /><path className="pet-guitar-neck" d="m48 65 24-20 4 5-24 21Z" /><circle className="pet-guitar-hole" cx="42" cy="70" r="3" /></g>
    <path className="pet-costume" d="M34 58h24l5 26H29Z" />
  </>;
}

function CustomPet() {
  return <>
    <path className="pet-custom-halo" d="M18 42c2-18 13-29 28-29s26 11 28 29v27c-6 11-15 17-28 17S24 80 18 69Z" />
    <path className="pet-custom-card" d="M25 30c0-7 5-12 12-12h18c7 0 12 5 12 12v42c0 7-5 12-12 12H37c-7 0-12-5-12-12Z" />
    <path className="pet-custom-star" d="m46 32 5 11 12 1-9 8 3 12-11-6-11 6 3-12-9-8 12-1Z" />
    <circle className="pet-custom-spark" cx="24" cy="23" r="3" />
    <circle className="pet-custom-spark" cx="71" cy="19" r="2" />
  </>;
}

function renderPetVisual(visual: PetView['visual']) {
  switch (visual) {
    case 'owl': return <OwlPet />;
    case 'duck': return <DuckPet />;
    case 'robot': return <RobotPet />;
    case 'idol-vocalist': return <VocalistPet />;
    case 'idol-guitarist': return <GuitaristPet />;
    case 'custom-avatar': return <CustomPet />;
    default: return <CreaturePet />;
  }
}

export function PetArtwork({ pet, mood = 'idle', compact = false }: { pet: PetView; mood?: PetMood; compact?: boolean }) {
  const id = useId().replace(/:/g, '');
  return <span
    className={`pet-artwork visual-${pet.visual} mood-${mood} motion-${pet.motion} ${compact ? 'compact' : ''}`}
    style={{ '--pet-accent': pet.accent ?? '#8FAEFF' } as CSSProperties}
    aria-hidden="true"
  >
    <svg viewBox="0 0 92 96" role="presentation">
      <defs>
        <linearGradient id={`${id}-body`} x1="18" y1="12" x2="72" y2="88" gradientUnits="userSpaceOnUse">
          <stop stopColor="white" stopOpacity=".52" />
          <stop offset=".23" stopColor="var(--pet-accent)" />
          <stop offset="1" stopColor="var(--pet-accent)" stopOpacity=".66" />
        </linearGradient>
        <radialGradient id={`${id}-shine`} cx="0" cy="0" r="1" gradientTransform="translate(38 28) rotate(52) scale(42 48)" gradientUnits="userSpaceOnUse">
          <stop stopColor="white" stopOpacity=".72" />
          <stop offset="1" stopColor="white" stopOpacity="0" />
        </radialGradient>
        <filter id={`${id}-shadow`} x="-25%" y="-25%" width="150%" height="165%">
          <feDropShadow dx="0" dy="3" stdDeviation="2.2" floodColor="#000" floodOpacity=".32" />
        </filter>
      </defs>
      <g className="pet-character" style={{ '--pet-body-fill': `url(#${id}-body)`, '--pet-shine-fill': `url(#${id}-shine)` } as CSSProperties} filter={`url(#${id}-shadow)`}>
        {renderPetVisual(pet.visual)}
      </g>
      <g className="pet-celebration">
        <path d="m14 24 2 4 4 2-4 2-2 4-2-4-4-2 4-2Z" />
        <path d="m78 30 1.6 3.2 3.4 1.7-3.4 1.6L78 40l-1.7-3.5-3.3-1.6 3.3-1.7Z" />
        <circle cx="18" cy="56" r="2" /><circle cx="76" cy="61" r="2" />
      </g>
    </svg>
  </span>;
}

export function PetCompanion({ pet, running, x, y }: { pet: PetView; running: boolean; x: number; y: number }) {
  const [position, setPosition] = useState({ x, y });
  const [dragging, setDragging] = useState(false);
  const [mood, setMood] = useState<PetMood>(running ? 'working' : 'idle');
  const [message, setMessage] = useState('');
  const positionRef = useRef(position);
  const dragRef = useRef<{ pointerId: number; host: DOMRect; moved: boolean } | null>(null);
  const wasRunning = useRef(running);

  useEffect(() => {
    const next = { x, y };
    positionRef.current = next;
    setPosition(next);
  }, [x, y, pet.id]);

  useEffect(() => {
    if (running) {
      setMood('working');
      setMessage('正在和你一起处理');
    } else if (wasRunning.current) {
      setMood('celebrating');
      setMessage('完成啦，来检查结果吧');
      const timer = window.setTimeout(() => { setMood('idle'); setMessage(''); }, 3200);
      wasRunning.current = running;
      return () => window.clearTimeout(timer);
    } else {
      setMood('idle');
      setMessage('');
    }
    wasRunning.current = running;
  }, [running]);

  useEffect(() => {
    if (running || !pet.idleMessages.length) return;
    const interval = window.setInterval(() => {
      const next = pet.idleMessages[Math.floor(Math.random() * pet.idleMessages.length)] ?? '';
      setMessage(next);
      window.setTimeout(() => setMessage((current) => current === next ? '' : current), 4200);
    }, Math.max(12, pet.idleIntervalSeconds) * 1000);
    return () => window.clearInterval(interval);
  }, [pet.id, pet.idleIntervalSeconds, pet.idleMessages, running]);

  const move = (clientX: number, clientY: number, host: DOMRect) => {
    const next = {
      x: Math.max(520, Math.min(9480, Math.round(((clientX - host.left) / Math.max(1, host.width)) * 10000))),
      y: Math.max(720, Math.min(9250, Math.round(((clientY - host.top) / Math.max(1, host.height)) * 10000)))
    };
    positionRef.current = next;
    setPosition(next);
  };

  const finishDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    dragRef.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    if (positionRef.current.x < 1300) positionRef.current = { ...positionRef.current, x: 650 };
    if (positionRef.current.x > 8700) positionRef.current = { ...positionRef.current, x: 9350 };
    setPosition(positionRef.current);
    sendCommand('settings.pet', { petX: positionRef.current.x, petY: positionRef.current.y });
    if (!drag.moved && !running) {
      setMessage(pet.idleMessages[0] ?? pet.description);
      window.setTimeout(() => setMessage(''), 3600);
    }
  };

  return <button
    className={`pet-companion mood-${mood} ${dragging ? 'dragging' : ''}`}
    style={{ left: `${position.x / 100}%`, top: `${position.y / 100}%`, '--pet-accent': pet.accent ?? '#8FAEFF' } as CSSProperties}
    aria-label={`移动桌宠 ${pet.name}`}
    title={`${pet.name} · ${running ? '任务进行中' : pet.description}`}
    onPointerDown={(event) => {
      const host = event.currentTarget.parentElement?.getBoundingClientRect();
      if (!host) return;
      dragRef.current = { pointerId: event.pointerId, host, moved: false };
      setDragging(true);
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={(event) => {
      const drag = dragRef.current;
      if (drag?.pointerId !== event.pointerId) return;
      drag.moved = true;
      move(event.clientX, event.clientY, drag.host);
    }}
    onPointerUp={finishDrag}
    onPointerCancel={finishDrag}
  >
    {message && <span className="pet-speech" role="status">{message}</span>}
    <span className="pet-stage"><span className="pet-ground" /><PetArtwork pet={pet} mood={mood} /></span>
    <span className="pet-state" aria-hidden="true">{mood === 'celebrating' ? <Check /> : mood === 'working' ? <span className="pet-state-pulse" /> : <Sparkles />}</span>
  </button>;
}

export function PetPicker({ pets, selectedId, onSelect }: { pets: PetView[]; selectedId: string; onSelect: (id: string) => void }) {
  const selected = pets.find((pet) => pet.id === selectedId) ?? pets[0];
  const [previewMood, setPreviewMood] = useState<PetMood>('idle');
  return <div className="pet-picker">
    {selected && <section className="pet-showcase" style={{ '--pet-accent': selected.accent ?? '#8FAEFF' } as CSSProperties}>
      <div className="pet-showcase-stage"><span className="pet-ground" /><PetArtwork pet={selected} mood={previewMood} /></div>
      <div><span className="eyebrow">原创角色预览</span><h3>{selected.name}</h3><p>{selected.description}</p><div className="pet-preview-moods">
        {(['idle', 'working', 'celebrating'] as PetMood[]).map((mood) => <button key={mood} className={previewMood === mood ? 'active' : ''} onClick={() => setPreviewMood(mood)}>{mood === 'idle' ? '待机' : mood === 'working' ? '工作' : '完成'}</button>)}
      </div></div>
    </section>}
    <div className="pet-gallery" role="list" aria-label="桌宠角色库">
      {pets.map((pet) => <button key={pet.id} role="listitem" className={`pet-gallery-card ${selectedId === pet.id ? 'selected' : ''}`} onClick={() => onSelect(pet.id)}>
        <span className="pet-gallery-art"><PetArtwork pet={pet} compact /></span>
        <span><strong>{pet.name}</strong><small>{pet.description}</small></span>
        {selectedId === pet.id && <Check className="pet-selected-mark" />}
      </button>)}
    </div>
  </div>;
}
