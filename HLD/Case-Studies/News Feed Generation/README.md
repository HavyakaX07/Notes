
# Facebook-Style News Feed — High-Level Design (HLD)

> Author: Prasanna Hegde
> Date: 2025-12-09

---

## 1) Executive Summary
This repository documents a comprehensive **High-Level Design (HLD)** for a Facebook-like **News Feed** system. The design enables users to **publish posts**, **follow/friend others**, and **consume a personalized, chronological feed** at **massive scale** (billions of users, tens of millions of daily active users). The architecture prioritizes **availability**, embraces **eventual consistency (≤ 1 minute)**, and targets **sub-500ms** latency for common operations.

The implementation adopts a **hybrid feed generation model**:
- **Push (fan-out on write)** for **active users** to precompute and cache their feeds.
- **Pull (fan-out on read)** for **passive users** and **celebrity follow graphs**, computing feeds on demand to avoid expensive fan-out updates.

---

## 2) Scope & Assumptions
- Platforms: **Web** and **Mobile**.
- Global user base: up to **2B registered users**; assume **10M Daily Active Users (DAU)** for concrete sizing.
- Posting rate: **~5 posts/day** per active user ⇒ **~50M new posts/day**.
- Consistency: **Eventual**, tolerate **≤ 1 minute staleness**.
- Latency: Feed retrieval **< 500ms** at p95; post publish **< 300ms** for acceptance (background distribution may take longer).
- Security: Authentication via **JWT**; transport via **HTTPS/TLS**.

> These assumptions reflect and extend your original HLD notes in the uploaded document.

---

## 3) Requirements

### 3.1 Functional
- **Create Post**: Users publish text/media posts.
- **Follow/Friend**: Users build a social graph.
- **View News Feed**: Aggregate posts from followed/friended users in **reverse chronological order** (baseline ranking).

### 3.2 Non-Functional
- **Availability over Consistency** (AP systems where appropriate).
- **Scalability** to hundreds of millions of feed reads/day.
- **Performance**: p95 **< 500ms** for `GET /feed`.
- **Durability**: Posts and relationships persist across failures.
- **Observability**: Metrics, logs, traces; proactive alerting.

---

## 4) High-Level Architecture

```text
+-------------------+         +-------------------+         +--------------------+
|  Clients (Web/Mo) | <--->   |  API Gateway/ LB  |  --->   |  Services Layer    |
+-------------------+         +-------------------+         +--------------------+
                                                            |  - Post Service    |
                                                            |  - Follow Service  |
                                                            |  - Feed Service    |
                                                            +--------------------+
                                                                    |
                                             +----------------------+----------------+
                                             |                                           |
                                     +---------------+                          +------------------+
                                     | Message Queue |                          |  Cache (Redis)   |
                                     |  (Kafka/SQS)  |                          |  Feed & Objects  |
                                     +---------------+                          +------------------+
                                             |                                           |
                                     +---------------+                          +------------------+
                                     |  Storage Tier |------------------------->|  CDN (Media)     |
                                     |  (DBs)        |                          +------------------+
                                     +---------------+
                                     |  - Posts (SQL/NoSQL)
                                     |  - Users (SQL)
                                     |  - Graph (Neo4j/Index)
                                     |  - Indexes (Search)
                                     +----------------------
```

**Key Components**
- **API Gateway & Load Balancer**: Traffic routing, throttling, auth offload.
- **Post Service**: Validates, persists posts; enqueues fan-out tasks.
- **Follow Service**: Manages follow/friend relations (graph operations).
- **Feed Service**: Aggregates posts per user via push/pull; pagination & cursors.
- **Message Queue**: Asynchronous distribution of posts to follower feeds; DLQ for failures.
- **Caches (e.g., Redis/Memcached)**: Per-user feed lists; hot object caching.
- **Databases**:
  - **User/Profile** (relational, sharded by `user_id`).
  - **Posts** (relational or NoSQL; sharded by `user_id` or time-bucket + `user_id`).
  - **Graph Store** (e.g., Neo4j, or adjacency lists in SQL/NoSQL).
- **CDN**: Media delivery (images, videos) via object storage + CDN edge caching.

---

## 5) Data Model

### 5.1 User
```
User(user_id BIGINT PK, user_name VARCHAR(50), email VARCHAR(100), profile_id BIGINT,
     phone VARCHAR(20), created_at TIMESTAMP, status ENUM('active','disabled'))
```
Approx row size **~200 bytes** (as per your HLD). For **2B users**, ≈ **400GB** (excluding indexes/media).

### 5.2 Profile
```
Profile(profile_id BIGINT PK, user_id BIGINT FK, preferences JSON, image_uri TEXT,
        description TEXT, privacy JSON)
```

### 5.3 Post
```
Post(post_id BIGINT PK, user_id BIGINT FK, content_type ENUM('text','image','video'),
     text TEXT, media_uri TEXT, created_at TIMESTAMP, visibility ENUM('public','friends','custom'),
     soft_deleted BOOLEAN DEFAULT FALSE)
```

### 5.4 Follow (Graph Edge)
```
Follow(following_id BIGINT, followed_id BIGINT, created_at TIMESTAMP,
      PRIMARY KEY(following_id, followed_id))
```
Alternatively, store adjacency lists: `Followers(user_id -> list<user_id>)`, `Following(user_id -> list<user_id>)`.

### 5.5 Feed Item (Materialized)
```
FeedItem(user_id BIGINT, post_id BIGINT, author_id BIGINT, created_at TIMESTAMP,
         rank_score FLOAT DEFAULT 0, dedupe_key VARCHAR(64), PRIMARY KEY(user_id, post_id))
```

---

## 6) Sharding Strategy
- **Primary key-based sharding by `user_id`** for Users, Posts, and FeedItem ensures **locality** of a user's data and simpler queries.
- Use **consistent hashing** to evenly distribute keys and **avoid hotspots**.
- **Rebalancing** via virtual nodes; migrate shards with **online backfills**.
- **Hot Key Mitigation**: Celebrity accounts are served via **pull**; apply **read-through caches** and **rate limits**.

---

## 7) Feed Generation Models

### 7.1 Push (Fan-out on Write)
- On post publish: Post Service writes the post, emits an event; **workers** fetch follower lists and **append** `(author_id, post_id, timestamp)` to each follower's **feed cache** (e.g., Redis list).
- Pros: **Low read latency** for active users; simple pagination.
- Cons: **Expensive** for authors with **millions of followers**; requires high worker capacity.

### 7.2 Pull (Fan-out on Read)
- On feed read: Feed Service fetches **recent posts** for all followees (from Post store), **merges**, sorts **by time**, and returns.
- Pros: Avoids massive fan-out; **cost proportional** to the reader, not the author.
- Cons: Higher **read latency**; heavier DB/query workload.

### 7.3 Hybrid (Recommended)
- Maintain an **Active User Registry** (e.g., last N minutes of activity).
- **Push** for active users; **Pull** for passive users and **celebrity graphs**.
- **TTL-based cache** (e.g., 30 days) with **write-around** semantics to preserve availability and reduce write amplification.

---

## 8) Cache Design & Sizing
- **Key**: `feed:{user_id}` ⇒ list of `(post_id, author_id, timestamp)`.
- **Eviction**: TTL **30 days**; size-limited (e.g., **top 5,000 items**).
- **Invalidation**:
  - New post: append to each follower's list (push).
  - Delete/edit: mark post soft-deleted; purge or hide lazily.
- **Size Calculation**: Tuple ≈ **20 bytes** (8B `user_id` + 8B `post_id` + 4B metadata) ⇒ **5,000 items ≈ 100KB**.
- **Capacity**: With **1TB** cache, `1TB / 100KB ≈ 10,000,000` users cached per server (as in your HLD).

---

## 9) APIs

### 9.1 Publish Post
```
POST /app/v1/post
Headers: Authorization: Bearer <JWT>
Body (JSON):
{
  "content_type": "text|image|video",
  "text": "optional",
  "media_uri": "optional",
  "visibility": "public|friends|custom"
}
Response:
{
  "post_id": "1234567890",
  "status": "accepted",
  "created_at": "2025-12-10T01:08:00Z"
}
```
**Notes**: Validate payload, **idempotency-key** to handle retries; enqueue event for feed distribution.

### 9.2 Get Feed (Chronological)
```
GET /app/v1/getFeed?cursor=<ts_or_post_id>&limit=50
Headers: Authorization: Bearer <JWT>
Response:
{
  "items": [
    {"post_id": "p1", "author_id": "u2", "created_at": "...", "text": "...", "media_uri": "..."},
    ...
  ],
  "next_cursor": "...",
  "staleness_ms": 43000
}
```
**Notes**: **Cursor-based pagination**; include **staleness** indicator for observability.

### 9.3 Follow/Friend
```
PUT /app/v1/{userId}/follow
Headers: Authorization: Bearer <JWT>
Body: { "target_user_id": "..." }
Response: { "status": "ok" }
```
**Notes**: Write to **graph store**; emit event to potentially backfill feed (optional).

---

## 10) Consistency, Ordering & Idempotency
- **Eventual Consistency**: Feeds may lag **≤ 1 minute** after publish.
- **Ordering**: Use **server-assigned timestamps**; merges are stable for chronological feed.
- **Idempotency**: Deduplicate fan-out tasks via `dedupe_key = author_id:post_id`.
- **Exactly-once semantics** are impractical; aim for **at-least-once** with **idempotent consumers**.

---

## 11) Performance Targets (SLOs)
- **Feed Read**: p95 **< 500ms**, p99 **< 800ms**.
- **Post Publish**: p95 **< 300ms** (persist+enqueue), background fan-out time variable.
- **Error Rate**: < **0.1%** 5xx at p95.
- **Availability**: **99.95%** monthly target for core APIs.

---

## 12) Handling the Celebrity Problem
- For authors with **> X followers** (e.g., 1M), **disable push** fan-out.
- Readers rely on **pull** aggregation with aggressive **read caches**.
- Precompute **recent-post indices** by author to reduce pull cost.

---

## 13) Security & Privacy
- **JWT** for auth; rotate signing keys.
- **Scopes** for API permissions (read:feed, write:post).
- **Privacy Controls**: Visibility `public|friends|custom`; respect **blocks** and **muting**.
- **Compliance**: GDPR/DSAR (delete, export); **soft-delete** then background hard-delete.

---

## 14) Observability & Operations
- **Metrics**: QPS per API, latency (p50/p95/p99), cache hit ratio, fan-out backlog, DLQ depth.
- **Logs**: Structured, sampled; user privacy safe.
- **Tracing**: Distributed tracing across gateway → services → queue → DB.
- **Dashboards & Alerts**: SLO breaches, error spikes, queue lag, cache memory pressure.
- **Backpressure**: Auto-scale workers; apply circuit breakers.

---

## 15) Scalability & Capacity Planning
- **Posts/day**: ≈ **50M**.
- **Per-user feed cache**: **100KB** (top 5,000 posts).
- **Cache server capacity**: **1TB ⇒ ~10M users cached**.
- **Throughput**: Fan-out workers sized by average followers per author and target lag (≤ 1 minute).
- **Storage**: Posts with media stored in object storage; metadata in DB; use **CDN** for delivery.

---

## 16) Failure Scenarios & Recovery
- **Queue outage**: Switch impacted partitions to **pull mode**; buffer publishes.
- **Cache failures**: Fall back to **pull**; rebuild caches lazily.
- **DB partition loss**: Serve partial feeds; degrade gracefully; async repair.
- **Disaster Recovery**: Multi-region replicas; point-in-time recovery; runbooks.

---

## 17) Testing Strategy
- **Unit** for API and model validation.
- **Integration** for service interactions and queue consumers.
- **Load**: Simulate burst publishes and reads; measure SLOs.
- **Chaos**: Kill cache/queue nodes; verify fallback.

---

## 18) Future Enhancements
- **Ranking/Personalization** beyond chrono (engagement, recency, social signals, ML).
- **Topic/Hashtag feeds**; search integration.
- **Real-time streaming** (WebSockets) for live updates.
- **Anti-abuse**: Spam detection, rate limits, anomaly detection.

---

## 19) Appendix — Calculations & Notes
- **Tuple sizing**: `(user_id 8B + post_id 8B + timestamp 8B) ≈ 24B`; with metadata and structure overhead, **~20–40B** per entry is typical. Using **~20B** yields **5,000 × 20B = 100KB** per feed cache.
- **1TB/100KB** ≈ **10,737,418** (exact using binary units: 1TB=2^40, 100KB=100×2^10). Practically rounded to **~10M users** per 1TB cache node.

---

## 20) Glossary
- **Fan-out**: Distributing a single post to many follower feeds.
- **Push vs Pull**: Precompute on write vs aggregate on read.
- **TTL**: Time To Live in cache; controls automatic expiry.
- **DLQ**: Dead Letter Queue for failed messages.
- **SLO**: Service Level Objective; target performance/availability.

---

## 21) References to Original HLD Notes
The above design expands on the following points from the uploaded document:
- Core requirements and availability-first stance (eventual consistency <= 1 minute).
- API endpoints: `POST /app/v1/post`, `GET /app/v1/getFeed`, `PUT /app/v1/{userId}/follow`.
- Sharding by `user_id` to keep user data co-located.
- Hybrid feed model and celebrity problem mitigation.
- Cache sizing example **1TB / 100KB ≈ ~10M users**.

---

---

## 22) How to Use This Document
- For interviews: skim **Executive Summary**, **Architecture**, **Feed Models**, **Trade-offs**.
- For building: start with **APIs**, **Data Model**, **Cache Design**, then scale with **observability** and **capacity planning**.
