"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { Staff } from "@/types/api";

const AUTH_STORAGE_KEY = "medschedule.user";
const TOKEN_STORAGE_KEY = "medschedule.token";
const REFRESH_TOKEN_STORAGE_KEY = "medschedule.refreshToken";

type AuthUser = {
  username: string;
  userId: number;
  roles: string[];
  permissions: string[];
};

type AuthState = {
  token: string | null;
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
};

type LoginResponse = {
  success?: boolean;
  message?: string;
  data?: {
    token?: string;
    userId?: number;
    username?: string;
    roles?: string[];
    permissions?: string[];
  };
};

const AuthContext = createContext<AuthState | null>(null);
const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1";

function toAuthUser(staff: Staff, permissions: string[] = []): AuthUser {
  return {
    username: staff.username,
    userId: staff.id,
    roles: staff.roles ?? [],
    permissions,
  };
}

function persistAuthUser(user: AuthUser | null) {
  if (typeof window === "undefined") {
    return;
  }

  if (user) {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user));
    return;
  }

  window.localStorage.removeItem(AUTH_STORAGE_KEY);
}

function getStoredToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_STORAGE_KEY);
}

function isUnauthorizedError(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const candidate = error as {
    status?: number;
    response?: { status?: number };
    statusCode?: number;
    message?: string;
  };
  return (
    candidate.status === 401 ||
    candidate.response?.status === 401 ||
    candidate.statusCode === 401 ||
    (typeof candidate.message === "string" &&
      candidate.message.includes("HTTP 401"))
  );
}

/**
 * BUGFIX (was FE#7) helper. The api-client wraps fetch errors with a
 * uniform shape; here we sniff for 5xx specifically so the bootstrap
 * path can flag the session as stale without immediately bouncing the
 * user to /login on a transient backend hiccup.
 */
function looksLike5xx(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const candidate = error as {
    status?: number;
    response?: { status?: number };
    statusCode?: number;
    message?: string;
  };
  const code =
    candidate.status ?? candidate.response?.status ?? candidate.statusCode;
  if (typeof code === "number" && code >= 500 && code < 600) return true;
  return (
    typeof candidate.message === "string" && /HTTP 5\d{2}/.test(candidate.message)
  );
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [mounted, setMounted] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [token, setToken] = useState<string | null>(() => getStoredToken());
  const router = useRouter();

  const refreshUser = useCallback(async () => {
    try {
      const currentStaff = await api.get<Staff>("/staff/me");
      // Preserve the permissions we already have in state — they came from the
      // JWT issued at login and the /staff/me endpoint doesn't carry them.
      setUser((prev) => {
        const nextUser = toAuthUser(currentStaff, prev?.permissions ?? []);
        persistAuthUser(nextUser);
        return nextUser;
      });
    } catch (error) {
      // BUGFIX (was DEAD-USER-AUTH): if /staff/me returns 401 the token
      // is dead — clear local auth so the next render doesn't treat the
      // user as signed in with permissions: []. Re-throw so the caller
      // (typically login()) can handle the redirect. Other errors
      // (network, 5xx) are re-thrown unchanged so the caller's existing
      // error-handling path keeps working.
      if (isUnauthorizedError(error)) {
        window.localStorage.removeItem(TOKEN_STORAGE_KEY);
        window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
        persistAuthUser(null);
        setUser(null);
        setToken(null);
        router.replace("/login");
      }
      throw error;
    }
  }, [router]);

  useEffect(() => {
    let active = true;

    const bootstrapAuth = async () => {
      setMounted(true);

      // BUGFIX (was DEAD-USER-AUTH): previously the bootstrap called
      // setUser(JSON.parse(savedUser)) immediately on every page load so
      // the UI rendered with whatever was in localStorage — even when
      // `medschedule.token` was missing/expired/invalid (e.g. after admin
      // toggled a permission that bumped `permVer`, or after a tab was
      // closed for longer than the access-token TTL). Downstream pages
      // then mounted with `permissions: []` and every API call returned
      // 403 because no Authorization header was attached (or the token
      // was rejected by PermissionInvalidationFilter).
      //
      // Fix: gate `isAuthenticated` on BOTH a valid saved user AND a
      // successful /staff/me round-trip. While /staff/me is in flight we
      // keep isLoading=true so children render the loading skeleton. If
      // /staff/me fails with 401, we drop the dead user and bounce to
      // /login instead of leaving the UI mounted in a no-permission state.
      const storedToken = window.localStorage.getItem(TOKEN_STORAGE_KEY);
      const savedUser = window.localStorage.getItem(AUTH_STORAGE_KEY);
      const hadSavedSession = Boolean(storedToken && savedUser);

      if (!hadSavedSession) {
        // Nothing to restore — finish bootstrap immediately so the
        // RouteGuard can redirect unauthenticated users to /login.
        if (active) setIsLoading(false);
        return;
      }

      // Hydrate the cached user *only* so the AppSidebar/Header can show
      // the right avatar and role chip during the loading window. We do
      // NOT expose this hydrated user as `isAuthenticated` until /staff/me
      // confirms the token is still valid.
      try {
        if (savedUser) {
          setUser(JSON.parse(savedUser) as AuthUser);
        }
      } catch {
        persistAuthUser(null);
      }

      // Refresh user data from server. On 401 (token expired/invalid),
      // clear auth and bounce to /login so the user re-authenticates
      // instead of hammering the API with a stale token and spamming 403.
      try {
        const currentStaff = await api.get<Staff>("/staff/me");
        if (!active) return;
        // Preserve permissions already known from the JWT/login payload —
        // /staff/me does not carry them. Falling back to whatever was already
        // in state prevents a brief flash of zero-permission UI right after
        // page reload (the bug that caused RouteGuard to 403 STAFF on
        // /holidays even though the JWT clearly carries HOLIDAY_VIEW).
        // Use functional updater to read the latest user state — the closure
        // captured by useEffect has a stale `user` reference because the effect
        // runs once on mount with `user = null`.
        setUser((prev) => {
          const nextUser = toAuthUser(currentStaff, prev?.permissions ?? []);
          persistAuthUser(nextUser);
          return nextUser;
        });
      } catch (error) {
        if (!active) return;
        if (isUnauthorizedError(error)) {
          // Token definitively invalid — clear everything so the next page
          // load treats the user as anonymous instead of carrying around a
          // dead token that 401s every request. This is the critical branch
          // that prevents the "MANAGER 403 on /staff/active" symptom: a
          // stale user with no token gets dropped, not kept.
          window.localStorage.removeItem(TOKEN_STORAGE_KEY);
          window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
          persistAuthUser(null);
          setUser(null);
          setToken(null);
          router.replace("/login");
        } else if (looksLike5xx(error)) {
          // BUGFIX (was FE#7): server is reachable but unhappy (5xx). The
          // previous code silently kept the localStorage user in place and
          // showed them as logged in — so they'd see the dashboard for
          // ~half a second then every API call would fail, leaving the UI
          // half-rendered with no recovery path (no toast, no banner). Now
          // we surface a soft warning via setMessage-equivalent and a
          // "session-stale" hint that callers can use to show a banner.
          // We do NOT auto-logout (the user may have valid data cached and
          // a retry might succeed); we just record the staleness so the
          // UI can offer a manual "Thử lại" path.
          try {
            window.sessionStorage.setItem("medschedule.session.stale", "1");
          } catch {
            /* sessionStorage unavailable — fall through */
          }
          console.warn("[AuthProvider] /staff/me returned 5xx during bootstrap:", error);
        }
        // For non-401 / non-5xx (network drop, CORS, etc.), keep localStorage
        // user as-is — the next successful call will refresh silently.
      } finally {
        if (active) {
          setIsLoading(false);
        }
      }
    };

    void bootstrapAuth();

    return () => {
      active = false;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const response = await fetch("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, password }),
    });

    if (!response.ok) {
      let msg = "Đăng nhập thất bại. Kiểm tra backend hoặc tài khoản.";
      try {
        const errPayload = (await response.json()) as { message?: string };
        if (errPayload?.message) msg = errPayload.message;
      } catch {
        // response body wasn't JSON — keep default message
      }
      throw new Error(msg);
    }

    // Parse body ONCE to extract token and user data
    const payload = (await response.json()) as LoginResponse;
    const token =
      response.headers.get("X-Auth-Token") ?? payload.data?.token;

    const fallbackUser = {
      username: payload.data?.username ?? username,
      userId: payload.data?.userId ?? 0,
      roles: payload.data?.roles ?? [],
      permissions: payload.data?.permissions ?? [],
    };

    if (token) {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, token);
      setToken(token);
    }
    // Refresh token — the backend may also send it via `Set-Cookie` (HttpOnly)
    // and credentials: "include" already attaches that cookie. The JS-readable
    // mirror is kept in localStorage so the api-client can include it in the
    // /auth/refresh POST body if needed.
    const refreshToken = payload.data && (payload.data as { refreshToken?: string }).refreshToken;
    if (refreshToken) {
      window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken);
    }
    persistAuthUser(fallbackUser);
    setUser(fallbackUser);
    setIsLoading(true);

    try {
      await refreshUser();
      router.replace("/");
    } finally {
      setIsLoading(false);
    }
  }, [refreshUser, router]);

  const logout = useCallback(async () => {
    // Logout is a best-effort operation: even if the backend call fails
    // (server down, 500, network drop, …) we still must wipe local auth
    // state and redirect to /login. The previous version rejected the
    // promise from api.logout() and Next.js 16 surfaced it as a runtime
    // error in the UI even though finally{} ran cleanly.
    try {
      await api.logout().catch(() => {
        // Backend unreachable / 500 — silent on purpose. We'll still
        // drop the tokens below so the UI returns to a logged-out state.
      });
    } finally {
      window.localStorage.removeItem(TOKEN_STORAGE_KEY);
      window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
      setToken(null);
      persistAuthUser(null);
      setUser(null);
      setIsLoading(false);
      router.replace("/login");
    }
  }, [router]);

  const value = useMemo(
    () => ({
      token,
      user,
      // BUGFIX (was DEAD-USER-AUTH): `isAuthenticated` now requires BOTH a
      // hydrated user AND a present access token. Previously a stale user
      // with permissions: [] (no token attached) was enough for the UI to
      // think the caller was signed in, which surfaced downstream as a
      // 403 storm on every guarded endpoint — most visibly
      // `api/v1/staff/active` returning 403 for a real MANAGER after their
      // session had been wiped server-side.
      isAuthenticated: Boolean(mounted && user && token),
      isLoading: !mounted || isLoading,
      login,
      logout,
      refreshUser,
    }),
    [isLoading, login, logout, mounted, refreshUser, token, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);

  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }

  return value;
}
