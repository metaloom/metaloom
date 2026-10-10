import { API_BASE_URL } from "./config";
import { UserResponse } from "./users";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
}

/**
 * The login endpoint answered, and said no. Carries the status so a refusal (4xx) can be told
 * apart from a server that failed (5xx); a network failure never produces one of these at all.
 */
export class LoginFailedError extends Error {
  constructor(readonly status: number) {
    super(`Login failed (${status})`);
    this.name = "LoginFailedError";
  }
}

/** True when the server refused these credentials, as opposed to failing to answer properly. */
export function isCredentialsRejection(e: unknown): boolean {
  return e instanceof LoginFailedError && e.status >= 400 && e.status < 500;
}

/**
 * Call the Loom REST login endpoint.
 * POST /api/v1/login  { username, password } → { token }
 */
export async function login(
  username: string,
  password: string
): Promise<LoginResponse> {
  const res = await fetch(`${API_BASE_URL}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password } satisfies LoginRequest),
  });

  if (!res.ok) {
    throw new LoginFailedError(res.status);
  }

  return res.json() as Promise<LoginResponse>;
}

/** Claims decoded from the Loom JWT payload. */
export interface JwtClaims {
  /** The authenticated user's uuid (Loom puts this in the "uuid" claim). */
  uuid?: string;
  /** Expiration time, seconds since epoch (standard JWT "exp" claim). */
  exp?: number;
}

/**
 * Decode (without verifying) the payload of a Loom JWT for immediate client-side
 * UI state. The signature is NOT checked — treat the result as a hint only; the
 * authoritative user identity comes from {@link getMe}. Returns null if the token
 * is malformed.
 */
export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const json = decodeURIComponent(
      atob(payload)
        .split("")
        .map((c) => `%${c.charCodeAt(0).toString(16).padStart(2, "0")}`)
        .join(""),
    );
    return JSON.parse(json) as JwtClaims;
  } catch {
    return null;
  }
}

/** True when the token carries an `exp` claim that is in the past. */
export function isJwtExpired(token: string): boolean {
  const claims = decodeJwt(token);
  if (!claims?.exp) return false;
  return claims.exp * 1000 <= Date.now();
}

/**
 * Load the currently authenticated user.
 * GET /api/v1/me → UserResponse
 */
export async function getMe(token: string): Promise<UserResponse> {
  const res = await fetch(`${API_BASE_URL}/me`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
  });

  if (!res.ok) {
    throw new Error(`Failed to load current user (${res.status})`);
  }

  return res.json() as Promise<UserResponse>;
}
