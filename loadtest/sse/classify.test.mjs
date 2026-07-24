// Unit test for the SSE frame classifier — the trickiest piece of the fan-out
// check. Deterministic, no backend needed:  node sse/classify.test.mjs
import { classifyFrame } from './classify.mjs';

const S = (arr) => new Set(arr);
let failures = 0;

function expect(name, got, want) {
  const ok = got === want;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  (got '${got}', want '${want}')`);
  if (!ok) failures++;
}

// Happy path: our confirm adds our seat.
expect('our confirm -> delivery', classifyFrame(S([]), S(['A1']), 'A1'), 'delivery');

// Our confirm when other seats were already booked before we connected.
expect('our confirm after prior bookings -> delivery',
  classifyFrame(S(['C3']), S(['C3', 'A1']), 'A1'), 'delivery');

// Forbidden hold broadcast: a frame that adds no seat at all.
expect('hold broadcast, empty baseline -> leak', classifyFrame(S([]), S([]), 'A1'), 'leak');
expect('hold broadcast, unchanged booked set -> leak',
  classifyFrame(S(['B2']), S(['B2']), 'A1'), 'leak');

// Delayed hold broadcast (arrives late, still adds no seat) -> leak.
expect('delayed hold broadcast -> leak', classifyFrame(S(['B2']), S(['B2']), 'A1'), 'leak');

// Another client confirms a DIFFERENT seat (review r3643768293): must NOT be a
// false leak, and must NOT be counted as our delivery.
expect('other client confirm -> ignore', classifyFrame(S([]), S(['F6']), 'A1'), 'ignore');
expect('other client confirm on top of prior -> ignore',
  classifyFrame(S(['C3']), S(['C3', 'F6']), 'A1'), 'ignore');

// Our confirm arriving together with another client's booking -> delivery.
expect('our seat among several new seats -> delivery',
  classifyFrame(S([]), S(['F6', 'A1']), 'A1'), 'delivery');

// --- Sequential test: the baseline must ADVANCE per frame, mirroring the
// subscriber loop, so a hold rebroadcast after an unrelated booking is still
// caught (review r3643807294). ---
function runSequence(snapshot, frames, seat) {
  let seen = new Set(snapshot);
  const verdicts = [];
  for (const f of frames) {
    verdicts.push(classifyFrame(seen, new Set(f), seat));
    for (const s of f) seen.add(s); // advance running baseline
  }
  return verdicts;
}

function expectSeq(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  (got ${JSON.stringify(got)}, want ${JSON.stringify(want)})`);
  if (!ok) failures++;
}

// Other client books X (ignore) -> hold rebroadcasts the cumulative {X} (leak,
// NOT ignore, because the running baseline already contains X) -> our confirm.
expectSeq('unrelated booking then hold rebroadcast -> leak',
  runSequence([], [['X'], ['X'], ['X', 'A1']], 'A1'),
  ['ignore', 'leak', 'delivery']);

// A frozen baseline would have returned 'ignore' for the middle frame; assert
// the running baseline does not.
expectSeq('hold rebroadcast after several bookings -> leak',
  runSequence(['S0'], [['S0', 'X'], ['S0', 'X', 'Y'], ['S0', 'X', 'Y']], 'A1'),
  ['ignore', 'ignore', 'leak']);

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} test(s) FAILED`);
process.exit(failures === 0 ? 0 : 1);
