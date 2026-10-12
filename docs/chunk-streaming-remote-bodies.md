# Chunk streaming and remote bodies

## 82. Two streamers, one store

| streamer | who sees | view | tickets |
|---|---|---|---|
| `CameraChunkStream` | feed subscribers (monitor, receiver, TV link) | own `FeedViewArea` pass; player view untouched | FULL per chunk (distance 0), window led by velocity |
| `DetachedBodyStream` | operator riding a `DetachedBodyHost` from afar | whole client moved: `SetChunkCacheCenter` on host, grid origin on host | region ticket radius r at host (ticks like a player); small ticket at the parked body |

- Both = `StreamWindow` (owner identity in `ChunkStreams`, one `Encoder` per window per pass, release on leave).
- Grid origin (`MixinLevelRendererFeed`, vanilla renderer only): feed pass camera > detached host > player. Sodium: no grid, no redirect.
- Parked body (`DetachedBodies`, `MixinChunkMap`, `MixinChunkMapTrackedEntity`): view distance `bodyViewDistance`, player ticket off (`skipPlayer`) for `bodyTicketRadius`, body-side entities untracked except what it rides/carries; host root vehicle always tracked.
- Host contract (`api.DetachedBodyHost`): anchor per operator, rider positioned at anchor, anchors synced to clients (render + `DetachedView`).
- `HostWakeup`: last position per host (`wflib_host_wakeup` saved data, recorded on join/leave + every 40 t); `request(player, id, callback)` tickets the chunk until loaded and `tickCount > 1`.
- Vanilla view reaching a streamed chunk: no resend (`MixinChunkMap.wfSkipStreamedResend`).
- Vanilla chunk sends (`SharedChunkPackets`, `MixinPlayerChunkSender`, `MixinConnection`, `MixinChunkHolder`): one compression per chunk for every connection within 40 ticks; handler `wflib_shared_chunk` between `prepender` and `compress`; dropped on `ChunkHolder` block/light change. Kill switch `SharedChunkPackets.enabled`. NeoForge bundles every chunk packet with aux light => unbundled there.
- Config `detachedBody.*`, `streamDebug.*` (common). Commands: `/wflib stream audit|status|sent|sleeping|debug|reset`; client `/wfstream audit|map`.
- Verified: `mc-harness/scenarios/chunk_stream_audit.mjs` (server belief vs client held, feed + detached UAV).

