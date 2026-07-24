// Classifies one post-snapshot SSE `seats-update` frame for the fan-out check.
//
//   baseline: RUNNING set of every booked seat seen so far (seeded from the
//             connect-time snapshot and advanced by the caller after each
//             frame). It must be cumulative, not frozen at connect time —
//             otherwise a hold that rebroadcasts a booked set already grown by
//             an unrelated booking would look like it "adds" that seat.
//   booked:   Set of booked seats carried by this frame.
//   seat:     the seat this script booked.
//
// The invariant that makes classification robust — without any reliance on
// timing — is that a legitimate confirm broadcast (ours OR any other client's)
// ALWAYS adds a seat to the public booked set, whereas a forbidden hold
// broadcast changes nothing (holds are excluded from the booked list). So,
// judged against everything seen so far:
//
//   - the frame adds OUR seat         -> 'delivery' (our booking's confirm)
//   - the frame adds NO new seat      -> 'leak'     (a hold must not broadcast)
//   - the frame adds only OTHER seats -> 'ignore'   (another client's booking)
//
// Scope: this is exact when the fan-out script is the only booker on the show
// (its documented isolation precondition). Under concurrent third-party confirms
// on the SAME show, SSE frames can arrive out of order and a delayed cumulative
// confirm is indistinguishable from a hold rebroadcast here — so run the probe
// against a dedicated/idle show. See sse-fanout.mjs's header.
export function classifyFrame(baseline, booked, seat) {
  let addsOurSeat = false;
  let addsAnySeat = false;
  for (const s of booked) {
    if (!baseline.has(s)) {
      addsAnySeat = true;
      if (s === seat) addsOurSeat = true;
    }
  }
  if (addsOurSeat) return 'delivery';
  if (!addsAnySeat) return 'leak';
  return 'ignore';
}
