/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        primary: { DEFAULT: '#1E40AF', light: '#3B82F6', dark: '#1E3A8A' },
        accent: { DEFAULT: '#D97706' },
        surface: '#F8FAFC',
        muted: '#E9EEF6',
        'status-ok': '#16A34A', 'status-warn': '#D97706', 'status-bad': '#DC2626'
      },
      fontFamily: { sans: ['Fira Sans', 'sans-serif'], mono: ['Fira Code', 'monospace'] }
    }
  },
  plugins: [],
}

