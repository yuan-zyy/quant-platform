import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const port = Number(process.env.QUANT_DEMO_PORT ?? 8080)
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('QUANT_DEMO_PORT must be a valid TCP port')
}

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    proxy: { '/v1': `http://127.0.0.1:${port}` },
  },
})
