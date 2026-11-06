/** Colors come only from the CSS variables in src/index.css (design tokens). */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--bg)', surface: 'var(--surface)', card: 'var(--card)', muted: 'var(--muted)', border: 'var(--border)',
        'input-border': 'var(--input-border)', fg: 'var(--fg)', 'muted-fg': 'var(--muted-fg)',
        accent: 'var(--accent)', 'accent-2': 'var(--accent-2)', 'on-accent': 'var(--on-accent)',
        success: 'var(--success)', danger: 'var(--danger)', focus: 'var(--focus)', warn: 'var(--warn)', info: 'var(--info)',
      },
      fontFamily: { sans: ['"Fira Sans"', 'system-ui', 'sans-serif'], mono: ['"Fira Code"', 'ui-monospace', 'monospace'] },
      keyframes: {
        rise: { from: { opacity: '0', transform: 'translateY(8px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
      },
      animation: { rise: 'rise .45s cubic-bezier(.2,.8,.2,1) both' },
    },
  },
  plugins: [],
};
