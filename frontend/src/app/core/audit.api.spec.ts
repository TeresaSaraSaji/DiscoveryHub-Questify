import { describe, expect, it } from 'vitest';
import {
  AUDIT_ACTIONS_BY_SERVICE,
  AUDIT_ACTION_GROUPS,
  AUDIT_OUTCOMES,
  AUDIT_SERVICES,
} from './audit.api';

/**
 * The audit filter offers choices instead of free text because P5 matches them exactly, which
 * means a wrong value here is invisible: the trail comes back empty and looks like an absence of
 * events rather than a typo in a dropdown.
 *
 * Two lists have to agree for that to hold — the grouped set the dropdown renders, and the
 * per-service map that narrows it — so the agreement is asserted rather than maintained by hand.
 */
const grouped = AUDIT_ACTION_GROUPS.flatMap((group) => group.actions);
const perService = Object.values(AUDIT_ACTIONS_BY_SERVICE).flat();

describe('audit action lists', () => {
  it('offers every service-specific action in the grouped list', () => {
    // An action reachable only through the map would never render: the dropdown iterates the
    // groups and filters them by the map, so anything missing here is silently unselectable.
    expect([...new Set(perService)].filter((action) => !grouped.includes(action))).toEqual([]);
  });

  it('attributes every grouped action to at least one service', () => {
    // The other direction: an action in no service's list disappears the moment a service is
    // chosen, and is a verb no emitter writes.
    expect(grouped.filter((action) => !perService.includes(action))).toEqual([]);
  });

  it('groups each action exactly once', () => {
    expect(grouped.length).toBe(new Set(grouped).size);
  });

  it('maps every service the dropdown can select', () => {
    // Selecting a service with no entry falls through to the unfiltered list, which would quietly
    // offer actions that service never emits.
    expect(
      AUDIT_SERVICES.map((option) => option.value).filter(
        (value) => !AUDIT_ACTIONS_BY_SERVICE[value],
      ),
    ).toEqual([]);
  });

  it('maps no service the dropdown cannot select', () => {
    const selectable = AUDIT_SERVICES.map((option) => option.value);
    expect(
      Object.keys(AUDIT_ACTIONS_BY_SERVICE).filter((key) => !selectable.includes(key)),
    ).toEqual([]);
  });
});

describe('audit outcomes', () => {
  it('offers exactly the three AuditEvent.Outcome values', () => {
    // A refusal is not a failure, and the trail's most important rows are refusals, so the set
    // being complete matters more here than anywhere else on the page.
    expect(AUDIT_OUTCOMES.map((option) => option.value)).toEqual(['SUCCESS', 'REFUSED', 'FAILURE']);
  });
});
