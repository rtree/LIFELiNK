// ===== sticky nav =====
const nav = document.getElementById('nav');
const onScroll = () => nav.classList.toggle('solid', window.scrollY > 20);
window.addEventListener('scroll', onScroll, { passive: true });
onScroll();

// ===== mobile menu =====
const burger = document.getElementById('burger');
const links = document.querySelector('.nav-links');
burger.addEventListener('click', () => links.classList.toggle('open'));
links.addEventListener('click', e => {
  if (e.target.tagName === 'A') links.classList.remove('open');
});

// ===== SOS demo =====
const sosBtn  = document.getElementById('sosBtn');
const sosHint = document.getElementById('sosHint');
const sosLog  = document.getElementById('sosLog');

const STEPS = [
  'Preview: coarse location context',
  'Preview: phone calls AI and contact',
  'Preview: carrier merges the calls',
  'Preview: Discord friends get updates'
];

let running = false;
let timers = [];

function resetDemo() {
  timers.forEach(clearTimeout);
  timers = [];
  running = false;
  sosLog.innerHTML = '';
  sosBtn.classList.remove('sent');
  sosBtn.textContent = 'SOS';
  sosHint.textContent = 'Tap to preview · no real call';
}

sosBtn.addEventListener('click', () => {
  if (running) { resetDemo(); return; }

  running = true;
  sosLog.innerHTML = '';
  sosBtn.classList.add('sent');
  sosBtn.textContent = 'DEMO';
  sosHint.textContent = 'Illustration only · no call is placed';

  STEPS.forEach((text, n) => {
    timers.push(setTimeout(() => {
      const row = document.createElement('div');
      row.textContent = text;
      sosLog.appendChild(row);
    }, 600 * (n + 1)));
  });

  // auto-reset so the demo can be replayed
  timers.push(setTimeout(resetDemo, 8000));
});


// ===== scroll reveal =====
const targets = document.querySelectorAll(
  '.stat, .step, .split-txt, .dc-window, .w-card'
);
targets.forEach(el => el.classList.add('reveal'));
const io = new IntersectionObserver((entries) => {
  entries.forEach((entry, idx) => {
    if (!entry.isIntersecting) return;
    const delay = Math.min(idx * 70, 350);
    setTimeout(() => entry.target.classList.add('in'), delay);
    io.unobserve(entry.target);
  });
}, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });

targets.forEach(el => io.observe(el));

// ===== year in footer =====
const yr = new Date().getFullYear();
document.querySelectorAll('.f-bot small').forEach(el => {
  el.innerHTML = el.innerHTML.replace('© 2026', `© ${yr}`);
});
