import { createRouter, createWebHistory } from 'vue-router'
import MetadataView from '../views/MetadataView.vue'
import CanvasView from '../views/CanvasView.vue'
import GenerateView from '../views/GenerateView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/views' },
    { path: '/metadata', component: MetadataView },
    { path: '/views', component: CanvasView },
    { path: '/generate', component: GenerateView }
  ]
})
