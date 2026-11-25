
/**
 * EXAM FORGE - SambaNova relay for Cloudflare Workers
 *
 * Workerに登録する設定：
 * SAMBANOVA_API_KEY   : Secret
 * PROXY_ACCESS_TOKEN : Secret
 * ALLOWED_ORIGIN      : Text
 */

const UPSTREAM = "https://api.sambanova.ai/v1/chat/completions";

const ALLOWED_MODELS = new Set([
  "gemma-4-31B-it",
  "gpt-oss-120b"
]);

const MAX_BODY_BYTES = 12 * 1024 * 1024;

const EXPOSE_HEADERS = [
  "retry-after",
  "x-ratelimit-limit-requests",
  "x-ratelimit-remaining-requests",
  "x-ratelimit-limit-requests-day",
  "x-ratelimit-remaining-requests-day",
  "x-ratelimit-limit-tokens",
  "x-ratelimit-remaining-tokens",
  "x-request-id"
];

function corsHeaders(origin, allowedOrigin) {
  const headers = new Headers({
    "Vary": "Origin",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Proxy-Token",
    "Access-Control-Max-Age": "86400",
    "Access-Control-Expose-Headers": EXPOSE_HEADERS.join(", "),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });

  if (origin && allowedOrigin && origin === allowedOrigin) {
    headers.set("Access-Control-Allow-Origin", origin);
  }

  return headers;
}

function json(data, status = 200, headers = new Headers()) {
  const h = new Headers(headers);
  h.set("Content-Type", "application/json; charset=utf-8");
  h.set("Cache-Control", "no-store");

  return new Response(JSON.stringify(data), {
    status,
    headers: h
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin") || "";

    const allowedOrigin = String(env.ALLOWED_ORIGIN || "")
      .trim()
      .replace(/\/$/, "");

    const cors = corsHeaders(origin, allowedOrigin);

    // Workerの動作確認用
    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: true,
        service: "EXAM FORGE SambaNova relay",
        models: [...ALLOWED_MODELS]
      });
    }

    // 登録したサイト以外からの利用を拒否
    if (!allowedOrigin || origin !== allowedOrigin) {
      return json({
        error: {
          message: "Origin is not allowed. Check ALLOWED_ORIGIN."
        }
      }, 403, cors);
    }

    // ブラウザの事前確認通信
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: cors
      });
    }

    // 指定されたAPI経路以外は拒否
    if (
      request.method !== "POST" ||
      url.pathname !== "/v1/chat/completions"
    ) {
      return json({
        error: {
          message: "Use POST /v1/chat/completions."
        }
      }, 404, cors);
    }

    // 中継用アクセスキーを確認
    const suppliedToken =
      request.headers.get("X-Proxy-Token") || "";

    if (
      !env.PROXY_ACCESS_TOKEN ||
      suppliedToken !== env.PROXY_ACCESS_TOKEN
    ) {
      return json({
        error: {
          message: "Invalid relay access token."
        }
      }, 401, cors);
    }

    // SambaNova APIキーの存在を確認
    if (!env.SAMBANOVA_API_KEY) {
      return json({
        error: {
          message: "SAMBANOVA_API_KEY Worker secret is missing."
        }
      }, 500, cors);
    }

    const declaredLength = Number(
      request.headers.get("Content-Length") || 0
    );

    if (declaredLength > MAX_BODY_BYTES) {
      return json({
        error: {
          message: "Request body exceeds the 12 MB relay limit."
        }
      }, 413, cors);
    }

    let rawBody;
    let payload;

    try {
      // リクエスト全体を読み込んでからモデルを確認する
      rawBody = await request.text();

      const actualBytes =
        new TextEncoder().encode(rawBody).byteLength;

      if (actualBytes > MAX_BODY_BYTES) {
        return json({
          error: {
            message: "Request body exceeds the 12 MB relay limit."
          }
        }, 413, cors);
      }

      payload = JSON.parse(rawBody);

    } catch {
      return json({
        error: {
          message: "Request body must be valid JSON."
        }
      }, 400, cors);
    }

    if (
      !payload ||
      typeof payload !== "object" ||
      Array.isArray(payload) ||
      !Array.isArray(payload.messages)
    ) {
      return json({
        error: {
          message: "Invalid chat-completions request body."
        }
      }, 400, cors);
    }

    // 許可したモデルだけを通す
    if (
      typeof payload.model !== "string" ||
      !ALLOWED_MODELS.has(payload.model)
    ) {
      return json({
        error: {
          message: "Model is not allowed by this relay."
        }
      }, 400, cors);
    }

    try {
      // SambaNovaへ転送
      const upstream = await fetch(UPSTREAM, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${env.SAMBANOVA_API_KEY}`
        },
        body: rawBody
      });

      const responseHeaders = new Headers(cors);

      responseHeaders.set(
        "Content-Type",
        upstream.headers.get("Content-Type") ||
          "application/json; charset=utf-8"
      );

      // 利用枠・待機時間などの情報をアプリへ返す
      for (const name of EXPOSE_HEADERS) {
        const value = upstream.headers.get(name);

        if (value) {
          responseHeaders.set(name, value);
        }
      }

      return new Response(upstream.body, {
        status: upstream.status,
        headers: responseHeaders
      });

    } catch (error) {
      const detail = String(
        error?.message || error
      ).slice(0, 180);

      return json({
        error: {
          message:
            `SambaNova upstream connection failed: ${detail}`
        }
      }, 502, cors);
    }
  }
};
