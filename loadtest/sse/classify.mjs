// Classifies one post-snapshot SSE `seats-update` frame for the fan-out check.
//
//   baseline: Set of booked seats from this subscriber's connect-time snapshot.
//   booked:   Set of booked seats carried by this frame.
//   seat:     the seat this script booked.
//
// The key invariant that makes classification robust — without any reliance on
// timing — is that a legitimate confirm broadcast (ours OR any other client's)
// ALWAYS adds a seat to the public booked set, whereas a forbidden hold
// broadcast changes nothing (holds are excluded from the booked list). So:
//
//   - the frame adds OUR seat         -> 'delivery' (our booking's confirm)
//   - the frame adds NO new seat      -> 'leak'     (a hold must not broadcast)
//   - the frame adds only OTHER seats -> 'ignore'   (another client's booking)
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
