<script setup>
import { Play } from "lucide-vue-next";
import { queueMessage } from "../../../shared/audio-queue-policy.js";
defineProps({ queues: Array, currentId: String, busy: Boolean });
const emit = defineEmits(["play"]);
</script>
<template>
  <div class="audio-history">
    <article v-for="queue in queues" :key="queue.queue_id" class="history-audio-row" :class="{ selected: queue.queue_id === currentId }">
      <div><h3>{{ queue.title || queue.instruction || "音频内容" }}</h3>
        <p class="muted">{{ queue.lessons?.length || 0 }} / {{ queue.total_segments || 0 }} 段已就绪 · {{ queueMessage(queue) }}</p>
        <small v-if="queue.updated_at" class="muted">{{ String(queue.updated_at).slice(0, 16).replace('T', ' ') }}</small>
      </div>
      <button :disabled="busy" @click="emit('play', queue)"><Play :size="14" /> {{ queue.queue_id === currentId ? "打开" : "继续收听" }}</button>
    </article>
  </div>
</template>
