const FIREBASE_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const FIREBASE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const CLOUDINARY_API_ROOT = "https://api.cloudinary.com/v1_1";
const CLOUDINARY_DELIVERY_ROOT = "https://res.cloudinary.com";
const MAX_JSON_BYTES = 32 * 1024;
const INTENT_COLLECTION = "ChatImageUploads";
const ALLOWED_FORMATS = new Set(["jpg", "jpeg", "png", "webp"]);
const MIME_BY_FORMAT = Object.freeze({
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
});

let cachedFirebaseKeys = null;
let cachedFirebaseKeysUntil = 0;
let cachedAdminToken = null;
let cachedAdminTokenUntil = 0;
let cachedServiceAccount = null;

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export default {
  async fetch(request, env) {
    let cors;
    try {
      cors = corsHeaders(request, env);
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: cors });
      }
      assertConfiguration(env);

      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return jsonResponse({ ok: true }, 200, cors);
      }

      if (request.method === "POST" && url.pathname === "/v1/chat-images/prepare") {
        const caller = await authenticate(request, env);
        const body = await readJson(request);
        const result = await prepareChatImageUpload(caller, body, env);
        return jsonResponse(result, 200, cors);
      }

      if (request.method === "POST" && url.pathname === "/v1/chat-images/finalize") {
        const caller = await authenticate(request, env);
        const body = await readJson(request);
        const result = await finalizeChatImageMessage(caller, body, env);
        return jsonResponse(result, 200, cors);
      }

      const contentMatch = url.pathname.match(/^\/v1\/chat-images\/([A-Za-z0-9_-]{10,128})\/content$/);
      if (request.method === "GET" && contentMatch) {
        const caller = await authenticate(request, env);
        const peerId = requireUid(url.searchParams.get("peerId"), "peerId");
        return await streamPrivateImage(caller, peerId, contentMatch[1], env, cors);
      }

      throw new HttpError(404, "not_found", "Endpoint not found.");
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      const code = error instanceof HttpError ? error.code : "internal_error";
      if (!(error instanceof HttpError)) {
        console.error("Worker request failed", error?.name || "Error", error?.message || "unknown");
      }
      return jsonResponse(
        { error: { code, message: error instanceof HttpError ? error.message : "The request could not be completed." } },
        status,
        cors || new Headers({ "Cache-Control": "no-store" })
      );
    }
  },
};

function assertConfiguration(env) {
  const required = [
    "FIREBASE_PROJECT_ID",
    "FIREBASE_DATABASE_URL",
    "FIREBASE_SERVICE_ACCOUNT_JSON",
    "API_Key",
    "Cloud_Name",
    "CLOUDINARY_API_SECRET",
    "CLOUDINARY_CHAT_UPLOAD_PRESET",
  ];
  const missing = required.filter((name) => !String(env[name] || "").trim());
  if (missing.length) {
    console.error("Missing Worker configuration bindings:", missing.join(", "));
    throw new HttpError(503, "worker_misconfigured", "The image service is not configured.");
  }

  const databaseUrl = new URL(env.FIREBASE_DATABASE_URL);
  if (databaseUrl.protocol !== "https:") {
    throw new HttpError(503, "worker_misconfigured", "The Firebase database URL must use HTTPS.");
  }

  const maxBytes = Number(env.MAX_IMAGE_BYTES || 8 * 1024 * 1024);
  const maxDimension = Number(env.MAX_IMAGE_DIMENSION || 8192);
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > 25 * 1024 * 1024 ||
      !Number.isSafeInteger(maxDimension) || maxDimension < 1 || maxDimension > 12000) {
    throw new HttpError(503, "worker_misconfigured", "Image limits are invalid.");
  }
}

function corsHeaders(request, env) {
  const headers = new Headers({
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Vary": "Origin",
  });
  const origin = request.headers.get("Origin");
  if (!origin) return headers; // Native clients do not need browser CORS headers.

  const allowed = String(env.ALLOWED_ORIGINS || "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  if (!allowed.includes(origin)) {
    throw new HttpError(403, "origin_not_allowed", "This origin is not allowed.");
  }
  headers.set("Access-Control-Allow-Origin", origin);
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  headers.set("Access-Control-Max-Age", "600");
  return headers;
}

function jsonResponse(value, status, headers = new Headers()) {
  const out = new Headers(headers);
  out.set("Content-Type", "application/json; charset=utf-8");
  out.set("Cache-Control", "no-store");
  return new Response(JSON.stringify(value), { status, headers: out });
}

async function readJson(request) {
  const declaredLength = Number(request.headers.get("Content-Length") || 0);
  if (declaredLength > MAX_JSON_BYTES) {
    throw new HttpError(413, "request_too_large", "Request body is too large.");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, "invalid_json", "A JSON request body is required.");

  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_JSON_BYTES) {
        await reader.cancel();
        throw new HttpError(413, "request_too_large", "Request body is too large.");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const body = JSON.parse(new TextDecoder().decode(bytes));
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("not an object");
    return body;
  } catch {
    throw new HttpError(400, "invalid_json", "Request body must be a JSON object.");
  }
}

async function authenticate(request, env) {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer\s+([A-Za-z0-9._~-]+)$/i);
  if (!match) throw new HttpError(401, "unauthenticated", "A Firebase ID token is required.");
  return verifyFirebaseIdToken(match[1], env.FIREBASE_PROJECT_ID);
}

async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new HttpError(401, "invalid_token", "Firebase ID token is invalid.");

  let header;
  let claims;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])));
  } catch {
    throw new HttpError(401, "invalid_token", "Firebase ID token is invalid.");
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    throw new HttpError(401, "invalid_token", "Firebase ID token algorithm is not accepted.");
  }

  const now = Math.floor(Date.now() / 1000);
  const skew = 300;
  if (claims.aud !== projectId ||
      claims.iss !== `https://securetoken.google.com/${projectId}` ||
      typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 128 ||
      !Number.isFinite(claims.exp) || claims.exp <= now ||
      !Number.isFinite(claims.iat) || claims.iat > now + skew ||
      !Number.isFinite(claims.auth_time) || claims.auth_time > now + skew) {
    throw new HttpError(401, "invalid_token", "Firebase ID token claims are invalid or expired.");
  }

  const keys = await getFirebaseSigningKeys();
  const jwk = keys.find((key) => key.kid === header.kid && key.kty === "RSA");
  if (!jwk) {
    cachedFirebaseKeysUntil = 0;
    const refreshed = await getFirebaseSigningKeys(true);
    const refreshedKey = refreshed.find((key) => key.kid === header.kid && key.kty === "RSA");
    if (!refreshedKey) throw new HttpError(401, "invalid_token", "Firebase ID token signing key is unknown.");
    return verifyJwtSignature(parts, refreshedKey, claims.sub);
  }
  return verifyJwtSignature(parts, jwk, claims.sub);
}

async function verifyJwtSignature(parts, jwk, uid) {
  let valid = false;
  try {
    const publicKey = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"]
    );
    valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      publicKey,
      base64UrlDecode(parts[2]),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
    );
  } catch {
    valid = false;
  }
  if (!valid) throw new HttpError(401, "invalid_token", "Firebase ID token signature is invalid.");
  return { uid };
}

async function getFirebaseSigningKeys(forceRefresh = false) {
  if (!forceRefresh && cachedFirebaseKeys && Date.now() < cachedFirebaseKeysUntil) return cachedFirebaseKeys;
  const response = await fetchWithTimeout(FIREBASE_JWKS_URL, { method: "GET" }, 8000);
  if (!response.ok) throw new HttpError(503, "auth_keys_unavailable", "Firebase authentication keys are temporarily unavailable.");
  const body = await response.json();
  if (!Array.isArray(body.keys)) throw new HttpError(503, "auth_keys_unavailable", "Firebase authentication keys are invalid.");
  const cacheControl = response.headers.get("Cache-Control") || "";
  const maxAgeMatch = cacheControl.match(/max-age=(\d+)/i);
  const maxAge = maxAgeMatch ? Number(maxAgeMatch[1]) : 3600;
  cachedFirebaseKeys = body.keys;
  cachedFirebaseKeysUntil = Date.now() + Math.max(60, Math.min(maxAge, 86400)) * 1000;
  return cachedFirebaseKeys;
}

async function prepareChatImageUpload(caller, body, env) {
  const recipientId = requireUid(body.recipientId, "recipientId");
  const messageId = requireMessageId(body.messageId);
  const mimeType = requireMimeType(body.mimeType);
  const fileSizeBytes = requirePositiveInt(body.fileSizeBytes, "fileSizeBytes", 1, maxImageBytes(env));
  const width = requirePositiveInt(body.width, "width", 1, maxImageDimension(env));
  const height = requirePositiveInt(body.height, "height", 1, maxImageDimension(env));
  if (width * height > 40_000_000) {
    throw new HttpError(413, "image_dimensions_exceeded", "Image dimensions exceed the allowed pixel limit.");
  }

  await assertConversationParticipants(caller.uid, recipientId, env);
  const intent = await getOrCreateUploadIntent(caller.uid, recipientId, messageId, {
    mimeType,
    fileSizeBytes,
    width,
    height,
  }, env);
  const publicId = intent.publicId;

  const existingAsset = await getCloudinaryAsset(publicId, env);
  if (existingAsset) {
    validateCloudinaryAsset(existingAsset, intent, env);
    return {
      messageId,
      createdAt: intent.createdAt,
      uploadComplete: true,
      media: mediaMetadata(existingAsset),
    };
  }

  const timestamp = Math.floor(Date.now() / 1000);
  const params = {
    overwrite: "false",
    public_id: publicId,
    timestamp: String(timestamp),
    type: "authenticated",
    upload_preset: env.CLOUDINARY_CHAT_UPLOAD_PRESET,
  };
  const signature = await cloudinaryUploadSignature(params, env.CLOUDINARY_API_SECRET);

  return {
    messageId,
    createdAt: intent.createdAt,
    uploadComplete: false,
    uploadUrl: `https://api.cloudinary.com/v1_1/${encodeURIComponent(env.Cloud_Name)}/image/upload`,
    fields: {
      api_key: env.API_Key,
      ...params,
      signature,
    },
    maxFileSizeBytes: maxImageBytes(env),
    allowedMimeTypes: [...Object.values(MIME_BY_FORMAT).filter((value, index, all) => all.indexOf(value) === index)],
  };
}

async function finalizeChatImageMessage(caller, body, env) {
  const recipientId = requireUid(body.recipientId, "recipientId");
  const messageId = requireMessageId(body.messageId);
  await assertConversationParticipants(caller.uid, recipientId, env);

  const intent = await getUploadIntent(caller.uid, recipientId, messageId, env);
  if (!intent) throw new HttpError(409, "upload_not_prepared", "Prepare this image upload before finalizing it.");

  const asset = await getCloudinaryAsset(intent.publicId, env);
  if (!asset) throw new HttpError(409, "upload_not_found", "The uploaded image was not found. Upload it before finalizing.");
  validateCloudinaryAsset(asset, intent, env);

  const channelId = getChannelId(caller.uid, recipientId);
  const createdAt = intent.createdAt;
  const media = mediaMetadata(asset);
  const message = {
    id: messageId,
    text: "",
    senderId: caller.uid,
    recipientId,
    senderName: await getUserDisplayName(caller.uid, env),
    recipientName: await getUserDisplayName(recipientId, env),
    // Firebase Android Date fields deserialize from epoch milliseconds, not ISO strings.
    date: Date.parse(createdAt),
    type: "IMAGE",
    channelId,
    status: "SENT",
    isRead: false,
    isDelivered: false,
    retryCount: 0,
    lastAttemptTime: 0,
    media,
  };

  await writeMessageIdempotently(message, env);
  await updateChatPreviews(message, env);
  await patchFirestoreDocument(
    `${INTENT_COLLECTION}/${intent.documentId}`,
    { finalized: true, finalizedAt: new Date().toISOString() },
    ["finalized", "finalizedAt"],
    env
  );

  return {
    ok: true,
    messageId,
    status: "SENT",
    type: "IMAGE",
    createdAt,
    media,
    contentPath: `/v1/chat-images/${encodeURIComponent(messageId)}/content?peerId=${encodeURIComponent(recipientId)}`,
  };
}

async function streamPrivateImage(caller, peerId, messageId, env, headers) {
  const channelId = getChannelId(caller.uid, peerId);
  const message = await getRealtimeMessage(caller.uid, channelId, messageId, env);
  if (!message || message.type !== "IMAGE" || !message.media ||
      ![message.senderId, message.recipientId].includes(caller.uid) ||
      ![message.senderId, message.recipientId].includes(peerId)) {
    throw new HttpError(404, "image_not_found", "Image message was not found.");
  }

  const expectedId = await cloudinaryPublicId(message.senderId, message.recipientId, messageId, env);
  if (message.media.publicId !== expectedId || message.media.deliveryType !== "authenticated") {
    throw new HttpError(404, "image_not_found", "Image message was not found.");
  }
  const format = String(message.media.format || "").toLowerCase();
  if (!ALLOWED_FORMATS.has(format)) throw new HttpError(415, "unsupported_media", "This image format is not supported.");

  const deliveryUrl = await cloudinaryAuthenticatedUrl(message.media, env);
  const remote = await fetchWithTimeout(deliveryUrl, { method: "GET", redirect: "follow" }, 15000);
  if (!remote.ok || !remote.body) {
    console.warn("Cloudinary image delivery failed", remote.status, messageId);
    throw new HttpError(remote.status === 404 ? 404 : 502, "image_delivery_failed", "The image could not be loaded.");
  }
  const remoteLength = Number(remote.headers.get("Content-Length") || 0);
  if (remoteLength > maxImageBytes(env)) {
    await remote.body.cancel();
    throw new HttpError(413, "image_too_large", "The stored image exceeds the configured limit.");
  }

  const out = new Headers(headers);
  out.set("Content-Type", MIME_BY_FORMAT[format]);
  out.set("Cache-Control", "private, no-store, max-age=0");
  out.set("Content-Disposition", "inline");
  out.set("X-Content-Type-Options", "nosniff");
  return new Response(remote.body, { status: 200, headers: out });
}

async function assertConversationParticipants(senderId, recipientId, env) {
  if (senderId === recipientId) throw new HttpError(400, "invalid_recipient", "A user cannot send an image to themselves.");
  const [senderFriend, recipientFriend] = await Promise.all([
    getFirestoreDocument(`Users/${senderId}/Friends/${recipientId}`, env),
    getFirestoreDocument(`Users/${recipientId}/Friends/${senderId}`, env),
  ]);
  if (!senderFriend || !recipientFriend) {
    const [senderDoc, recipientDoc] = await Promise.all([
      getFirestoreDocument(`Users/${senderId}`, env),
      getFirestoreDocument(`Users/${recipientId}`, env),
    ]);
    const senderFriends = arrayField(senderDoc?.fields, "friends");
    const recipientFriends = arrayField(recipientDoc?.fields, "friends");
    if (!senderFriends.includes(recipientId) || !recipientFriends.includes(senderId)) {
      throw new HttpError(403, "not_conversation_participants", "Only confirmed friends can exchange chat images.");
    }
  }
}

async function getOrCreateUploadIntent(senderId, recipientId, messageId, input, env) {
  const documentId = await sha256Hex(`${senderId}\n${recipientId}\n${messageId}`);
  const path = `${INTENT_COLLECTION}/${documentId}`;
  const existing = await getFirestoreDocument(path, env);
  const publicId = await cloudinaryPublicId(senderId, recipientId, messageId, env);
  if (existing) {
    const fields = existing.fields || {};
    const sameRequest = stringField(fields, "senderId") === senderId &&
      stringField(fields, "recipientId") === recipientId &&
      stringField(fields, "messageId") === messageId &&
      stringField(fields, "publicId") === publicId &&
      stringField(fields, "mimeType") === input.mimeType &&
      integerField(fields, "fileSizeBytes") === input.fileSizeBytes;
    if (!sameRequest) throw new HttpError(409, "message_id_conflict", "This message ID is already reserved for different upload data.");
    const createdAt = timestampField(fields, "createdAt");
    return { documentId, publicId, createdAt };
  }

  const createdAt = new Date().toISOString();
  const fields = {
    senderId: { stringValue: senderId },
    recipientId: { stringValue: recipientId },
    messageId: { stringValue: messageId },
    publicId: { stringValue: publicId },
    mimeType: { stringValue: input.mimeType },
    fileSizeBytes: { integerValue: String(input.fileSizeBytes) },
    width: { integerValue: String(input.width) },
    height: { integerValue: String(input.height) },
    createdAt: { timestampValue: createdAt },
    finalized: { booleanValue: false },
  };
  const created = await createFirestoreDocument(INTENT_COLLECTION, documentId, fields, env);
  if (created) return { documentId, publicId, createdAt };

  // Concurrent prepare requests for the same ID converge on the first intent.
  const raced = await getFirestoreDocument(path, env);
  if (!raced) throw new HttpError(503, "upload_intent_unavailable", "The upload could not be prepared.");
  const racedFields = raced.fields || {};
  if (stringField(racedFields, "senderId") !== senderId ||
      stringField(racedFields, "recipientId") !== recipientId ||
      stringField(racedFields, "publicId") !== publicId ||
      stringField(racedFields, "mimeType") !== input.mimeType ||
      integerField(racedFields, "fileSizeBytes") !== input.fileSizeBytes) {
    throw new HttpError(409, "message_id_conflict", "This message ID is already reserved for different upload data.");
  }
  return { documentId, publicId, createdAt: timestampField(racedFields, "createdAt") };
}

async function getUploadIntent(senderId, recipientId, messageId, env) {
  const documentId = await sha256Hex(`${senderId}\n${recipientId}\n${messageId}`);
  const doc = await getFirestoreDocument(`${INTENT_COLLECTION}/${documentId}`, env);
  if (!doc) return null;
  const fields = doc.fields || {};
  if (stringField(fields, "senderId") !== senderId ||
      stringField(fields, "recipientId") !== recipientId ||
      stringField(fields, "messageId") !== messageId) {
    throw new HttpError(409, "upload_intent_conflict", "The upload reservation does not match this request.");
  }
  return {
    documentId,
    publicId: stringField(fields, "publicId"),
    createdAt: timestampField(fields, "createdAt"),
    mimeType: stringField(fields, "mimeType"),
    fileSizeBytes: integerField(fields, "fileSizeBytes"),
    width: integerField(fields, "width"),
    height: integerField(fields, "height"),
  };
}

async function writeMessageIdempotently(message, env) {
  const [senderCopy, recipientCopy] = await Promise.all([
    getRealtimeMessage(message.senderId, message.channelId, message.id, env),
    getRealtimeMessage(message.recipientId, message.channelId, message.id, env),
  ]);
  for (const existing of [senderCopy, recipientCopy]) {
    if (existing && !sameImageMessage(existing, message)) {
      throw new HttpError(409, "message_id_conflict", "This message ID already belongs to a different message.");
    }
  }

  // A root PATCH with relative paths is an atomic multi-location update in Realtime Database.
  const updates = {};
  updates[`Sender: ${message.senderId}/ChannelID: ${message.channelId}/Messages/${message.id}`] = message;
  updates[`Sender: ${message.recipientId}/ChannelID: ${message.channelId}/Messages/${message.id}`] = message;
  await firebaseRealtimeRequest("/Message.json", env, { method: "PATCH", body: JSON.stringify(updates) });
}

function sameImageMessage(a, b) {
  return a.id === b.id && a.senderId === b.senderId && a.recipientId === b.recipientId &&
    a.type === "IMAGE" && a.media?.publicId === b.media.publicId;
}

async function updateChatPreviews(message, env) {
  const firebaseToken = await getFirebaseAdminAccessToken(env);
  const senderProfile = await getFirestoreDocument(`Users/${message.senderId}`, env, firebaseToken);
  const recipientProfile = await getFirestoreDocument(`Users/${message.recipientId}`, env, firebaseToken);
  const senderName = profileDisplayName(senderProfile, message.senderName);
  const recipientName = profileDisplayName(recipientProfile, message.recipientName);
  const senderImage = profileImage(senderProfile);
  const recipientImage = profileImage(recipientProfile);
  const timestamp = new Date(message.date).toISOString();

  const senderPath = `Users/${message.senderId}/chat channel/${message.channelId}`;
  const recipientPath = `Users/${message.recipientId}/chat channel/${message.channelId}`;
  const fieldsets = [
    {
      path: senderPath,
      fields: previewFields(message.recipientId, recipientName, recipientImage, "Photo", message.senderId, timestamp),
    },
    {
      path: recipientPath,
      fields: previewFields(message.senderId, senderName, senderImage, "Photo", message.senderId, timestamp),
    },
  ];

  for (let attempt = 0; attempt < 3; attempt++) {
    const transaction = await beginFirestoreTransaction(env, firebaseToken);
    try {
      const docs = await Promise.all(fieldsets.map(({ path }) => getFirestoreDocument(path, env, firebaseToken, transaction)));
      const writes = [];
      for (let index = 0; index < fieldsets.length; index++) {
        const currentDate = timestampField(docs[index]?.fields || {}, "date");
        if (!currentDate || Date.parse(timestamp) >= Date.parse(currentDate)) {
          writes.push({ path: fieldsets[index].path, fields: fieldsets[index].fields });
        }
      }
      if (writes.length === 0) {
        await commitFirestoreTransaction(env, firebaseToken, transaction, []);
        return;
      }
      await commitFirestoreTransaction(env, firebaseToken, transaction, writes);
      return;
    } catch (error) {
      if (attempt === 2 || !(error instanceof HttpError) || error.status !== 409) throw error;
    }
  }
}

function previewFields(otherId, otherName, otherImage, lastMessage, senderId, timestamp) {
  return {
    recipientId: { stringValue: otherId },
    recipientName: { stringValue: otherName },
    profileImg: otherImage ? { stringValue: otherImage } : { nullValue: null },
    lastMessage: { stringValue: lastMessage },
    lastMessageSenderId: { stringValue: senderId },
    lastMessageRead: { booleanValue: false },
    date: { timestampValue: timestamp },
    isOnline: { booleanValue: false },
  };
}

async function beginFirestoreTransaction(env, token) {
  const response = await fetchWithTimeout(firestoreBase(env) + ":beginTransaction", {
    method: "POST",
    headers: firebaseHeaders(token),
    body: JSON.stringify({ options: { readWrite: {} } }),
  }, 12000);
  const body = await safeJson(response);
  if (!response.ok || !body.transaction) throw upstreamError("firestore_transaction_failed", response.status, body);
  return body.transaction;
}

async function commitFirestoreTransaction(env, token, transaction, writes) {
  if (writes.length === 0) {
    const response = await fetchWithTimeout(firestoreBase(env) + ":rollback", {
      method: "POST",
      headers: firebaseHeaders(token),
      body: JSON.stringify({ transaction }),
    }, 12000);
    if (!response.ok && response.status !== 404) throw upstreamError("firestore_transaction_failed", response.status, await safeJson(response));
    return;
  }
  const updateWrites = writes.map(({ path, fields }) => ({
    update: { name: firestoreDocumentName(env, path), fields },
    updateMask: { fieldPaths: Object.keys(fields) },
  }));
  const response = await fetchWithTimeout(firestoreBase(env) + ":commit", {
    method: "POST",
    headers: firebaseHeaders(token),
    body: JSON.stringify({ transaction, writes: updateWrites }),
  }, 12000);
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("firestore_commit_failed", response.status, body);
}

async function getFirestoreDocument(path, env, token = null, transaction = null) {
  const accessToken = token || await getFirebaseAdminAccessToken(env);
  const url = new URL(firestoreDocumentUrl(env, path));
  if (transaction) url.searchParams.set("transaction", transaction);
  const response = await fetchWithTimeout(url, {
    method: "GET",
    headers: firebaseHeaders(accessToken),
  }, 12000);
  if (response.status === 404) return null;
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("firestore_read_failed", response.status, body);
  return body;
}

async function createFirestoreDocument(collectionPath, documentId, fields, env) {
  const token = await getFirebaseAdminAccessToken(env);
  const url = `${firestoreCollectionUrl(env, collectionPath)}?documentId=${encodeURIComponent(documentId)}`;
  const response = await fetchWithTimeout(url, {
    method: "POST",
    headers: firebaseHeaders(token),
    body: JSON.stringify({ fields }),
  }, 12000);
  if (response.status === 409) return false;
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("firestore_write_failed", response.status, body);
  return true;
}

async function patchFirestoreDocument(path, nativeFields, fieldMask, env) {
  const token = await getFirebaseAdminAccessToken(env);
  const fields = {};
  for (const [name, value] of Object.entries(nativeFields)) {
    if (typeof value === "boolean") fields[name] = { booleanValue: value };
    else if (typeof value === "number") fields[name] = { integerValue: String(value) };
    else fields[name] = { stringValue: String(value) };
  }
  const url = new URL(firestoreDocumentUrl(env, path));
  for (const name of fieldMask) url.searchParams.append("updateMask.fieldPaths", name);
  const response = await fetchWithTimeout(url, {
    method: "PATCH",
    headers: firebaseHeaders(token),
    body: JSON.stringify({ fields }),
  }, 12000);
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("firestore_write_failed", response.status, body);
}

async function getUserDisplayName(uid, env) {
  return profileDisplayName(await getFirestoreDocument(`Users/${uid}`, env), "MoonChat User");
}

function profileDisplayName(document, fallback) {
  const fields = document?.fields || {};
  return stringField(fields, "displayName") || stringField(fields, "username") || stringField(fields, "name") || fallback || "MoonChat User";
}

function profileImage(document) {
  const fields = document?.fields || {};
  return stringField(fields, "profileImg") || stringField(fields, "pfp") || null;
}

function firestoreBase(env) {
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(env.FIREBASE_PROJECT_ID)}/databases/%28default%29/documents`;
}

function firestoreDocumentName(env, path) {
  return `projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/${path}`;
}

function firestoreDocumentUrl(env, path) {
  const encoded = path.split("/").map(encodeURIComponent).join("/");
  return `${firestoreBase(env)}/${encoded}`;
}

function firestoreCollectionUrl(env, path) {
  const encoded = path.split("/").map(encodeURIComponent).join("/");
  return `${firestoreBase(env)}/${encoded}`;
}

async function firebaseRealtimeRequest(path, env, options) {
  const token = await getFirebaseAdminAccessToken(env);
  const base = env.FIREBASE_DATABASE_URL.replace(/\/+$/, "");
  const response = await fetchWithTimeout(`${base}${path}`, {
    ...options,
    headers: {
      ...firebaseHeaders(token),
      ...(options.headers || {}),
    },
  }, 15000);
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("firebase_database_failed", response.status, body);
  return body;
}

async function getRealtimeMessage(ownerId, channelId, messageId, env) {
  const path = [
    "Message",
    `Sender: ${ownerId}`,
    `ChannelID: ${channelId}`,
    "Messages",
    messageId,
  ].map((segment) => encodeURIComponent(segment)).join("/");
  return await firebaseRealtimeRequest(`/${path}.json`, env, { method: "GET" });
}

async function getFirebaseAdminAccessToken(env) {
  if (cachedAdminToken && Date.now() < cachedAdminTokenUntil - 60_000) return cachedAdminToken;
  const serviceAccount = getServiceAccount(env);
  const now = Math.floor(Date.now() / 1000);
  const assertion = await createServiceAccountAssertion(serviceAccount, now);
  const response = await fetchWithTimeout(FIREBASE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  }, 12000);
  const body = await safeJson(response);
  if (!response.ok || !body.access_token) {
    const detail = body?.error_description || body?.error || "";
    console.error("Firebase admin auth failed", response.status, detail);
    const detailMsg = detail ? `: ${detail}` : "";
    throw new HttpError(503, "firebase_admin_auth_failed", `Firebase backend authentication is unavailable (${response.status}${detailMsg}).`);
  }
  cachedAdminToken = body.access_token;
  cachedAdminTokenUntil = Date.now() + Math.min(Number(body.expires_in || 3600), 3600) * 1000;
  return cachedAdminToken;
}

function getServiceAccount(env) {
  if (cachedServiceAccount) return cachedServiceAccount;
  let account;
  try {
    account = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  } catch {
    throw new HttpError(503, "worker_misconfigured", "Firebase service-account configuration is invalid.");
  }
  if (!account.client_email || !account.private_key ||
      account.project_id !== env.FIREBASE_PROJECT_ID ||
      !/^https:\/\/oauth2\.googleapis\.com\/token$/.test(account.token_uri || "")) {
    throw new HttpError(503, "worker_misconfigured", "Firebase service-account configuration does not match this project.");
  }
  cachedServiceAccount = account;
  return account;
}

async function createServiceAccountAssertion(account, nowSeconds) {
  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64UrlEncode(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/datastore",
    aud: account.token_uri,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  }));
  const input = `${header}.${claims}`;
  const pem = account.private_key.replace(/\\n/g, "\n");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(pem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(input));
  return `${input}.${base64UrlEncodeBytes(new Uint8Array(signature))}`;
}

async function getCloudinaryAsset(publicId, env) {
  const encodedPublicId = publicId.split("/").map(encodeURIComponent).join("/");
  const url = `${CLOUDINARY_API_ROOT}/${encodeURIComponent(env.Cloud_Name)}/resources/image/authenticated/${encodedPublicId}`;
  const authorization = `Basic ${btoa(`${env.API_Key}:${env.CLOUDINARY_API_SECRET}`)}`;
  const response = await fetchWithTimeout(url, {
    method: "GET",
    headers: { Authorization: authorization, Accept: "application/json" },
  }, 15000);
  if (response.status === 404) return null;
  const body = await safeJson(response);
  if (!response.ok) throw upstreamError("cloudinary_asset_lookup_failed", response.status, body);
  return body;
}

function validateCloudinaryAsset(asset, intent, env) {
  const format = String(asset.format || "").toLowerCase();
  const maxBytes = maxImageBytes(env);
  const maxDimension = maxImageDimension(env);
  const width = Number(asset.width);
  const height = Number(asset.height);
  const bytes = Number(asset.bytes);
  if (asset.type !== "authenticated" || asset.resource_type !== "image" ||
      !ALLOWED_FORMATS.has(format) || !Number.isSafeInteger(bytes) || bytes < 1 || bytes > maxBytes ||
      !Number.isSafeInteger(width) || !Number.isSafeInteger(height) ||
      width < 1 || height < 1 || width > maxDimension || height > maxDimension || width * height > 40_000_000) {
    throw new HttpError(422, "uploaded_asset_rejected", "The uploaded asset does not satisfy the image policy.");
  }
  const actualMime = MIME_BY_FORMAT[format];
  if (intent.mimeType && intent.mimeType !== actualMime) {
    throw new HttpError(422, "mime_type_mismatch", "The uploaded image type does not match the prepared upload.");
  }
}

function mediaMetadata(asset) {
  const format = String(asset.format).toLowerCase();
  return {
    publicId: asset.public_id,
    assetId: asset.asset_id,
    version: Number(asset.version),
    resourceType: "image",
    deliveryType: "authenticated",
    format,
    mimeType: MIME_BY_FORMAT[format],
    width: Number(asset.width),
    height: Number(asset.height),
    bytes: Number(asset.bytes),
  };
}

async function cloudinaryUploadSignature(params, apiSecret) {
  const serialized = Object.keys(params)
    .filter((key) => params[key] !== undefined && params[key] !== null && params[key] !== "")
    .sort()
    .map((key) => `${key}=${params[key]}`)
    .join("&");
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(serialized + apiSecret));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function cloudinaryAuthenticatedUrl(media, env) {
  const format = String(media.format).toLowerCase();
  const publicId = String(media.publicId);
  const version = Number(media.version);
  if (!ALLOWED_FORMATS.has(format) || !Number.isSafeInteger(version) || version <= 0 ||
      !/^mc_[a-f0-9]{64}$/.test(publicId)) {
    throw new HttpError(500, "invalid_media_record", "The stored image reference is invalid.");
  }
  const pathToSign = `v${version}/${publicId}.${format}`;
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(pathToSign + env.CLOUDINARY_API_SECRET));
  const signature = `s--${base64UrlEncodeBytes(new Uint8Array(digest)).slice(0, 8)}--`;
  return `${CLOUDINARY_DELIVERY_ROOT}/${encodeURIComponent(env.Cloud_Name)}/image/authenticated/${signature}/v${version}/${publicId}.${format}`;
}

async function cloudinaryPublicId(senderId, recipientId, messageId, env) {
  return `mc_${await sha256Hex(`${env.FIREBASE_PROJECT_ID}\n${senderId}\n${recipientId}\n${messageId}`)}`;
}

function getChannelId(userId1, userId2) {
  return userId1 < userId2 ? `${userId1}_${userId2}` : `${userId2}_${userId1}`;
}

function requireUid(value, name) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw new HttpError(400, "invalid_input", `${name} is invalid.`);
  }
  return value;
}

function requireMessageId(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{10,128}$/.test(value)) {
    throw new HttpError(400, "invalid_input", "messageId is invalid.");
  }
  return value;
}

function requireMimeType(value) {
  if (typeof value !== "string" || !Object.values(MIME_BY_FORMAT).includes(value.toLowerCase())) {
    throw new HttpError(415, "unsupported_media", "Only JPEG, PNG, and WebP images are accepted.");
  }
  return value.toLowerCase();
}

function requirePositiveInt(value, name, min, max) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
    throw new HttpError(400, "invalid_input", `${name} is outside the allowed range.`);
  }
  return parsed;
}

function maxImageBytes(env) {
  return Number(env.MAX_IMAGE_BYTES || 8 * 1024 * 1024);
}

function maxImageDimension(env) {
  return Number(env.MAX_IMAGE_DIMENSION || 8192);
}

function stringField(fields, name) {
  const value = fields?.[name];
  if (typeof value?.stringValue === "string") return value.stringValue;
  return "";
}

function integerField(fields, name) {
  const raw = fields?.[name]?.integerValue;
  const value = raw === undefined ? NaN : Number(raw);
  return Number.isSafeInteger(value) ? value : 0;
}

function timestampField(fields, name) {
  const value = fields?.[name]?.timestampValue;
  return typeof value === "string" ? value : "";
}

function arrayField(fields, name) {
  const values = fields?.[name]?.arrayValue?.values;
  if (!Array.isArray(values)) return [];
  return values.map((item) => item?.stringValue).filter((val) => typeof val === "string");
}

function firebaseHeaders(accessToken) {
  return {
    Authorization: `Bearer ${accessToken}`,
    "Content-Type": "application/json",
    Accept: "application/json",
  };
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return {};
  }
}

function upstreamError(code, status, body) {
  if (status === 409 || body?.error?.status === "ABORTED") {
    return new HttpError(409, code, "A concurrent update occurred; retry the request.");
  }
  const detail = body?.error?.message || body?.message || body?.error || "";
  console.error("Upstream service error", code, status, body?.error?.status || "unknown", detail);
  const detailMsg = detail ? `: ${detail}` : "";
  return new HttpError(503, code, `A required backend service is temporarily unavailable (${code}${detailMsg}).`);
}

async function fetchWithTimeout(input, init, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort("timeout"), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } catch (error) {
    if (error?.name === "AbortError") throw new HttpError(503, "upstream_timeout", "A required service timed out; retry shortly.");
    throw new HttpError(503, "upstream_unavailable", "A required service is temporarily unavailable.");
  } finally {
    clearTimeout(timer);
  }
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function pemToArrayBuffer(pem) {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes.buffer;
}

function base64UrlDecode(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function base64UrlEncode(value) {
  return base64UrlEncodeBytes(new TextEncoder().encode(value));
}

function base64UrlEncodeBytes(bytes) {
  let binary = "";
  for (let index = 0; index < bytes.length; index++) binary += String.fromCharCode(bytes[index]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
