import { reactive } from "vue";

export function useWorkspace() {
  const state = reactive({
    token: localStorage.getItem("audiorag_token") || "",
    user: null,
    project: null,
    projects: [],
    documents: [],
    records: [],
    points: [],
    sessions: [],
    agentArtifacts: [],
    agentRuns: [],
    agentCapabilities: [],
    agentRuntime: "offline-retrieval",
    notice: "",
    backend: "http://127.0.0.1:18081",
    busy: false,
  });
  async function api(path, method = "GET", body = null) {
    return window.audioAgent.request({
      path,
      method,
      body,
      token: state.token,
    });
  }
  async function refreshProjects() {
    state.projects = (await api("/api/v1/projects")).projects;
  }
  async function refreshProject() {
    if (!state.project) return;
    const id = state.project.id;
    const results = await Promise.all([
      api("/api/v1/projects/" + id + "/knowledge"),
      api("/api/v1/projects/" + id + "/records"),
      api("/api/v1/projects/" + id + "/learning-plan"),
      api("/api/v1/projects/" + id + "/sessions"),
      api("/api/v1/projects/" + id + "/agent/artifacts"),
      api("/api/v1/projects/" + id + "/agent/runs"),
      api("/api/v1/projects/" + id + "/agent/capabilities"),
    ]);
    if (state.project?.id !== id) return;
    state.documents = results[0].documents;
    state.records = results[1].records;
    state.points = results[2].points;
    state.sessions = results[3].sessions;
    state.agentArtifacts = results[4].artifacts;
    state.agentRuns = results[5].runs;
    state.agentCapabilities = results[6].agents;
  }
  async function openProject(project) {
    state.project = project;
    state.notice = "";
    state.documents = [];
    state.records = [];
    state.points = [];
    state.sessions = [];
    state.agentArtifacts = [];
    state.agentRuns = [];
    await refreshProject();
  }
  async function safe(task) {
    try {
      state.busy = true;
      return await task();
    } catch (error) {
      state.notice = error.message;
    } finally {
      state.busy = false;
    }
  }
  function mediaUrl(path) {
    return /^\/media\/[0-9a-f-]{36}\.wav$/i.test(path || "")
      ? state.backend + path
      : "";
  }
  return {
    state,
    api,
    refreshProjects,
    refreshProject,
    openProject,
    safe,
    mediaUrl,
  };
}
