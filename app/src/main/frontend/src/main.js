import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Lara from '@primeuix/themes/lara'
import App from './App.vue'
import router from './router/index.js'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import 'primeicons/primeicons.css'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
// Neo4j teal scale derived from the NDL design tokens.
// Lara defaults: light → primary.500, dark → primary.400.
// We override colorScheme to use 600 (dark teal) in light mode and 200 (light cyan) in dark.
const Neo4jPreset = definePreset(Lara, {
  semantic: {
    primary: {
      50:  '#E7FAFB',
      100: '#CBF0F3',
      200: '#00CDD7',
      300: '#5DB3BF',
      400: '#4C99A4',
      500: '#30839D',
      600: '#0A6190',
      700: '#02507B',
      800: '#014063',
      900: '#01121C',
      950: '#000810'
    },
    colorScheme: {
      light: {
        primary: {
          color:         '{primary.600}',
          contrastColor: '#ffffff',
          hoverColor:    '{primary.700}',
          activeColor:   '{primary.800}'
        }
      },
      dark: {
        primary: {
          color:         '{primary.200}',
          contrastColor: '{primary.900}',
          hoverColor:    '{primary.300}',
          activeColor:   '{primary.400}'
        }
      }
    }
  }
})

app.use(PrimeVue, {
  theme: {
    preset: Neo4jPreset,
    options: {
      darkModeSelector: '[data-theme="dark"]'
    }
  }
})
app.mount('#app')
