/** Pure seat-selection rules (spec §23.2): at most 4 seats, only AVAILABLE ones, keyboard movement in the grid. */
export const MAX_SEATS = 4;

export interface Seat {
  id: number;
  seatNo: string;
  status: string;
}

export function toggle(selected: number[], seat: Seat): { selected: number[]; refused?: string } {
  if (selected.includes(seat.id)) return { selected: selected.filter((id) => id !== seat.id) };
  if (seat.status !== 'AVAILABLE') return { selected, refused: 'That seat is no longer available.' };
  if (selected.length >= MAX_SEATS) return { selected, refused: `You can hold at most ${MAX_SEATS} seats.` };
  return { selected: [...selected, seat.id] };
}

/** After a poll: keep only seats that are still AVAILABLE (someone else may have taken one). */
export function reconcile(selected: number[], seats: Seat[]): number[] {
  const available = new Set(seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.id));
  return selected.filter((id) => available.has(id));
}

/** Arrow-key movement inside a grid of `columns` columns and `total` cells; stays inside the bounds. */
export function moveFocus(index: number, key: string, columns: number, total: number): number {
  const next = {
    ArrowRight: index % columns < columns - 1 ? index + 1 : index,
    ArrowLeft: index % columns > 0 ? index - 1 : index,
    ArrowDown: index + columns < total ? index + columns : index,
    ArrowUp: index - columns >= 0 ? index - columns : index,
  }[key];
  return next ?? index;
}

/** "A12" -> row "A", number "12". */
export function splitSeatNo(seatNo: string): { row: string; number: string } {
  const m = /^([A-Za-z]+)(\d+)$/.exec(seatNo);
  return m ? { row: m[1].toUpperCase(), number: m[2] } : { row: '', number: seatNo };
}

export function seatLabel(seat: Seat, selected: boolean): string {
  const { row, number } = splitSeatNo(seat.seatNo);
  const state = selected ? 'selected' : seat.status.toLowerCase();
  return `Row ${row} seat ${number}, ${state}`;
}
