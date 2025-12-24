
# WhatsApp-Style Chat Application – High-Level Design (HLD)

## Overview
A real-time chat application enabling communication between two or more users. Supports **text and image messages**, **group chats**, and **offline message delivery**.

---

## Functional Requirements (MVP)
- Send messages (Text, Image)
- Receive messages (Text, Image)
- Offline message delivery (Inbox mechanism)
- View conversation history

---

## Non-Functional Requirements
- **Message ordering**: Receiver sees messages in the same order as sent.
- **Consistency vs Availability**:
  - High availability is critical.
  - Illusion of consistency: allow slight delay (~5s) but maintain order.
- **Ephemeral storage**: Messages should not persist longer than necessary.

---

## Scale Estimation
- **Users**: ~4B (80% of 5B internet devices)
- **DAU**: ~4B
- **Messages/day**: 4B users × 100 msgs = **400B messages/day**
- **Message size**: ~1KB → **400TB/day**
- **User profile size**: ~500 bytes → ~2TB total

---

## Data Model
### User
```
user_id (8 bytes)
user_name (≤50 chars)
profile_image (S3 URL)
description (≤50 chars)
```

### Message
```
message_id (64-bit)
sender_id
receiver_id
content (≤500 chars)
attachment_url (optional)
```

### Conversation
```
conversation_id
user_id
message_id
```

---

## Sharding Strategy
- **Shard by user_id** for WhatsApp-scale (groups ≤200 members).
- For enterprise-scale (Teams-like, 500+ members), shard by group_id.
- Ensure **idempotent sendMessage API** and **message chaining** for order.

---

## Throughput & QPS
- **sendMessage API calls/day**: 400B
- **QPS**: ~4M/sec (peak ~8M/sec)
- Heavy **read/write** → avoid polling → use **push model**.

---

## WebSocket for Real-Time Delivery
- Persistent connection for push notifications.
- Maintain **session manager**:
```
user_id → client_id → session_id
```
- If recipient offline → store in **Inbox**:
```
inbox_id, message_id, sender_id, status
```
- On reconnect → pull undelivered messages → delete after ACK.

---

## Illusion of Consistency
- Write to **recipient shard first** for quick delivery.
- Async write to sender shard; sender UI caches message until confirmed.

---

## Media Handling
- **Do NOT store blobs in DB**.
- Use **pre-signed URLs** for client-side upload/download to object storage (S3/GCS).
- Store only reference in DB.

---

## High Throughput Strategies
- **Scaling servers behind LB alone won’t work** (fan-out problem).
- **Kafka per user?** Not feasible (4B topics).
- **Better approach**:
  - L7 LB with consistent hashing for sticky sessions.
  - Redis Pub/Sub for lightweight fan-out.

---

## Redis Pub/Sub Flow
**A) User A connects**
- WebSocket → Chat Server → Auth → Compute shard → SUBSCRIBE `user:{A}` in Redis.
- Mark presence with TTL heartbeat.

**B) User B sends message to A**
- Persist to Inbox DB (A’s shard).
- PUBLISH `user:{A}` in Redis.
- Chat Server fetches message → push via WebSocket → ACK → mark delivered.

**C) If A offline**
- Pub/Sub notification dropped.
- Message stays in Inbox until reconnect.

---

## Architecture Diagram (Mermaid)
```mermaid
flowchart LR
    Client --> L4LB[Edge L4 Load Balancer]
    L4LB --> GW[WebSocket Gateways]
    GW --> SR[Shard Router]
    SR --> S1[Shard 1 - App Servers]
    SR --> S2[Shard 2 - App Servers]
    SR --> S3[Shard 3 - App Servers]
    S1 --> R1[(Redis - presence/pubsub)]
    S2 --> R2[(Redis - presence/pubsub)]
    S3 --> R3[(Redis - presence/pubsub)]
    S1 --> DB1[(Messages Store)]
    S2 --> DB2[(Messages Store)]
    S3 --> DB3[(Messages Store)]
    S1 --> BUS[(Kafka/Event Bus)]
    S2 --> BUS
    S3 --> BUS
    BUS --> GF[Group Fanout Service]
    GF --> S1
    GF --> S2
    GF --> S3
```

---

## Sequence: Send & Deliver Message (Mermaid)
```mermaid
sequenceDiagram
    participant C as Client (Sender)
    participant G as WebSocket Gateway
    participant SR as Shard Router
    participant SA as Shard App Server (Recipient shard)
    participant R as Redis (Recipient shard)
    participant DB as Inbox DB (Recipient shard)
    participant CR as Client (Recipient)

    C->>G: WS connect + Auth (token with user_id)
    G->>SR: route by ConsistentHash(user_id)
    SR->>SA: proxy WS stream

    C->>SA: sendMessage(B -> A, content)
    SA->>DB: Persist message + Inbox record (undelivered)
    SA->>R: PUBLISH user:{A}, messageId

    Note over CR: If online and subscribed
    R-->>SA: messageId
    SA->>CR: push message via WS
    CR-->>SA: ACK
    SA->>DB: mark Inbox delivered

    Note over CR: If offline
    R--xSA: (no subscriber)
    DB-->CR: delivered on reconnect
```

---

## Operational Considerations
- **Consistent hashing ring** with virtual nodes; minimal user movement on scale changes.
- **Service discovery / shard map** via etcd/Consul.
- **Health checks & drain** for graceful failover.
- **Observability**: OpenTelemetry traces; Prometheus metrics; Grafana dashboards.
- **Multi-region**: Anycast → regional L4 → gateways; active-active shards; async replication.

---

## Suggested Tech Stack
- **Load Balancer**: Envoy / HAProxy / AWS NLB
- **Gateway**: Envoy/NGINX (WebSocket-aware)
- **Shard Router**: Envoy/Service mesh or custom proxy
- **DB**: Cassandra or MySQL shards
- **Cache/Presence**: Redis
- **Event Bus**: Kafka (inter-shard/group fanout)
- **Object Storage**: S3/GCS/MinIO (pre-signed URLs)

---

## API & Idempotency Notes
- `sendMessage` must be **idempotent** (dedupe by client message UUID).
- **Message chaining** (prev_msg_id) ensures client-side ordered rendering.

---

## Security
- TLS everywhere; JWT with short-lived tokens.
- Rate limiting, WAF at edge; replay protection for message ACKs.

---

## License
MIT (adjust as needed).
