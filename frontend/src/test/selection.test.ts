import { describe, expect, it } from 'vitest';
import { MAX_SEATS, moveFocus, reconcile, seatLabel, toggle } from '../seatmap/selection';

const seat = (id: number, seatNo: string, status = 'AVAILABLE') => ({ id, seatNo, status });

describe('seat selection', () => {
  it('toggles seats on and off', () => {
    let r = toggle([], seat(1, 'A1'));
    expect(r.selected).toEqual([1]);
    r = toggle(r.selected, seat(1, 'A1'));
    expect(r.selected).toEqual([]);
  });

  it('refuses a 5th seat with a reason', () => {
    const four = [1, 2, 3, 4];
    const r = toggle(four, seat(5, 'A5'));
    expect(r.selected).toEqual(four);
    expect(r.refused).toMatch(/at most 4/);
    expect(MAX_SEATS).toBe(4);
  });

  it('does not select a held or booked seat', () => {
    expect(toggle([], seat(1, 'A1', 'HELD')).selected).toEqual([]);
    expect(toggle([], seat(1, 'A1', 'BOOKED')).selected).toEqual([]);
  });

  it('drops seats that someone else took in the latest poll', () => {
    expect(reconcile([1, 2], [seat(1, 'A1'), seat(2, 'A2', 'HELD')])).toEqual([1]);
  });

  it('moves focus with arrow keys inside the grid bounds', () => {
    // 2 rows x 3 seats: index 0..5
    expect(moveFocus(0, 'ArrowRight', 3, 6)).toBe(1);
    expect(moveFocus(2, 'ArrowRight', 3, 6)).toBe(2);
    expect(moveFocus(0, 'ArrowLeft', 3, 6)).toBe(0);
    expect(moveFocus(1, 'ArrowDown', 3, 6)).toBe(4);
    expect(moveFocus(4, 'ArrowDown', 3, 6)).toBe(4);
    expect(moveFocus(4, 'ArrowUp', 3, 6)).toBe(1);
  });

  it('names every seat for screen readers', () => {
    expect(seatLabel(seat(1, 'A1'), false)).toBe('Row A seat 1, available');
    expect(seatLabel(seat(2, 'B12', 'BOOKED'), false)).toBe('Row B seat 12, booked');
    expect(seatLabel(seat(3, 'A3'), true)).toBe('Row A seat 3, selected');
  });
});
