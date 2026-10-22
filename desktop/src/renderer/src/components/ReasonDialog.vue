<script setup>
import { ref, watch } from "vue";
const props = defineProps({ open: Boolean, title: String, message: String });
const emit = defineEmits(["confirm", "cancel"]);
const reason = ref("");
watch(() => props.open, value => { if (value) reason.value = ""; });
function submit() {
  if (reason.value.trim().length >= 10 && reason.value.trim().length <= 500) emit("confirm", reason.value.trim());
}
</script>
<template>
  <Teleport to="body">
    <div v-if="open" class="reason-backdrop">
      <form role="dialog" aria-modal="true" class="reason-dialog" @submit.prevent="submit">
        <h3>{{ title }}</h3><p>{{ message }}</p>
        <label>人工确认理由（10至500字）<textarea v-model="reason" rows="5" maxlength="500" /></label>
        <small>{{ reason.trim().length }} / 500</small>
        <div class="row"><button type="button" @click="emit('cancel')">取消，不执行</button>
          <button class="primary" :disabled="reason.trim().length < 10">确认此操作</button></div>
      </form>
    </div>
  </Teleport>
</template>
<style scoped>
.reason-backdrop { position:fixed; inset:0; z-index:100; background:#0008; display:grid; place-items:center; padding:20px; }
.reason-dialog { width:min(560px,100%); background:var(--panel,#fff); color:#263238; padding:24px; border-radius:14px; box-shadow:0 16px 60px #0004; }
.reason-dialog textarea { width:100%; margin:10px 0; resize:vertical; }
</style>
