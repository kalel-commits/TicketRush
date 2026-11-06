import { describe, expect, it } from 'vitest';
import { createSseParser } from '../assistant/sse';

describe('SSE parser (spec §24.8: delta / meta / done / error)', () => {
  it('parses one event per chunk', () => {
    const p = createSseParser();
    expect(p.push('event:delta\ndata:{"text":"Hi"}\n\n')).toEqual([{ event: 'delta', data: '{"text":"Hi"}' }]);
  });

  it('parses several events in one chunk', () => {
    const p = createSseParser();
    const events = p.push('event:delta\ndata:{"text":"a"}\n\nevent:delta\ndata:{"text":"b"}\n\nevent:done\ndata:{}\n\n');
    expect(events.map((e) => e.event)).toEqual(['delta', 'delta', 'done']);
  });

  it('keeps an event split across chunks, even mid-line', () => {
    const p = createSseParser();
    expect(p.push('event:del')).toEqual([]);
    expect(p.push('ta\ndata:{"te')).toEqual([]);
    expect(p.push('xt":"Hi"}\n')).toEqual([]);
    expect(p.push('\n')).toEqual([{ event: 'delta', data: '{"text":"Hi"}' }]);
  });

  it('joins multi-line data and accepts CRLF and a space after the colon', () => {
    const p = createSseParser();
    expect(p.push('event: meta\r\ndata: line1\r\ndata: line2\r\n\r\n')).toEqual([{ event: 'meta', data: 'line1\nline2' }]);
  });

  it('defaults the event name to message', () => {
    expect(createSseParser().push('data:x\n\n')).toEqual([{ event: 'message', data: 'x' }]);
  });
});
