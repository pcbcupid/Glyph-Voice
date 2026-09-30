import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt', // Never reload an active recording to install an update.
      manifest: {
        name: 'PCBCUPID GLYPH VOICE',
        short_name: 'Glyph Voice',
        description: 'Voicing your thoughts. Foreground Glyph voice transcription.',
        theme_color: '#111a19',
        background_color: '#f5f5ef',
        display: 'standalone',
        start_url: '.',
        icons: [{ src: 'glyph.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,webmanifest}'],
        // App shell only. No provider requests, audio, transcripts, or credentials.
        runtimeCaching: [],
        cleanupOutdatedCaches: true,
        skipWaiting: false,
        clientsClaim: false,
      },
    }),
  ],
  server: { port: 5173, strictPort: true },
});
