<template>
  <router-view v-slot="{ Component, route }"
    ><component :is="route.meta.public ? Component : AppShell"
      ><component v-if="!route.meta.public" :is="Component" /></component
  ></router-view>
</template>
<script setup>
import AppShell from './components/AppShell.vue'
</script>
