import { createClient } from "npm:@supabase/supabase-js@2";
import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  ListObjectsV2Command,
  S3Client,
} from "npm:@aws-sdk/client-s3@3";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(status: number, payload: Record<string, unknown>) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function env(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Falta configurar el secreto ${name}`);
  return value;
}

function cleanKey(value: unknown): string {
  const key = String(value ?? "").trim().replace(/^\/+/, "");
  if (!key || key.includes("..") || key.includes("\\")) throw new Error("storage_key inválida");
  return key;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json(405, { ok: false, error: "Método no permitido" });

  try {
    const authorization = req.headers.get("Authorization") ?? "";
    if (!authorization.startsWith("Bearer ")) return json(401, { ok: false, error: "Sesión requerida" });

    const supabaseUrl = env("SUPABASE_URL");
    const anonKey = env("SUPABASE_ANON_KEY");
    const supabase = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false },
    });
    const { data: userData, error: userError } = await supabase.auth.getUser();
    if (userError || !userData.user) return json(401, { ok: false, error: "Sesión inválida" });
    const user = userData.user;

    const accountId = env("R2_ACCOUNT_ID");
    const accessKeyId = env("R2_ACCESS_KEY_ID");
    const secretAccessKey = env("R2_SECRET_ACCESS_KEY");
    const bucket = env("R2_BUCKET");
    const endpoint = Deno.env.get("R2_ENDPOINT")?.trim() || `https://${accountId}.r2.cloudflarestorage.com`;
    const allowedPrefix = (Deno.env.get("R2_ALLOWED_PREFIX")?.trim() || "skc").replace(/^\/+|\/+$/g, "") + "/";

    const r2 = new S3Client({
      region: "auto",
      endpoint,
      credentials: { accessKeyId, secretAccessKey },
    });

    const body = await req.json().catch(() => ({}));
    const action = String(body.action ?? "");

    const { data: adminValue } = await supabase.rpc("skc_is_admin");
    const isAdmin = adminValue === true;

    const canReadKey = async (key: string): Promise<boolean> => {
      if (key.startsWith(`${allowedPrefix}${user.id}/`)) return true;
      if (key.includes("/excel-templates/")) return isAdmin;

      let from = 0;
      const page = 500;
      while (true) {
        const { data, error } = await supabase
          .from("skc_events")
          .select("payload")
          .in("entity_type", ["transactions", "messages"])
          .range(from, from + page - 1);
        if (error) throw error;
        const rows = data ?? [];
        for (const row of rows) {
          const attachments = Array.isArray(row.payload?.attachments) ? row.payload.attachments : [];
          if (attachments.some((a: Record<string, unknown>) => String(a.storageKey ?? a.remotePath ?? "") === key)) return true;
        }
        if (rows.length < page) break;
        from += page;
      }
      return false;
    };

    if (action === "test") {
      if (!isAdmin) return json(403, { ok: false, error: "Solo ADMIN puede probar R2" });
      const key = `${allowedPrefix}${user.id}/_connection_test/${crypto.randomUUID()}.txt`;
      const content = new TextEncoder().encode("SKC R2 connection test");
      let writeOk = false;
      let readOk = false;
      try {
        await r2.send(new PutObjectCommand({ Bucket: bucket, Key: key, Body: content, ContentType: "text/plain" }));
        writeOk = true;
        const result = await r2.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
        const text = await result.Body?.transformToString();
        readOk = text === "SKC R2 connection test";
      } finally {
        await r2.send(new DeleteObjectCommand({ Bucket: bucket, Key: key })).catch(() => undefined);
      }
      return json(200, { ok: writeOk && readOk, message: "Cloudflare R2 conectado", bucket, writeOk, readOk });
    }

    if (action === "list") {
      if (!isAdmin) return json(403, { ok: false, error: "Solo ADMIN puede listar el almacenamiento completo" });
      const requestedPrefix = String(body.prefix ?? "").trim().replace(/^\/+|\/+$/g, "");
      const prefix = requestedPrefix
        ? `${allowedPrefix}${requestedPrefix.startsWith(allowedPrefix) ? requestedPrefix.slice(allowedPrefix.length) : requestedPrefix}`.replace(/\/+/g, "/")
        : allowedPrefix;
      if (!prefix.startsWith(allowedPrefix)) return json(403, { ok: false, error: "Prefijo fuera del espacio SKC" });
      const maxKeys = Math.min(Math.max(Number(body.maxKeys ?? 1000), 1), 1000);
      const continuationToken = String(body.continuationToken ?? "").trim() || undefined;
      const result = await r2.send(new ListObjectsV2Command({
        Bucket: bucket,
        Prefix: prefix,
        MaxKeys: maxKeys,
        ContinuationToken: continuationToken,
      }));
      return json(200, {
        ok: true,
        bucket,
        objects: (result.Contents ?? []).map((item) => ({
          key: item.Key ?? "",
          sizeBytes: item.Size ?? 0,
          objectId: item.ETag ?? item.Key ?? "",
        })),
        nextContinuationToken: result.NextContinuationToken ?? "",
      });
    }

    const key = cleanKey(body.key);
    if (!key.startsWith(allowedPrefix)) return json(403, { ok: false, error: "La ruta está fuera del prefijo permitido" });

    if (action === "presignUpload") {
      if (!key.startsWith(`${allowedPrefix}${user.id}/`)) return json(403, { ok: false, error: "Solo puedes subir archivos bajo tu identidad autenticada" });
      const mimeType = String(body.mimeType ?? "application/octet-stream").trim().toLowerCase();
      const allowedMime = new Set([
        "application/pdf", "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip", "application/octet-stream"
      ]);
      if (!allowedMime.has(mimeType)) return json(400, { ok: false, error: "Tipo de archivo no permitido" });
      const sizeBytes = Number(body.sizeBytes ?? 0);
      if (!Number.isFinite(sizeBytes) || sizeBytes <= 0 || sizeBytes > 50 * 1024 * 1024) return json(400, { ok: false, error: "Tamaño de archivo no permitido" });
      const checksum = String(body.checksum ?? "").trim().toLowerCase();
      if (checksum && !/^[0-9a-f]{64}$/.test(checksum)) return json(400, { ok: false, error: "Checksum SHA-256 inválido" });

      // 3.15.2: no adjuntar Metadata al PutObject prefirmado. AWS SDK v3 puede
      // convertir x-amz-meta-* en parámetros de la URL; si Android vuelve a enviar
      // el mismo valor como header, R2 calcula otra firma y responde 403
      // SignatureDoesNotMatch. Content-Type sí se firma de forma explícita y el
      // cliente recibe exactamente ese único header obligatorio.
      const command = new PutObjectCommand({
        Bucket: bucket,
        Key: key,
        ContentType: mimeType,
      });
      const url = await getSignedUrl(r2, command, {
        expiresIn: 600,
        signableHeaders: new Set(["content-type"]),
      });
      const headers: Record<string, string> = { "Content-Type": mimeType };
      return json(200, { ok: true, url, headers, expiresIn: 600, bucket, expectedChecksum: checksum });
    }

    const allowed = await canReadKey(key);
    if (!allowed) return json(403, { ok: false, error: "No tienes permiso para este archivo" });

    if (action === "presignDownload") {
      const url = await getSignedUrl(r2, new GetObjectCommand({ Bucket: bucket, Key: key }), { expiresIn: 300 });
      return json(200, { ok: true, url, expiresIn: 300, bucket });
    }

    if (action === "head") {
      const head = await r2.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
      return json(200, {
        ok: true,
        bucket,
        sizeBytes: head.ContentLength ?? 0,
        mimeType: head.ContentType ?? "",
        checksum: head.Metadata?.sha256 ?? "",
        objectId: key,
        etag: head.ETag ?? "",
      });
    }

    if (action === "delete") {
      if (!key.startsWith(`${allowedPrefix}${user.id}/`) && !isAdmin) return json(403, { ok: false, error: "No tienes permiso para eliminar este archivo" });
      await r2.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
      return json(200, { ok: true, bucket });
    }

    return json(400, { ok: false, error: "Acción desconocida" });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return json(500, { ok: false, error: message });
  }
});
