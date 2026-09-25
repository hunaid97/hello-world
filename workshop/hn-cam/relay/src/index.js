// HN_CAM relay: lets the phone app watch the board from anywhere.
//
// The board connects out to  wss://<worker>/board?key=KEY  and the app to  /view?key=KEY.
// Everything the board sends (binary: "XCAM"/"XSNP" packets, same format as Bluetooth) goes
// to every viewer; everything a viewer sends ('P' = take a photo, 'A' = frame received)
// goes to the board. The relay tells the board how many viewers there are ("V<n>") so it
// only streams while someone is watching, and tells viewers whether the board is online
// ("B1"/"B0").
//
// KEY is a Worker secret: `wrangler secret put KEY`.

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const role = url.pathname === "/board" ? "board" : url.pathname === "/view" ? "view" : null;
    if (!role) return new Response("HN_CAM relay\n", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected a WebSocket\n", { status: 426 });
    }
    if (!env.KEY || !(await sameSecret(url.searchParams.get("key") ?? "", env.KEY))) {
      return new Response("Wrong key\n", { status: 401 });
    }
    // One room for the one camera.
    const room = env.RELAY.get(env.RELAY.idFromName("hn-cam"));
    return room.fetch(new Request(`https://relay/${role}`, request));
  },
};

// Constant-time comparison, so the key can't be guessed byte by byte from response timing.
async function sameSecret(a, b) {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

export class Relay {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const role = new URL(request.url).pathname.slice(1);
    const [client, server] = Object.values(new WebSocketPair());

    if (role === "board") {
      // A new board connection replaces any old one (e.g. after the board rebooted).
      for (const old of this.state.getWebSockets("board")) old.close(1000, "replaced");
    }
    this.state.acceptWebSocket(server, [role]);
    this.announce();
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const [role] = this.state.getTags(ws);
    const to = role === "board" ? "view" : "board";
    for (const peer of this.state.getWebSockets(to)) {
      try { peer.send(message); } catch { /* peer is going away */ }
    }
  }

  webSocketClose(ws) {
    ws.close();
    this.announce(ws);
  }

  webSocketError(ws) {
    this.announce(ws);
  }

  // Tell the board how many viewers there are, and viewers whether the board is here.
  announce(leaving) {
    const open = (tag) => this.state.getWebSockets(tag).filter((s) => s !== leaving && s.readyState === 1);
    const boards = open("board");
    const viewers = open("view");
    for (const b of boards) try { b.send(`V${viewers.length}`); } catch {}
    for (const v of viewers) try { v.send(boards.length ? "B1" : "B0"); } catch {}
  }
}
