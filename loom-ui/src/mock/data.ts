import {
  Project, Library, Asset, Collection, Task, Comment, Annotation,
  Reaction, Pipeline, User, Group, Role, Permission, ApiKey,
  BlacklistEntry, MetricSeries, ChatMessage,
  TranscriptSection, DetectedFace, FaceCluster, Person,
  DetectedObject,
} from "../types";

// ── Helpers ───────────────────────────────────────────────────────────────
function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString();
}
function hoursAgo(n: number): string {
  const d = new Date();
  d.setHours(d.getHours() - n);
  return d.toISOString();
}
function genPoints(base: number, count: number, variance: number, dayOffset = 0): Array<{ts: string; value: number}> {
  return Array.from({ length: count }, (_, i) => ({
    ts: daysAgo(count - i + dayOffset),
    value: Math.max(0, Math.round(base + (Math.random() - 0.5) * 2 * variance)),
  }));
}

// ── Users ─────────────────────────────────────────────────────────────────
export const USERS: User[] = [
  { id: "u1", name: "Aria Chen", email: "aria@metaloom.io", username: "aria", role: "admin", groupIds: ["g1", "g2"], active: true, createdAt: daysAgo(180), lastSeenAt: hoursAgo(1) },
  { id: "u2", name: "Marcus Webb", email: "marcus@metaloom.io", username: "marcus", role: "editor", groupIds: ["g1"], active: true, createdAt: daysAgo(120), lastSeenAt: hoursAgo(4) },
  { id: "u3", name: "Priya Suresh", email: "priya@metaloom.io", username: "priya", role: "editor", groupIds: ["g2"], active: true, createdAt: daysAgo(90), lastSeenAt: daysAgo(1) },
  { id: "u4", name: "Tom Fischer", email: "tom@metaloom.io", username: "tom", role: "viewer", groupIds: ["g3"], active: true, createdAt: daysAgo(60), lastSeenAt: daysAgo(2) },
  { id: "u5", name: "Sofia Reyes", email: "sofia@metaloom.io", username: "sofia", role: "operator", groupIds: ["g2", "g3"], active: true, createdAt: daysAgo(45), lastSeenAt: hoursAgo(6) },
  { id: "u6", name: "Dev Build Bot", email: "bot@metaloom.io", username: "buildbot", role: "operator", groupIds: [], active: false, createdAt: daysAgo(200), lastSeenAt: daysAgo(10) },
];

// ── Groups ────────────────────────────────────────────────────────────────
export const GROUPS: Group[] = [
  { id: "g1", name: "Core Team", description: "Core product and engineering", memberIds: ["u1", "u2"], roleIds: ["r1", "r2"], createdAt: daysAgo(180) },
  { id: "g2", name: "Media Ops", description: "Ingestion and pipeline operators", memberIds: ["u1", "u3", "u5"], roleIds: ["r2", "r3"], createdAt: daysAgo(100) },
  { id: "g3", name: "Reviewers", description: "Content review and approval", memberIds: ["u4", "u5"], roleIds: ["r4"], createdAt: daysAgo(60) },
];

// ── Permissions ───────────────────────────────────────────────────────────
export const PERMISSIONS: Permission[] = [
  { id: "p1", resource: "asset", action: "read", description: "View assets" },
  { id: "p2", resource: "asset", action: "write", description: "Upload and edit assets" },
  { id: "p3", resource: "asset", action: "delete", description: "Delete assets" },
  { id: "p4", resource: "pipeline", action: "read", description: "View pipelines" },
  { id: "p5", resource: "pipeline", action: "execute", description: "Run pipelines" },
  { id: "p6", resource: "pipeline", action: "write", description: "Create and modify pipelines" },
  { id: "p7", resource: "user", action: "manage", description: "Manage users" },
  { id: "p8", resource: "collection", action: "write", description: "Manage collections" },
  { id: "p9", resource: "task", action: "write", description: "Create and update tasks" },
  { id: "p10", resource: "admin", action: "access", description: "Access admin panel" },
];

// ── Roles ─────────────────────────────────────────────────────────────────
export const ROLES: Role[] = [
  { id: "r1", name: "Super Admin", description: "Full system access", permissionIds: ["p1","p2","p3","p4","p5","p6","p7","p8","p9","p10"], isSystem: true, createdAt: daysAgo(180) },
  { id: "r2", name: "Editor", description: "Can read and write assets, collections, tasks", permissionIds: ["p1","p2","p4","p8","p9"], isSystem: true, createdAt: daysAgo(180) },
  { id: "r3", name: "Pipeline Operator", description: "Can view and run pipelines", permissionIds: ["p1","p4","p5"], isSystem: true, createdAt: daysAgo(100) },
  { id: "r4", name: "Reviewer", description: "Read-only access with annotation capability", permissionIds: ["p1","p4"], isSystem: false, createdAt: daysAgo(60) },
];

// ── API Keys ──────────────────────────────────────────────────────────────
export const API_KEYS: ApiKey[] = [
  { id: "k1", name: "CI Pipeline Key", prefix: "lm_ci_a8f2", ownerId: "u1", scopes: ["pipeline:execute","asset:read"], lastUsedAt: hoursAgo(2), createdAt: daysAgo(30), active: true },
  { id: "k2", name: "Mobile App Key", prefix: "lm_mob_9d3b", ownerId: "u2", scopes: ["asset:read","collection:read"], lastUsedAt: daysAgo(1), createdAt: daysAgo(14), active: true },
  { id: "k3", name: "Legacy Webhook", prefix: "lm_wh_7c1e", ownerId: "u5", scopes: ["asset:write"], lastUsedAt: daysAgo(45), expiresAt: daysAgo(-10), createdAt: daysAgo(90), active: false },
];

// ── Blacklist ─────────────────────────────────────────────────────────────
export const BLACKLIST: BlacklistEntry[] = [
  { id: "bl1", type: "ip", value: "192.168.99.200", reason: "Repeated scraping attempts", addedBy: "u1", createdAt: daysAgo(5) },
  { id: "bl2", type: "domain", value: "bad-actor.example.com", reason: "Phishing referrer", addedBy: "u1", createdAt: daysAgo(12) },
  { id: "bl3", type: "fingerprint", value: "sha256:abc123def456", reason: "DMCA takedown", addedBy: "u2", createdAt: daysAgo(20) },
  { id: "bl4", type: "user", value: "u6", reason: "Compromised bot account", addedBy: "u1", createdAt: daysAgo(8) },
];

// ── Projects ──────────────────────────────────────────────────────────────
export const PROJECTS: Project[] = [
  { id: "proj1", name: "Campaign Alpha", slug: "campaign-alpha", description: "Summer 2026 brand campaign assets", color: "#7c6af7", assetCount: 142, memberCount: 4, createdAt: daysAgo(90), libraryIds: ["lib1", "lib2"] },
  { id: "proj2", name: "Sports Archive", slug: "sports-archive", description: "Broadcast and editorial sports library", color: "#2ea8ff", assetCount: 381, memberCount: 6, createdAt: daysAgo(180), libraryIds: ["lib3", "lib4"] },
  { id: "proj3", name: "Internal Comms", slug: "internal-comms", description: "Internal video and document assets", color: "#34d58a", assetCount: 58, memberCount: 3, createdAt: daysAgo(30), libraryIds: ["lib5"] },
];

// ── Libraries ─────────────────────────────────────────────────────────────
export const LIBRARIES: Library[] = [
  { id: "lib1", projectId: "proj1", name: "Hero Videos", description: "Campaign hero video masters", assetCount: 28, createdAt: daysAgo(88) },
  { id: "lib2", projectId: "proj1", name: "Still Photos", description: "Product and lifestyle photography", assetCount: 114, createdAt: daysAgo(88) },
  { id: "lib3", projectId: "proj2", name: "Match Footage", description: "Game and highlight reels", assetCount: 220, createdAt: daysAgo(178) },
  { id: "lib4", projectId: "proj2", name: "Press Images", description: "Editorial press photos", assetCount: 161, createdAt: daysAgo(178) },
  { id: "lib5", projectId: "proj3", name: "All Hands", description: "All-hands meeting recordings", assetCount: 58, createdAt: daysAgo(28) },
];

// ── Assets ────────────────────────────────────────────────────────────────
export const ASSETS: Asset[] = [
  {
    id: "a1", projectId: "proj1", libraryId: "lib1",
    name: "Hero_Campaign_30s_Final.mp4",
    type: "video", status: "ready",
    tags: ["hero", "campaign", "approved", "30s"],
    description: "30-second hero cut for Campaign Alpha. Final approved master.",
    duration: 30, width: 3840, height: 2160,
    fileSize: 480 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a1/640/360",
    url: "https://www.w3schools.com/html/mov_bbb.mp4",
    ownerId: "u1", collectionIds: ["col1"], taskIds: ["t1", "t2"],
    createdAt: daysAgo(14), updatedAt: daysAgo(2),
    metadata: { codec: "h265", fps: 60, colorSpace: "BT.2020", deliverable: true },
  },
  {
    id: "a2", projectId: "proj1", libraryId: "lib1",
    name: "Hero_Campaign_15s_Cut.mp4",
    type: "video", status: "ready",
    tags: ["hero", "campaign", "15s", "social"],
    description: "Social media cut — 15 second version.",
    duration: 15, width: 1920, height: 1080,
    fileSize: 120 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a2/640/360",
    url: "https://www.w3schools.com/html/mov_bbb.mp4",
    ownerId: "u2", collectionIds: ["col1", "col2"], taskIds: ["t3"],
    createdAt: daysAgo(12), updatedAt: daysAgo(1),
    metadata: { codec: "h264", fps: 30, colorSpace: "BT.709", deliverable: true },
  },
  {
    id: "a3", projectId: "proj1", libraryId: "lib2",
    name: "Product_Shot_A_Hero.jpg",
    type: "image", status: "ready",
    tags: ["product", "hero", "clean"],
    description: "Primary hero product shot on white background.",
    width: 4000, height: 3000,
    fileSize: 18 * 1024 * 1024, mimeType: "image/jpeg",
    thumbnailUrl: "https://picsum.photos/seed/a3/640/480",
    url: "https://picsum.photos/seed/a3/4000/3000",
    ownerId: "u3", collectionIds: ["col2"], taskIds: ["t4"],
    createdAt: daysAgo(10), updatedAt: daysAgo(3),
    metadata: { lens: "85mm f/1.4", iso: 200, studio: "Studio B" },
  },
  {
    id: "a4", projectId: "proj1", libraryId: "lib2",
    name: "Lifestyle_Shoot_Outdoors_01.jpg",
    type: "image", status: "ready",
    tags: ["lifestyle", "outdoor", "talent"],
    description: "Lifestyle shot from outdoor session, talent release signed.",
    width: 5120, height: 3413,
    fileSize: 32 * 1024 * 1024, mimeType: "image/jpeg",
    thumbnailUrl: "https://picsum.photos/seed/a4/640/427",
    url: "https://picsum.photos/seed/a4/5120/3413",
    ownerId: "u3", collectionIds: ["col2"], taskIds: [],
    createdAt: daysAgo(8), updatedAt: daysAgo(3),
    metadata: { location: "Venice Beach, CA", talent: "cleared" },
  },
  {
    id: "a5", projectId: "proj2", libraryId: "lib3",
    name: "Match_Finals_Highlight_Reel.mp4",
    type: "video", status: "ready",
    tags: ["highlight", "finals", "broadcast"],
    description: "Official 90-minute finals highlight compilation for broadcast.",
    duration: 5400, width: 1920, height: 1080,
    fileSize: 12 * 1024 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a5/640/360",
    url: "https://www.w3schools.com/html/mov_bbb.mp4",
    ownerId: "u5", collectionIds: ["col3"], taskIds: ["t5"],
    createdAt: daysAgo(7), updatedAt: daysAgo(1),
    metadata: { event: "Championship Finals 2026", format: "MXF proxy" },
  },
  {
    id: "a6", projectId: "proj2", libraryId: "lib3",
    name: "Halftime_Show_4K.mp4",
    type: "video", status: "processing",
    tags: ["halftime", "4k", "ingest"],
    description: "Raw ingest of halftime show — processing.",
    duration: 1800, width: 3840, height: 2160,
    fileSize: 8 * 1024 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a6/640/360",
    url: "",
    ownerId: "u5", collectionIds: [], taskIds: ["t6"],
    createdAt: hoursAgo(3), updatedAt: hoursAgo(1),
    metadata: { ingestSource: "SDI Deck 4", frame: "59.94" },
  },
  {
    id: "a7", projectId: "proj2", libraryId: "lib4",
    name: "Press_Conference_Hero.jpg",
    type: "image", status: "ready",
    tags: ["press", "official", "editorial"],
    description: "Official post-match press conference team photo.",
    width: 3500, height: 2333,
    fileSize: 12 * 1024 * 1024, mimeType: "image/jpeg",
    thumbnailUrl: "https://picsum.photos/seed/a7/640/427",
    url: "https://picsum.photos/seed/a7/3500/2333",
    ownerId: "u4", collectionIds: ["col3"], taskIds: [],
    createdAt: daysAgo(5), updatedAt: daysAgo(3),
    metadata: { photographer: "Jeff Stein", agency: "AP" },
  },
  {
    id: "a8", projectId: "proj3", libraryId: "lib5",
    name: "All_Hands_Q1_2026.mp4",
    type: "video", status: "ready",
    tags: ["all-hands", "internal", "q1"],
    description: "Q1 2026 All Hands recording. Restricted to employees.",
    duration: 3600, width: 1920, height: 1080,
    fileSize: 4 * 1024 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a8/640/360",
    url: "https://www.w3schools.com/html/mov_bbb.mp4",
    ownerId: "u1", collectionIds: ["col4"], taskIds: ["t7"],
    createdAt: daysAgo(21), updatedAt: daysAgo(14),
    metadata: { confidential: true, presenter: "Aria Chen" },
  },
  {
    id: "a9", projectId: "proj1", libraryId: "lib1",
    name: "Behind_The_Scenes_BTS.mp4",
    type: "video", status: "failed",
    tags: ["bts", "draft"],
    description: "BTS footage — encoding failed, needs re-ingest.",
    duration: 240, width: 1920, height: 1080,
    fileSize: 600 * 1024 * 1024, mimeType: "video/mp4",
    thumbnailUrl: "https://picsum.photos/seed/a9/640/360",
    url: "",
    ownerId: "u2", collectionIds: [], taskIds: ["t8"],
    createdAt: daysAgo(3), updatedAt: hoursAgo(12),
    metadata: { error: "Corrupt audio track at 03:14" },
  },
];

// ── Collections ───────────────────────────────────────────────────────────
export const COLLECTIONS: Collection[] = [
  { id: "col1", projectId: "proj1", name: "Broadcast Deliverables", description: "Final approved assets cleared for broadcast", assetIds: ["a1", "a2"], ownerId: "u1", color: "#7c6af7", createdAt: daysAgo(13), updatedAt: daysAgo(2) },
  { id: "col2", projectId: "proj1", name: "Social Media Pack", description: "Cut-downs and photos for social channels", assetIds: ["a2", "a3", "a4"], ownerId: "u2", color: "#2ea8ff", createdAt: daysAgo(10), updatedAt: daysAgo(1) },
  { id: "col3", projectId: "proj2", name: "Finals Package", description: "Championship finals full media package", assetIds: ["a5", "a7"], ownerId: "u5", color: "#f5a623", createdAt: daysAgo(6), updatedAt: daysAgo(1) },
  { id: "col4", projectId: "proj3", name: "Q1 Recordings", description: "All Q1 internal meeting recordings", assetIds: ["a8"], ownerId: "u1", color: "#34d58a", createdAt: daysAgo(20), updatedAt: daysAgo(14) },
];

// ── Tasks ─────────────────────────────────────────────────────────────────
export const TASKS: Task[] = [
  { id: "t1", projectId: "proj1", title: "QC final hero video", description: "Full QC pass on Hero_Campaign_30s_Final.mp4 — check audio levels, color grade, and format compliance.", status: "in_progress", priority: "high", assigneeId: "u2", assetId: "a1", dueDate: daysAgo(-2), createdAt: daysAgo(5), updatedAt: hoursAgo(8), tags: ["qc", "high-priority"] },
  { id: "t2", projectId: "proj1", title: "Add subtitles to 30s hero", description: "Add EN subtitles and accessibility annotations to hero video.", status: "open", priority: "medium", assigneeId: "u3", assetId: "a1", dueDate: daysAgo(-5), createdAt: daysAgo(4), updatedAt: daysAgo(2), tags: ["accessibility", "subtitles"] },
  { id: "t3", projectId: "proj1", title: "Approve 15s social cut", description: "Brand sign-off on social cut. Check logo safe zone for all platforms.", status: "review", priority: "high", assigneeId: "u1", assetId: "a2", dueDate: daysAgo(-1), createdAt: daysAgo(3), updatedAt: hoursAgo(4), tags: ["approval", "social"] },
  { id: "t4", projectId: "proj1", title: "Retouch product hero shot", description: "Minor retouch: remove shadow artifact in bottom-right corner.", status: "done", priority: "low", assigneeId: "u3", assetId: "a3", dueDate: daysAgo(1), createdAt: daysAgo(7), updatedAt: daysAgo(2), tags: ["retouching"] },
  { id: "t5", projectId: "proj2", title: "Export broadcast proxy", description: "Create low-res proxy for editorial workflow.", status: "open", priority: "critical", assigneeId: "u5", assetId: "a5", dueDate: hoursAgo(-24), createdAt: daysAgo(2), updatedAt: hoursAgo(6), tags: ["proxy", "broadcast"] },
  { id: "t6", projectId: "proj2", title: "Monitor halftime ingest", description: "Track ingest progress, validate checksums when complete.", status: "in_progress", priority: "critical", assigneeId: "u5", assetId: "a6", createdAt: hoursAgo(4), updatedAt: hoursAgo(1), tags: ["ingest", "monitoring"] },
  { id: "t7", projectId: "proj3", title: "Chaptering for All Hands Q1", description: "Add chapter markers for each agenda item.", status: "open", priority: "low", assigneeId: "u2", assetId: "a8", dueDate: daysAgo(-7), createdAt: daysAgo(14), updatedAt: daysAgo(10), tags: ["chapters", "internal"] },
  { id: "t8", projectId: "proj1", title: "Re-ingest BTS footage", description: "Source file is available on Deck 3. Re-ingest and validate audio track.", status: "blocked", priority: "medium", assigneeId: "u2", assetId: "a9", createdAt: daysAgo(3), updatedAt: hoursAgo(12), tags: ["ingest", "blocked"] },
];

// ── Comments ──────────────────────────────────────────────────────────────
export const COMMENTS: Comment[] = [
  { id: "c1", assetId: "a1", authorId: "u2", title: "Audio peak at 0:08", text: "There's a noticeable audio peak at 8 seconds. Needs a trim or a limiter.", timestampStart: 8, createdAt: hoursAgo(20), updatedAt: hoursAgo(20) },
  { id: "c2", assetId: "a1", authorId: "u1", title: "Color grade approved", text: "Grade looks great overall. Approved for final delivery.", timestampStart: undefined, createdAt: hoursAgo(16), updatedAt: hoursAgo(16) },
  { id: "c3", assetId: "a1", authorId: "u3", text: "Agreed — the LUTs look clean on SDR too.", parentId: "c2", createdAt: hoursAgo(15), updatedAt: hoursAgo(15) },
  { id: "c4", assetId: "a1", authorId: "u4", title: "Logo safe zone issue", text: "Logo is getting clipped on vertical 9:16 crops around 00:22–00:27.", timestampStart: 22, timestampEnd: 27, createdAt: hoursAgo(10), updatedAt: hoursAgo(10) },
  { id: "c5", assetId: "a5", authorId: "u5", title: "Proxy needed urgently", text: "Editorial is waiting. Need proxy by EOD.", timestampStart: undefined, createdAt: hoursAgo(5), updatedAt: hoursAgo(5) },
  { id: "c6", assetId: "a5", authorId: "u2", text: "Great sequence at 43:12 — use for promo?", timestampStart: 2592, createdAt: daysAgo(1), updatedAt: daysAgo(1) },
  { id: "c7", assetId: "a8", authorId: "u1", title: "Context at 12:00", text: "This section covers Q1 headcount and hiring plans. Not for sharing externally.", timestampStart: 720, createdAt: daysAgo(20), updatedAt: daysAgo(20) },
];

// ── Annotations ───────────────────────────────────────────────────────────
export const ANNOTATIONS: Annotation[] = [
  { id: "an1", assetId: "a1", authorId: "u2", title: "Audio peak", description: "Transient peak exceeds -3dB", timestampStart: 8, timestampEnd: 9, color: "#f0546e", createdAt: hoursAgo(20) },
  { id: "an2", assetId: "a1", authorId: "u4", title: "Safe zone violation", description: "Logo crops on 9:16", timestampStart: 22, timestampEnd: 27, color: "#f5a623", createdAt: hoursAgo(10) },
  { id: "an3", assetId: "a3", authorId: "u3", title: "Shadow artifact", description: "Minor shadow at bottom right", region: { x: 0.73, y: 0.82, width: 0.18, height: 0.12 }, color: "#f5a623", createdAt: daysAgo(3) },
  { id: "an4", assetId: "a5", authorId: "u5", title: "Promo clip", description: "Strong sequence for promo reel", timestampStart: 2592, timestampEnd: 2640, color: "#34d58a", createdAt: daysAgo(1) },
  { id: "an5", assetId: "a8", authorId: "u1", title: "Confidential section", description: "Hiring and headcount data", timestampStart: 720, timestampEnd: 960, color: "#f0546e", createdAt: daysAgo(20) },
];

// ── Reactions ─────────────────────────────────────────────────────────────
export const REACTIONS: Reaction[] = [
  { id: "rx1", assetId: "a1", userId: "u1", type: "approve", rating: 5, createdAt: hoursAgo(15) },
  { id: "rx2", assetId: "a1", userId: "u4", type: "flag", createdAt: hoursAgo(10) },
  { id: "rx3", assetId: "a2", userId: "u1", type: "approve", rating: 4, createdAt: daysAgo(1) },
  { id: "rx4", assetId: "a3", userId: "u2", type: "favorite", rating: 5, createdAt: daysAgo(3) },
  { id: "rx5", assetId: "a5", userId: "u5", type: "approve", rating: 4, timestamp: 2592, createdAt: daysAgo(1) },
  { id: "rx6", assetId: "a5", userId: "u2", type: "question", timestamp: 1200, createdAt: daysAgo(1) },
];

// ── Pipelines ─────────────────────────────────────────────────────────────
export const PIPELINES: Pipeline[] = [
  {
    id: "pipe1", projectId: "proj1", name: "Campaign Ingest Pipeline",
    description: "Processes incoming campaign assets: hash, fingerprint, resize, and deliver to S3.",
    enabled: true, priority: 1, dryRun: false,
    definition: {
      nodes: [
        { id: "pn1", type: "source", label: "S3 Source", description: "Watch incoming S3 bucket", position: { x: 60, y: 160 }, data: { bucket: "loom-ingest-prod", prefix: "campaign/" } },
        { id: "pn2", type: "filter", label: "Format Filter", description: "Accept only video/image MIME types", position: { x: 260, y: 80 }, data: { types: ["video/*", "image/*"] } },
        { id: "pn3", type: "process", label: "Hash", description: "SHA-256 + perceptual hash", position: { x: 460, y: 40 }, data: { algorithms: ["sha256", "phash"] } },
        { id: "pn4", type: "process", label: "Fingerprint", description: "Generate audio/video fingerprint", position: { x: 460, y: 160 }, data: { engine: "chromaprint" } },
        { id: "pn5", type: "process", label: "Resize Proxy", description: "Generate 720p and 360p proxies", position: { x: 460, y: 280 }, data: { resolutions: ["720p", "360p"] } },
        { id: "pn6", type: "output", label: "S3 Delivery", description: "Store outputs in delivery bucket", position: { x: 680, y: 160 }, data: { bucket: "loom-delivery-prod" } },
      ],
      edges: [
        { id: "pe1", source: "pn1", target: "pn2", animated: true },
        { id: "pe2", source: "pn2", target: "pn3" },
        { id: "pe3", source: "pn2", target: "pn4" },
        { id: "pe4", source: "pn2", target: "pn5" },
        { id: "pe5", source: "pn3", target: "pn6" },
        { id: "pe6", source: "pn4", target: "pn6" },
        { id: "pe7", source: "pn5", target: "pn6" },
      ],
    },
    runs: [
      { id: "run1", pipelineId: "pipe1", startedAt: hoursAgo(2), finishedAt: hoursAgo(1), status: "success", processedAssets: 12, errors: 0, log: ["12 assets ingested", "12 hashes computed", "12 proxies generated"] },
      { id: "run2", pipelineId: "pipe1", startedAt: daysAgo(1), finishedAt: daysAgo(1), status: "success", processedAssets: 8, errors: 0, log: ["8 assets ingested"] },
      { id: "run3", pipelineId: "pipe1", startedAt: daysAgo(3), finishedAt: daysAgo(3), status: "failed", processedAssets: 4, errors: 1, log: ["4 assets processed", "ERROR: S3 timeout on delivery"] },
    ],
    createdAt: daysAgo(60), updatedAt: hoursAgo(2),
  },
  {
    id: "pipe2", projectId: "proj2", name: "Sports Broadcast Processing",
    description: "High-priority ingestion and transcoding for live sports broadcasts.",
    enabled: true, priority: 10, dryRun: false,
    definition: {
      nodes: [
        { id: "pn1", type: "source", label: "SDI Ingest", description: "SDI deck capture endpoint", position: { x: 60, y: 120 }, data: { decks: ["Deck 1", "Deck 4"] } },
        { id: "pn2", type: "process", label: "Transcode 4K", description: "Transcode to H.265 4K master", position: { x: 260, y: 60 }, data: { codec: "h265", resolution: "4K" } },
        { id: "pn3", type: "process", label: "Proxy Generation", description: "Create 1080p and 540p proxies", position: { x: 260, y: 200 }, data: { resolutions: ["1080p", "540p"] } },
        { id: "pn4", type: "filter", label: "Scene Detect", description: "AI scene change detection", position: { x: 460, y: 60 }, data: { model: "scenedetect-v3" } },
        { id: "pn5", type: "output", label: "CDN Push", description: "Push proxies to broadcast CDN", position: { x: 660, y: 140 }, data: { cdn: "akamai-sports" } },
      ],
      edges: [
        { id: "pe1", source: "pn1", target: "pn2", animated: true },
        { id: "pe2", source: "pn1", target: "pn3", animated: true },
        { id: "pe3", source: "pn2", target: "pn4" },
        { id: "pe4", source: "pn3", target: "pn5" },
        { id: "pe5", source: "pn4", target: "pn5" },
      ],
    },
    runs: [
      { id: "run4", pipelineId: "pipe2", startedAt: hoursAgo(3), status: "running", processedAssets: 3, errors: 0, log: ["Ingest started", "3 streams active"] },
    ],
    createdAt: daysAgo(120), updatedAt: hoursAgo(3),
  },
  {
    id: "pipe3", projectId: "proj1", name: "AI Tagging Pipeline",
    description: "Runs ML models to auto-tag assets with objects, faces, and sentiment.",
    enabled: true, priority: 5, dryRun: true,
    definition: {
      nodes: [
        { id: "pn1", type: "source", label: "Asset Queue", description: "Pull from ready asset queue", position: { x: 60, y: 120 }, data: { status: "ready", limit: 50 } },
        { id: "pn2", type: "process", label: "Object Detection", description: "YOLO v8 object detection", position: { x: 260, y: 60 }, data: { model: "yolov8-dam", confidence: 0.72 } },
        { id: "pn3", type: "process", label: "Face Recognition", description: "InspireFace identity matching", position: { x: 260, y: 180 }, data: { threshold: 0.85 } },
        { id: "pn4", type: "process", label: "Sentiment Score", description: "NLP scene sentiment analysis", position: { x: 460, y: 120 }, data: { model: "genai-sentiment-v2" } },
        { id: "pn5", type: "output", label: "Tag Writer", description: "Write tags back to asset metadata", position: { x: 660, y: 120 }, data: { overwrite: false } },
      ],
      edges: [
        { id: "pe1", source: "pn1", target: "pn2" },
        { id: "pe2", source: "pn1", target: "pn3" },
        { id: "pe3", source: "pn2", target: "pn4" },
        { id: "pe4", source: "pn3", target: "pn4" },
        { id: "pe5", source: "pn4", target: "pn5" },
      ],
    },
    runs: [
      { id: "run5", pipelineId: "pipe3", startedAt: daysAgo(1), finishedAt: daysAgo(1), status: "success", processedAssets: 24, errors: 2, log: ["24 assets tagged", "2 face matches skipped (low confidence)"] },
    ],
    createdAt: daysAgo(14), updatedAt: daysAgo(1),
  },
];

// ── Metrics ───────────────────────────────────────────────────────────────
export const METRICS: {
  ingestion: MetricSeries[];
  pipelineRuns: MetricSeries[];
  latency: MetricSeries[];
  storage: MetricSeries[];
  taskBacklog: MetricSeries[];
  annotations: MetricSeries[];
  chatUsage: MetricSeries[];
} = {
  ingestion: [
    { label: "Assets Ingested", color: "#7c6af7", data: genPoints(40, 14, 20) },
  ],
  pipelineRuns: [
    { label: "Successful", color: "#34d58a", data: genPoints(12, 14, 5) },
    { label: "Failed", color: "#f0546e", data: genPoints(1, 14, 1) },
  ],
  latency: [
    { label: "Avg Latency (ms)", color: "#2ea8ff", data: genPoints(340, 14, 80) },
    { label: "P99 Latency (ms)", color: "#f5a623", data: genPoints(820, 14, 200) },
  ],
  storage: [
    { label: "Storage (TB)", color: "#00c9b1", data: genPoints(8.4, 14, 0.4).map((p, i) => ({ ts: p.ts, value: 8.0 + i * 0.03 + Math.random() * 0.05 })) },
  ],
  taskBacklog: [
    { label: "Open Tasks", color: "#f5a623", data: genPoints(18, 14, 6) },
    { label: "Overdue", color: "#f0546e", data: genPoints(3, 14, 2) },
  ],
  annotations: [
    { label: "New Annotations", color: "#a597ff", data: genPoints(7, 14, 4) },
  ],
  chatUsage: [
    { label: "Agent Queries", color: "#7c6af7", data: genPoints(22, 14, 10) },
    { label: "Actions Taken", color: "#2ea8ff", data: genPoints(14, 14, 7) },
  ],
};

// ── Chat History ──────────────────────────────────────────────────────────
export const INITIAL_CHAT: ChatMessage[] = [
  {
    id: "msg0", role: "system", content: "Loom Agent is ready. Ask me about assets, collections, tasks, pipelines, or let me help you find and organize content.",
    createdAt: daysAgo(1), suggestedFollowUps: [
      "Show me assets that need review",
      "What pipelines ran today?",
      "Create a collection from flagged assets",
      "Find comments around 00:43 in the finals video",
    ],
  },
  {
    id: "msg1", role: "user", content: "Show me the latest assets in Campaign Alpha",
    createdAt: hoursAgo(3),
  },
  {
    id: "msg2", role: "assistant",
    content: "Here are the most recently updated assets in **Campaign Alpha**. The hero video and 15-second social cut are both ready. The BTS footage has a failed ingest that needs attention.",
    createdAt: hoursAgo(3),
    references: [
      { type: "asset", id: "a1", label: "Hero_Campaign_30s_Final.mp4" },
      { type: "asset", id: "a2", label: "Hero_Campaign_15s_Cut.mp4" },
      { type: "asset", id: "a9", label: "Behind_The_Scenes_BTS.mp4" },
    ],
    suggestedFollowUps: [
      "Open the hero video",
      "Show tasks for Campaign Alpha",
      "Why did the BTS ingest fail?",
    ],
  },
  {
    id: "msg3", role: "user", content: "Why did the BTS ingest fail?",
    createdAt: hoursAgo(2),
  },
  {
    id: "msg4", role: "assistant",
    content: "The BTS footage failed during encoding. The metadata shows a **corrupt audio track at 03:14**. The source file is available on Deck 3.\n\nI've already created a task to re-ingest it and assigned it to Marcus.",
    createdAt: hoursAgo(2),
    references: [
      { type: "asset", id: "a9", label: "Behind_The_Scenes_BTS.mp4" },
      { type: "task", id: "t8", label: "Re-ingest BTS footage" },
    ],
    actions: [
      { id: "act1", label: "Task created", description: "Re-ingest BTS footage assigned to Marcus Webb", status: "done", result: "Task t8 created" },
    ],
    suggestedFollowUps: [
      "Show all blocked tasks",
      "Open the re-ingest task",
    ],
  },
];

// ── Transcripts ───────────────────────────────────────────────────────────
function makeWords(text: string, startTime: number): { words: Array<{word: string; startTime: number; endTime: number; confidence: number}>; endTime: number } {
  const ws = text.split(/\s+/);
  let t = startTime;
  const words = ws.map(w => {
    const dur = 0.2 + Math.random() * 0.3;
    const gap = Math.random() * 0.15;
    const wObj = { word: w, startTime: Math.round(t * 100) / 100, endTime: Math.round((t + dur) * 100) / 100, confidence: 0.85 + Math.random() * 0.15 };
    t += dur + gap;
    return wObj;
  });
  return { words, endTime: Math.round(t * 100) / 100 };
}

const s1w = makeWords("Welcome everyone to the quarterly update. We have a packed agenda today covering product launches, financial results, and team updates.", 0);
const s2w = makeWords("First up, let's discuss the new product launch. The campaign alpha assets are performing exceptionally well across all channels. Social engagement is up forty percent compared to last quarter.", s1w.endTime + 0.5);
const s3w = makeWords("Moving on to financials. Q1 revenue came in twelve percent above target. Our media pipeline automation reduced processing costs by nearly a third. The investment in the new encoding infrastructure is already paying dividends.", s2w.endTime + 0.5);
const s4w = makeWords("Let's talk about the highlight reel we produced for the championship finals. The broadcast team pulled together the package in record time using our automated workflows.", s3w.endTime + 0.5);
const s5w = makeWords("Finally, some team updates. We're welcoming two new members to Media Ops next week. Please make sure to update your project permissions and onboard them into the relevant pipelines.", s4w.endTime + 0.5);

export const TRANSCRIPTS: Record<string, TranscriptSection[]> = {
  a1: [
    { id: "ts1", title: "Introduction", startTime: 0, endTime: s1w.endTime, words: s1w.words },
    { id: "ts2", title: "Product Launch Update", startTime: s1w.endTime + 0.5, endTime: s2w.endTime, words: s2w.words },
    { id: "ts3", title: "Financial Results", startTime: s2w.endTime + 0.5, endTime: s3w.endTime, words: s3w.words },
  ],
  a5: [
    { id: "ts4", title: "Broadcast Highlights", startTime: 0, endTime: s4w.endTime, words: s4w.words },
  ],
  a8: [
    { id: "ts5", title: "Opening Remarks", startTime: 0, endTime: s1w.endTime, words: s1w.words },
    { id: "ts6", title: "Campaign Review", startTime: s1w.endTime + 0.5, endTime: s2w.endTime, words: s2w.words },
    { id: "ts7", title: "Financials", startTime: s2w.endTime + 0.5, endTime: s3w.endTime, words: s3w.words },
    { id: "ts8", title: "Broadcast Segment", startTime: s3w.endTime + 0.5, endTime: s4w.endTime, words: s4w.words },
    { id: "ts9", title: "Team Updates", startTime: s4w.endTime + 0.5, endTime: s5w.endTime, words: s5w.words },
  ],
};

// ── Face Detection ────────────────────────────────────────────────────────
export const DETECTED_FACES: DetectedFace[] = [
  { id: "f1", assetId: "a1", timestamp: 2, boundingBox: { x: 0.3, y: 0.2, width: 0.12, height: 0.2 }, confidence: 0.97, thumbnailUrl: "https://i.pravatar.cc/80?u=f1", clusterId: "fc1" },
  { id: "f2", assetId: "a1", timestamp: 8, boundingBox: { x: 0.55, y: 0.15, width: 0.1, height: 0.18 }, confidence: 0.94, thumbnailUrl: "https://i.pravatar.cc/80?u=f2", clusterId: "fc2" },
  { id: "f3", assetId: "a1", timestamp: 15, boundingBox: { x: 0.25, y: 0.25, width: 0.11, height: 0.19 }, confidence: 0.96, thumbnailUrl: "https://i.pravatar.cc/80?u=f3", clusterId: "fc1" },
  { id: "f4", assetId: "a5", timestamp: 120, boundingBox: { x: 0.4, y: 0.1, width: 0.15, height: 0.22 }, confidence: 0.92, thumbnailUrl: "https://i.pravatar.cc/80?u=f4", clusterId: "fc3" },
  { id: "f5", assetId: "a5", timestamp: 300, boundingBox: { x: 0.2, y: 0.3, width: 0.1, height: 0.18 }, confidence: 0.89, thumbnailUrl: "https://i.pravatar.cc/80?u=f5", clusterId: "fc2" },
  { id: "f6", assetId: "a7", boundingBox: { x: 0.35, y: 0.2, width: 0.08, height: 0.15 }, confidence: 0.95, thumbnailUrl: "https://i.pravatar.cc/80?u=f6", clusterId: "fc1" },
  { id: "f7", assetId: "a7", boundingBox: { x: 0.55, y: 0.22, width: 0.09, height: 0.16 }, confidence: 0.93, thumbnailUrl: "https://i.pravatar.cc/80?u=f7", clusterId: "fc3" },
  { id: "f8", assetId: "a7", boundingBox: { x: 0.7, y: 0.18, width: 0.07, height: 0.14 }, confidence: 0.88, thumbnailUrl: "https://i.pravatar.cc/80?u=f8", clusterId: "fc4" },
  { id: "f9", assetId: "a4", boundingBox: { x: 0.42, y: 0.15, width: 0.13, height: 0.22 }, confidence: 0.91, thumbnailUrl: "https://i.pravatar.cc/80?u=f9", clusterId: "fc2" },
  { id: "f10", assetId: "a8", timestamp: 600, boundingBox: { x: 0.45, y: 0.2, width: 0.1, height: 0.18 }, confidence: 0.96, thumbnailUrl: "https://i.pravatar.cc/80?u=f10", clusterId: "fc1" },
];

export const FACE_CLUSTERS: FaceCluster[] = [
  { id: "fc1", label: "Cluster A", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f1", faceIds: ["f1", "f3", "f6", "f10"], personId: "per1" },
  { id: "fc2", label: "Cluster B", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f2", faceIds: ["f2", "f5", "f9"], personId: "per2" },
  { id: "fc3", label: "Cluster C", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f4", faceIds: ["f4", "f7"], personId: undefined },
  { id: "fc4", label: "Cluster D", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f8", faceIds: ["f8"], personId: undefined },
];

export const PERSONS: Person[] = [
  { id: "per1", name: "Aria Chen", description: "CEO and co-founder", avatarUrl: "https://i.pravatar.cc/80?u=per1", clusterIds: ["fc1"], createdAt: daysAgo(60) },
  { id: "per2", name: "Marcus Webb", description: "Lead editor", avatarUrl: "https://i.pravatar.cc/80?u=per2", clusterIds: ["fc2"], createdAt: daysAgo(45) },
  { id: "per3", name: "Sofia Reyes", description: "Pipeline operator", avatarUrl: "https://i.pravatar.cc/80?u=per3", clusterIds: [], createdAt: daysAgo(30) },
];

// ── Object Detection ──────────────────────────────────────────────────────
export const DETECTED_OBJECTS: DetectedObject[] = [
  { id: "obj1", assetId: "a1", label: "car", confidence: 0.95, boundingBox: { x: 0.1, y: 0.4, width: 0.25, height: 0.3 }, timestamp: 5 },
  { id: "obj2", assetId: "a1", label: "person", confidence: 0.92, boundingBox: { x: 0.5, y: 0.2, width: 0.12, height: 0.35 }, timestamp: 5 },
  { id: "obj3", assetId: "a1", label: "tree", confidence: 0.88, boundingBox: { x: 0.75, y: 0.1, width: 0.2, height: 0.5 }, timestamp: 12 },
  { id: "obj4", assetId: "a4", label: "dog", confidence: 0.94, boundingBox: { x: 0.3, y: 0.5, width: 0.15, height: 0.2 } },
  { id: "obj5", assetId: "a4", label: "bench", confidence: 0.87, boundingBox: { x: 0.55, y: 0.6, width: 0.3, height: 0.2 } },
  { id: "obj6", assetId: "a5", label: "building", confidence: 0.96, boundingBox: { x: 0.05, y: 0.05, width: 0.4, height: 0.7 }, timestamp: 60 },
  { id: "obj7", assetId: "a5", label: "person", confidence: 0.91, boundingBox: { x: 0.6, y: 0.3, width: 0.1, height: 0.3 }, timestamp: 120 },
  { id: "obj8", assetId: "a7", label: "laptop", confidence: 0.89, boundingBox: { x: 0.35, y: 0.45, width: 0.2, height: 0.15 } },
  { id: "obj9", assetId: "a7", label: "cup", confidence: 0.85, boundingBox: { x: 0.6, y: 0.5, width: 0.08, height: 0.12 } },
  { id: "obj10", assetId: "a8", label: "microphone", confidence: 0.93, boundingBox: { x: 0.45, y: 0.15, width: 0.08, height: 0.25 }, timestamp: 300 },
];
