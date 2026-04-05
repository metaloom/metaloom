import { API_BASE_URL } from "./config";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
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
    throw new Error(`Login failed (${res.status})`);
  }

  return res.json() as Promise<LoginResponse>;
}
