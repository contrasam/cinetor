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

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} test(s) FAILED`);
process.exit(failures === 0 ? 0 : 1);
