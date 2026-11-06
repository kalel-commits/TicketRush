/**
 * Incremental Server-Sent-Events parser for the assistant stream (spec §24.8). Network chunks can end anywhere,
 * even mid-line, so text is buffered until a blank line completes an event.
 */
export interface SseEvent {
  event: string;
  data: string;
}

export function createSseParser() {
  let buffer = '';
  return {
    push(chunk: string): SseEvent[] {
      buffer += chunk.replace(/\r\n/g, '\n');
      const events: SseEvent[] = [];
      let end: number;
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        let event = 'message';
        const data: string[] = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        }
        if (data.length || event !== 'message') events.push({ event, data: data.join('\n') });
      }
      return events;
    },
  };
}
