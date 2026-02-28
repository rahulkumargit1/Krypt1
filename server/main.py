"""
Krypt WebSocket Signaling & Relay Server
Routes encrypted payloads between UUIDs. Never decrypts content.
"""

import asyncio
import json
import logging
from typing import Dict, Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("krypt-server")

app = FastAPI(title="Krypt Server", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ─── Connection Registry ────────────────────────────────────────────────────────

class ConnectionRegistry:
    def __init__(self):
        # uuid -> WebSocket
        self.connections: Dict[str, WebSocket] = {}
        # uuid -> base64 public key
        self.public_keys: Dict[str, str] = {}

    async def register(self, uuid: str, ws: WebSocket, public_key: str):
        self.connections[uuid] = ws
        self.public_keys[uuid] = public_key
        logger.info(f"Registered: {uuid[:12]}…  Total connected: {len(self.connections)}")

    def unregister(self, uuid: str):
        self.connections.pop(uuid, None)
        # Keep public key cached so offline delivery info persists
        logger.info(f"Disconnected: {uuid[:12]}…  Total connected: {len(self.connections)}")

    async def send_to(self, target_uuid: str, payload: dict) -> bool:
        ws = self.connections.get(target_uuid)
        if ws is None:
            return False
        try:
            await ws.send_text(json.dumps(payload))
            return True
        except Exception as e:
            logger.warning(f"Failed to send to {target_uuid[:12]}: {e}")
            self.connections.pop(target_uuid, None)
            return False

    def get_public_key(self, uuid: str) -> Optional[str]:
        return self.public_keys.get(uuid)


registry = ConnectionRegistry()


# ─── WebSocket Endpoint ─────────────────────────────────────────────────────────

@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    client_uuid: Optional[str] = None

    try:
        async for raw in ws.iter_text():
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type")

            # ── Registration ───────────────────────────────────────────────────
            if msg_type == "register":
                client_uuid = msg.get("uuid")
                pub_key = msg.get("public_key", "")
                if not client_uuid:
                    continue
                await registry.register(client_uuid, ws, pub_key)
                await ws.send_text(json.dumps({
                    "type": "registered",
                    "uuid": client_uuid
                }))

            # ── Public Key Lookup ──────────────────────────────────────────────
            elif msg_type == "get_public_key":
                target = msg.get("target")
                requester = msg.get("from")
                if not target or not requester:
                    continue
                pub_key = registry.get_public_key(target)
                if pub_key:
                    await registry.send_to(requester, {
                        "type": "public_key_response",
                        "target": target,
                        "public_key": pub_key
                    })
                else:
                    await registry.send_to(requester, {
                        "type": "error",
                        "message": f"UUID {target} not found or has no public key"
                    })

            # ── Encrypted Message Relay ────────────────────────────────────────
            elif msg_type == "message":
                to = msg.get("to")
                from_uuid = msg.get("from")
                if not to or not from_uuid:
                    continue
                delivered = await registry.send_to(to, msg)
                if not delivered:
                    # Notify sender of offline recipient
                    if client_uuid:
                        await ws.send_text(json.dumps({
                            "type": "delivery_failed",
                            "to": to,
                            "reason": "recipient_offline"
                        }))

            # ── File Chunk Relay ───────────────────────────────────────────────
            elif msg_type == "file_chunk":
                to = msg.get("to")
                if to:
                    await registry.send_to(to, msg)

            # ── WebRTC Offer ───────────────────────────────────────────────────
            elif msg_type == "webrtc_offer":
                to = msg.get("to")
                if to:
                    delivered = await registry.send_to(to, msg)
                    if not delivered and client_uuid:
                        await ws.send_text(json.dumps({
                            "type": "error",
                            "message": f"Call recipient {to} is offline"
                        }))

            # ── WebRTC Answer ──────────────────────────────────────────────────
            elif msg_type == "webrtc_answer":
                to = msg.get("to")
                if to:
                    await registry.send_to(to, msg)

            # ── ICE Candidate ──────────────────────────────────────────────────
            elif msg_type == "webrtc_ice":
                to = msg.get("to")
                if to:
                    await registry.send_to(to, msg)

            # ── Status Broadcast ───────────────────────────────────────────────
            elif msg_type == "status":
                from_uuid = msg.get("from")
                if not from_uuid:
                    continue
                # Broadcast to all other connected clients
                broadcast_tasks = []
                for uuid, conn_ws in list(registry.connections.items()):
                    if uuid != from_uuid:
                        broadcast_tasks.append(
                            registry.send_to(uuid, msg)
                        )
                if broadcast_tasks:
                    await asyncio.gather(*broadcast_tasks, return_exceptions=True)

            else:
                logger.debug(f"Unknown message type: {msg_type}")

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"WebSocket error for {client_uuid}: {e}")
    finally:
        if client_uuid:
            registry.unregister(client_uuid)


# ─── HTTP Health Endpoints ──────────────────────────────────────────────────────

@app.get("/")
async def root():
    return {
        "service": "Krypt E2EE Signaling Server",
        "version": "1.0.0",
        "connected_clients": len(registry.connections)
    }

@app.get("/health")
async def health():
    return {"status": "ok", "clients": len(registry.connections)}


# ─── Entry Point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        log_level="info",
        reload=False
    )
