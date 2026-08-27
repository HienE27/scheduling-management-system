"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState } from "@/components/ui/EmptyState";
import { BackButton } from "@/components/ui/BackButton";
import { useToast } from "@/hooks/useToast";

// ─── Types ────────────────────────────────────────────────────────────────────

type RunConclusion = "success" | "failure" | "cancelled" | "skipped" | "timed_out" | "neutral" | null;
type RunStatus = "queued" | "in_progress" | "completed" | "waiting" | "pending" | null;

type WorkflowRun = {
  id: number;
  name: string;
  display_title: string;
  path: string;
  run_number: number;
  run_attempt: number;
  event: string;
  status: RunStatus;
  conclusion: RunConclusion;
  branch: string | null;
  head_sha: string;
  head_branch: string | null;
  workflow_id: number;
  created_at: string;
  updated_at: string;
  run_started_at: string | null;
  html_url: string;
  actor: { login: string; avatar_url: string } | null;
};

type WorkflowRunsResponse = {
  total_count: number;
  workflow_runs: WorkflowRun[];
};

// ─── Constants ────────────────────────────────────────────────────────────────

const REPO_OWNER = "HienE27";
const REPO_NAME = "scheduling-management-system";

const TRACKED_WORKFLOWS = [
  { id: "ci.yml", label: "CI/CD Pipeline", icon: "deployed_code", description: "Pipeline tổng: build, test, Docker, deploy" },
  { id: "backend-ci.yml", label: "Backend CI/CD", icon: "memory", description: "Maven build + test với MySQL service" },
  { id: "frontend-ci.yml", label: "Frontend CI/CD", icon: "web", description: "pnpm build + lint + E2E + a11y" },
  { id: "pr2-discovery.yml", label: "PR2 Discovery", icon: "manage_search", description: "Sinh JSON metadata cho PR review" },
  { id: "trellis-tasks.yml", label: "Trellis Task Sync", icon: "task_alt", description: "Đồng bộ task ID với PR title" },
] as const;

// ─── Style helpers ────────────────────────────────────────────────────────────

function getConclusionStyle(c: RunConclusion): { label: string; chip: string; icon: string; ringClass: string } {
  switch (c) {
    case "success":
      return { label: "Thành công", chip: "bg-secondary-container text-on-secondary-container border border-on-secondary-container/10", icon: "check_circle", ringClass: "ring-secondary/30" };
    case "failure":
      return { label: "Thất bại", chip: "bg-error-container text-on-error-container border border-error/20", icon: "error", ringClass: "ring-error/30" };
    case "cancelled":
      return { label: "Đã huỷ", chip: "bg-surface-container-highest text-outline border border-outline-variant/30", icon: "block", ringClass: "ring-outline/30" };
    case "timed_out":
      return { label: "Timeout", chip: "bg-tertiary-fixed text-on-tertiary-fixed-variant border border-tertiary/20", icon: "schedule", ringClass: "ring-tertiary/30" };
    default:
      return { label: "Đang chạy", chip: "bg-primary-fixed text-primary border border-primary/20", icon: "progress_activity", ringClass: "ring-primary/30" };
  }
}

function formatRelativeVi(iso: string): string {
  const now = Date.now();
  const t = new Date(iso).getTime();
  const diff = Math.max(0, now - t);
  const min = Math.floor(diff / 60_000);
  if (min < 1) return "vừa xong";
  if (min < 60) return `${min} phút trước`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} giờ trước`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d} ngày trước`;
  return new Date(iso).toLocaleDateString("vi-VN");
}

function shortSha(sha: string): string {
  return sha ? sha.slice(0, 7) : "";
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function CiStatusPage() {
  return <CiStatusContent />;
}

function CiStatusContent() {
  const toast = useToast();
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastSyncedAt, setLastSyncedAt] = useState<Date | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const fetchRuns = useCallback(async () => {
    setError(null);
    try {
      const res = await fetch(
        `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/runs?per_page=30`,
        { headers: { Accept: "application/vnd.github+json" } },
      );
      if (!res.ok) throw new Error(`GitHub API ${res.status}`);
      const data: WorkflowRunsResponse = await res.json();
      setRuns(data.workflow_runs ?? []);
      setLastSyncedAt(new Date());
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Lỗi không xác định";
      setError(msg);
      toast.error(`Không tải được CI status: ${msg}`);
    } finally {
      setLoading(false);
    }
  }, [toast]);

  // Initial + auto-refresh every 60s
  useEffect(() => {
    fetchRuns();
    if (!autoRefresh) return;
    const id = setInterval(fetchRuns, 60_000);
    return () => clearInterval(id);
  }, [fetchRuns, autoRefresh]);

  // Group runs by workflow file
  const grouped = useMemo(() => {
    const map = new Map<string, WorkflowRun[]>();
    for (const wf of TRACKED_WORKFLOWS) map.set(wf.id, []);
    for (const run of runs) {
      const file = run.path.replace(/^\.github\/workflows\//, "");
      if (map.has(file)) map.get(file)!.push(run);
    }
    return map;
  }, [runs]);

  // KPI summary
  const summary = useMemo(() => {
    let success = 0, failure = 0, inProgress = 0, total = 0;
    for (const run of runs) {
      if (run.status !== "completed") inProgress++;
      else if (run.conclusion === "success") success++;
      else if (run.conclusion === "failure") failure++;
      total++;
    }
    return { success, failure, inProgress, total };
  }, [runs]);

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <BackButton href="/dashboard" />
          <h1 className="font-headline-lg text-headline-lg text-on-surface">
            CI/CD Status
          </h1>
          <p className="font-body-sm text-body-sm text-on-surface-variant">
            Trạng thái workflow GitHub Actions cho repo {REPO_OWNER}/{REPO_NAME}.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2 font-label-md text-label-md text-on-surface-variant cursor-pointer">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
              className="size-4 accent-primary"
            />
            Auto-refresh 60s
          </label>
          <Button variant="secondary" size="sm" onClick={fetchRuns} disabled={loading}>
            <span className="material-symbols-outlined text-[18px]">refresh</span>
            Làm mới
          </Button>
          <a
            href={`https://github.com/${REPO_OWNER}/${REPO_NAME}/actions`}
            target="_blank"
            rel="noopener noreferrer"
          >
            <Button variant="primary" size="sm">
              <span className="material-symbols-outlined text-[18px]">open_in_new</span>
              Mở GitHub
            </Button>
          </a>
        </div>
      </div>

      {/* KPI Row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KpiCard
          icon="check_circle"
          iconBg="bg-secondary-container text-on-secondary-container"
          label="Thành công (30 run gần nhất)"
          value={summary.success}
          tone="positive"
        />
        <KpiCard
          icon="error"
          iconBg="bg-error-container text-on-error-container"
          label="Thất bại"
          value={summary.failure}
          tone={summary.failure > 0 ? "negative" : "neutral"}
        />
        <KpiCard
          icon="progress_activity"
          iconBg="bg-primary-fixed text-primary"
          label="Đang chạy"
          value={summary.inProgress}
          tone={summary.inProgress > 0 ? "info" : "neutral"}
        />
        <KpiCard
          icon="list_alt"
          iconBg="bg-surface-container-high text-on-surface-variant"
          label="Tổng run"
          value={summary.total}
          tone="neutral"
        />
      </div>

      {/* Last sync */}
      {lastSyncedAt && (
        <p className="font-label-sm text-label-sm text-on-surface-variant">
          Cập nhật lần cuối:{" "}
          <time dateTime={lastSyncedAt.toISOString()}>
            {lastSyncedAt.toLocaleTimeString("vi-VN")}
          </time>
        </p>
      )}

      {/* Error banner */}
      {error && !loading && (
        <div
          role="alert"
          className="bg-error-container text-on-error-container border border-error/20 rounded-lg p-4 flex items-start gap-3"
        >
          <span className="material-symbols-outlined">warning</span>
          <div className="flex-1">
            <p className="font-label-md text-label-md font-semibold">Không tải được dữ liệu từ GitHub</p>
            <p className="font-body-sm text-body-sm">{error}</p>
          </div>
        </div>
      )}

      {/* Workflow cards */}
      <div className="space-y-4">
        {TRACKED_WORKFLOWS.map((wf) => (
          <WorkflowCard
            key={wf.id}
            label={wf.label}
            description={wf.description}
            icon={wf.icon}
            fileName={wf.id}
            runs={grouped.get(wf.id) ?? []}
            loading={loading}
          />
        ))}
      </div>
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function KpiCard({
  icon,
  iconBg,
  label,
  value,
  tone,
}: {
  icon: string;
  iconBg: string;
  label: string;
  value: number;
  tone: "positive" | "negative" | "info" | "neutral";
}) {
  const valueColor =
    tone === "positive" ? "text-secondary" :
    tone === "negative" ? "text-error" :
    tone === "info" ? "text-primary" :
    "text-on-surface";

  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-lg p-4 shadow-sm h-28 flex flex-col justify-between">
      <div className="flex items-start justify-between gap-2">
        <h3 className="font-label-md text-label-md text-on-surface-variant">{label}</h3>
        <span className={`material-symbols-outlined p-1.5 rounded-md text-[20px] ${iconBg}`}>{icon}</span>
      </div>
      <span className={`font-display-lg text-display-lg ${valueColor}`}>{value}</span>
    </div>
  );
}

function WorkflowCard({
  label,
  description,
  icon,
  fileName,
  runs,
  loading,
}: {
  label: string;
  description: string;
  icon: string;
  fileName: string;
  runs: WorkflowRun[];
  loading: boolean;
}) {
  const latest = runs[0];
  const style = latest ? getConclusionStyle(latest.conclusion) : null;

  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-lg shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-5 py-3.5 border-b border-outline-variant flex items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <span className="material-symbols-outlined text-primary bg-primary-fixed p-1.5 rounded-md text-[20px]">
            {icon}
          </span>
          <div className="min-w-0">
            <h2 className="font-headline-md text-headline-md text-on-surface truncate">{label}</h2>
            <p className="font-label-sm text-label-sm text-on-surface-variant truncate">{description}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {style && (
            <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[12px] font-semibold ${style.chip}`}>
              <span className="material-symbols-outlined text-[14px]">{style.icon}</span>
              {style.label}
            </span>
          )}
          <a
            href={`https://github.com/${REPO_OWNER}/${REPO_NAME}/actions/workflows/${fileName}`}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`Xem ${label} trên GitHub`}
            className="text-on-surface-variant hover:text-primary transition-colors"
          >
            <span className="material-symbols-outlined text-[20px]">open_in_new</span>
          </a>
        </div>
      </div>

      {/* Body */}
      <div className="p-5">
        {loading ? (
          <div className="space-y-2">
            <Skeleton className="h-4 w-2/3 rounded" />
            <Skeleton className="h-4 w-1/2 rounded" />
            <Skeleton className="h-4 w-3/5 rounded" />
          </div>
        ) : runs.length === 0 ? (
          <EmptyState
            icon="history_toggle_off"
            title="Chưa có lần chạy nào"
            description={`Workflow này chưa từng chạy trong 30 lần gần nhất của repo.`}
            size="compact"
          />
        ) : (
          <ul className="space-y-1.5">
            {runs.slice(0, 5).map((run) => {
              const s = getConclusionStyle(run.conclusion);
              const inProgress = run.status !== "completed";
              return (
                <li key={run.id}>
                  <a
                    href={run.html_url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className={`flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-surface-container-low transition-colors group ${inProgress ? "animate-pulse-soft" : ""}`}
                  >
                    <span className={`inline-flex items-center justify-center size-7 rounded-full ${inProgress ? "bg-primary-fixed text-primary" : "bg-surface-container text-on-surface-variant"}`}>
                      <span className="material-symbols-outlined text-[16px]">{s.icon}</span>
                    </span>
                    <div className="flex-1 min-w-0">
                      <p className="font-label-md text-label-md text-on-surface truncate">
                        #{run.run_number} · {run.display_title || run.name}
                      </p>
                      <p className="font-label-sm text-label-sm text-on-surface-variant">
                        {run.head_branch && (
                          <>
                            <span className="material-symbols-outlined text-[12px] align-middle">fork_right</span>{" "}
                            {run.head_branch} ·{" "}
                          </>
                        )}
                        <code className="font-mono">{shortSha(run.head_sha)}</code>
                        {" · "}
                        {formatRelativeVi(run.updated_at)}
                        {run.actor && <> · {run.actor.login}</>}
                      </p>
                    </div>
                    <span className="font-label-sm text-label-sm text-on-surface-variant group-hover:text-primary transition-colors">
                      <span className="material-symbols-outlined text-[18px]">chevron_right</span>
                    </span>
                  </a>
                </li>
              );
            })}
          </ul>
        )}
        {runs.length > 5 && (
          <a
            href={`https://github.com/${REPO_OWNER}/${REPO_NAME}/actions/workflows/${fileName}`}
            target="_blank"
            rel="noopener noreferrer"
            className="block mt-3 text-center font-label-md text-label-md text-primary hover:underline"
          >
            Xem tất cả {runs.length} lần chạy →
          </a>
        )}
      </div>
    </div>
  );
}